package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real workbench UI -> HTTP -> original native coordinator -> A/B processes. Mockito only delegates into that owner. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=8081", "atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.agent.enabled=false",
        "atoms.generation.worker-enabled=false", "atoms.project-workbench.enabled=true",
        "atoms.project-delivery.enabled=true", "atoms.project-delivery.origin=http://127.0.0.1:5174"})
class ExecutionWorkbenchContractIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource database;
    @Autowired JdbcIndexedSessionRepository sessions;
    @MockitoBean ProjectExecutionAccess execution;
    @MockitoBean ProjectExecutionWorkbench runtime;
    @MockitoBean ExecutionQuery queries;
    @MockitoBean ProjectSourceWorkbenchGateway source;
    @MockitoBean ProjectSourceReadGateway sourceRead;
    @MockitoBean ProjectSourceRestorationGateway restoration;

    @Test void formalPageInspectsStartsPreviewsAndDownloadsExistingApplications() throws Exception {
        try (var app = new ExecutionApplicationFixture(jdbc, projects, transactions, database)) {
            var buildA = app.retainedBuild(ExecutionApplicationFixture.A, "execution.delivery-retained-build", 2);
            // B retains the reviewed EX26 V2, before the portable test-config successor used by A.
            var buildB = app.retainedBuild(ExecutionApplicationFixture.B, "execution.workbench-retained-build-b",
                    "a786407f59d6ad08be8c77aaad27458c5552b1061c9af5438b5ebc3b4c9363d0");
            var rowsA = app.storedTodos(ExecutionApplicationFixture.A); var rowsB = app.storedTodos(ExecutionApplicationFixture.B);
            var historyA = app.history(ExecutionApplicationFixture.A); var historyB = app.history(ExecutionApplicationFixture.B);
            bridge(app);
            var stored = sessions.createSession(); org.springframework.session.Session session = stored;
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ExecutionApplicationFixture.OWNER.toString(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            session.setMaxInactiveInterval(Duration.ofMinutes(20)); sessions.save(stored);
            Path evidence = Files.createTempDirectory(app.root, "workbench-browser-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try {
                var configuration = Map.of("session", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)),
                        "directory", evidence.toString(), "frontend", Path.of("../frontend").toRealPath().toString(),
                        "applications", List.of(Map.of("project", buildA.projectId(), "build", buildA.id(), "source", buildA.sourceVersionId()),
                                Map.of("project", buildB.projectId(), "build", buildB.id(), "source", buildB.sourceVersionId())));
                Path input = evidence.resolve("input.json");
                Files.write(Files.createFile(input, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))), new ObjectMapper().writeValueAsBytes(configuration));
                var command = new ProcessBuilder(System.getProperty("execution.contract-node"),
                        Path.of("src/test/resources/projectdelivery/workbench-browser.mjs").toRealPath().toString(), input.toString()).directory(evidence.toFile());
                command.environment().clear(); command.environment().put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
                var result = new OwnedProcess().runCommand(command, new ExecutionProcess.Deadline(Duration.ofMinutes(8)), () -> false,
                        UUID.randomUUID(), UUID.randomUUID(), stream -> capture(stream, evidence.resolve("browser.stdout")),
                        stream -> { capture(stream, evidence.resolve("browser.stderr")); return null; }, () -> {});
                assertThat(result.stopped()).isNotNull();
                assertThat(new ObjectMapper().readTree(result.summary()).path("ok").asBoolean()).as("Native workbench browser evidence: %s", evidence).isTrue();
                assertThat(new ExecutionRunRepository(jdbc).unfinished()).isEmpty();
                assertThat(app.storedTodos(ExecutionApplicationFixture.A)).isEqualTo(rowsA);
                assertThat(app.storedTodos(ExecutionApplicationFixture.B)).isEqualTo(rowsB);
                assertThat(app.history(ExecutionApplicationFixture.A)).isEqualTo(historyA);
                assertThat(app.history(ExecutionApplicationFixture.B)).isEqualTo(historyB);
                app.assertTestDatabasesUnchanged();
                System.out.printf("workbench_native_passed evidence=%s preservedA=%d preservedB=%d%n", evidence, rowsA.size(), rowsB.size());
            } finally { sessions.deleteById(session.getId()); }
        }
    }
    private void bridge(ExecutionApplicationFixture app) {
        var sources = app.source.workbenchGateway(); var restores = app.source.restorationGateway();
        when(sourceRead.readyForExecution()).thenReturn(true);
        doAnswer(c -> { app.query.authorizeProject(c.getArgument(0), c.getArgument(1)); return null; }).when(queries).authorizeProject(any(), any());
        doAnswer(c -> app.query.tasks(c.getArgument(0), c.getArgument(1), c.getArgument(2), c.getArgument(3), c.getArgument(4))).when(queries).tasks(any(), any(), any(), nullable(String.class), anyInt());
        doAnswer(c -> app.query.events(c.getArgument(0), c.getArgument(1), c.getArgument(2), c.getArgument(3), c.getArgument(4), c.getArgument(5))).when(queries).events(any(), any(), any(), any(), nullable(String.class), anyInt());
        doAnswer(c -> sources.versions(c.getArgument(0), c.getArgument(1), c.getArgument(2), c.getArgument(3))).when(source).versions(any(), any(), nullable(Long.class), anyInt());
        doAnswer(c -> sources.operations(c.getArgument(0), c.getArgument(1))).when(source).operations(any(), any());
        doAnswer(c -> sources.tree(c.getArgument(0), c.getArgument(1), c.getArgument(2))).when(source).tree(any(), any(), any());
        doAnswer(c -> sources.file(c.getArgument(0), c.getArgument(1), c.getArgument(2), c.getArgument(3))).when(source).file(any(), any(), any(), anyString());
        doAnswer(c -> sources.diff(c.getArgument(0), c.getArgument(1), c.getArgument(2), c.getArgument(3))).when(source).diff(any(), any(), any(), any());
        doAnswer(c -> restores.head(c.getArgument(0), c.getArgument(1))).when(restoration).head(any(), any());
        doAnswer(c -> app.workbench.availability(c.getArgument(0), c.getArgument(1))).when(runtime).availability(any(), any());
        doAnswer(c -> app.workbench.migration(c.getArgument(0), c.getArgument(1), c.getArgument(2))).when(runtime).migration(any(), any(), any());
        doAnswer(c -> app.workbench.approve(c.getArgument(0), c.getArgument(1), c.getArgument(2), c.getArgument(3), c.getArgument(4))).when(runtime).approve(any(), any(), any(), anyString(), anyString());
        doAnswer(c -> app.workbench.start(c.getArgument(0), c.getArgument(1), c.getArgument(2), c.getArgument(3))).when(runtime).start(any(), any(), any(), anyString());
        doAnswer(c -> app.workbench.stop(c.getArgument(0), c.getArgument(1), c.getArgument(2))).when(runtime).stop(any(), any(), any());
        doAnswer(c -> app.access.running(c.getArgument(0), c.getArgument(1), c.getArgument(2), c.getArgument(3))).when(execution).running(any(), any(), any(), any(SourceExecutionBudget.class));
        doAnswer(c -> app.access.identity(c.getArgument(0), c.getArgument(1), c.getArgument(2, ProjectExecutionAccess.RunningHandle.class), c.getArgument(3))).when(execution).identity(any(), any(), any(ProjectExecutionAccess.RunningHandle.class), any(SourceExecutionBudget.class));
        doAnswer(c -> app.access.identity(c.getArgument(0), c.getArgument(1), c.getArgument(2, ProjectExecutionAccess.BuildHandle.class), c.getArgument(3))).when(execution).identity(any(), any(), any(ProjectExecutionAccess.BuildHandle.class), any(SourceExecutionBudget.class));
        doAnswer(c -> app.access.exchange(c.getArgument(0), c.getArgument(1), c.getArgument(2), c.getArgument(3), c.getArgument(4))).when(execution).exchange(any(), any(), any(), any(), any());
        doAnswer(c -> { app.access.authorizeProject(c.getArgument(0), c.getArgument(1), c.getArgument(2)); return null; }).when(execution).authorizeProject(any(), any(), any());
        doAnswer(c -> app.access.build(c.getArgument(0), c.getArgument(1), c.getArgument(2), c.getArgument(3))).when(execution).build(any(), any(), any(), any());
        doAnswer(c -> app.access.source(c.getArgument(0), c.getArgument(1), c.getArgument(2, ProjectExecutionAccess.BuildHandle.class), c.getArgument(3))).when(execution).source(any(), any(), any(ProjectExecutionAccess.BuildHandle.class), any(SourceExecutionBudget.class));
        doAnswer(c -> app.access.source(c.getArgument(0), c.getArgument(1), c.getArgument(2, ProjectExecutionAccess.RunningHandle.class), c.getArgument(3))).when(execution).source(any(), any(), any(ProjectExecutionAccess.RunningHandle.class), any(SourceExecutionBudget.class));
    }
    private static byte[] capture(java.io.InputStream stream, Path file) throws java.io.IOException {
        byte[] bytes = stream.readNBytes(1 << 20); Files.write(file, bytes); return bytes;
    }
}
