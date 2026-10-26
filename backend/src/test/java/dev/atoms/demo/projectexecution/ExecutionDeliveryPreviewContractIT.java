package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.*;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.SourceExecutionBudget;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real reviewed source, native worker, owned Java app, JDBC session, delivery HTTP and isolated Chrome. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=8081", "atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false",
        "atoms.project-delivery.enabled=true", "atoms.project-delivery.origin=http://127.0.0.1:5174"})
class ExecutionDeliveryPreviewContractIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource database;
    @Autowired JdbcIndexedSessionRepository sessions;
    @MockitoBean ProjectExecutionAccess execution;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void actualVueAndBusinessApiWorkThroughOpaqueOriginPreviewAndOldGrantCannotFollowRestart() throws Exception {
        try (var app = new ExecutionApplicationFixture(jdbc, projects, transactions, database)) {
            assertThat(app.compatibleVersion(ExecutionApplicationFixture.A)).isEqualTo(2);
            var build = System.getProperty("execution.delivery-retained-build") == null
                    ? app.build(ExecutionApplicationFixture.A, ExecutionTodoFixture.application(2))
                    : app.retainedBuild(ExecutionApplicationFixture.A, "execution.delivery-retained-build", 2);
            var run = app.start(build, null);
            // The web context delegates into the fixture's actual owning coordinator; no synthetic HTTP responses.
            doAnswer(call -> app.access.running(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)))
                    .when(execution).running(any(), any(), any(), any(SourceExecutionBudget.class));
            doAnswer(call -> app.access.identity(call.getArgument(0), call.getArgument(1),
                    call.getArgument(2, ProjectExecutionAccess.RunningHandle.class), call.getArgument(3)))
                    .when(execution).identity(any(), any(), any(ProjectExecutionAccess.RunningHandle.class), any(SourceExecutionBudget.class));
            doAnswer(call -> app.access.exchange(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)))
                    .when(execution).exchange(any(), any(), any(), any(), any());
            var before = app.allTodos(ExecutionApplicationFixture.A);
            var stored = sessions.createSession();
            org.springframework.session.Session session = stored;
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ExecutionApplicationFixture.OWNER.toString(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            session.setMaxInactiveInterval(Duration.ofMinutes(20)); sessions.save(stored);
            String cookie = "ATOMS_SESSION=" + Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
            try {
                var csrf = JSON.readTree(request("GET", "/api/csrf", cookie, null, null, 200));
                var preview = JSON.readTree(request("POST", "/api/projects/" + build.projectId() + "/delivery/runs/" + run.id() + "/previews",
                        cookie, csrf.path("token").asText(), "{}", 201));
                assertThat(preview.path("identity").path("executionId").asText()).isEqualTo(run.executionId().toString());
                assertThat(preview.path("identity").path("build").path("sourceVersionId").asText()).isEqualTo(build.sourceVersionId().toString());
                Path evidence = Files.createTempDirectory(app.root, "delivery-browser-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                String marker = "delivery-" + UUID.randomUUID();
                var configuration = Map.of("iframePath", preview.path("iframeUrl").asText(), "marker", marker,
                        "executionId", run.executionId().toString(), "directory", evidence.toString(), "frontend", Path.of("../frontend").toRealPath().toString());
                Path input = evidence.resolve("preview.json");
                Files.write(Files.createFile(input, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))), JSON.writeValueAsBytes(configuration));
                var command = new ProcessBuilder(System.getProperty("execution.contract-node"),
                        Path.of("src/test/resources/projectdelivery/preview-browser.mjs").toRealPath().toString(), input.toString()).directory(evidence.toFile());
                command.environment().clear(); command.environment().put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
                var stopped = new OwnedProcess().runCommand(command, new ExecutionProcess.Deadline(Duration.ofSeconds(180)), () -> false,
                        UUID.randomUUID(), UUID.randomUUID(), stream -> capture(stream, evidence.resolve("browser.stdout")),
                        stream -> { capture(stream, evidence.resolve("browser.stderr")); return null; }, () -> {});
                assertThat(stopped.stopped()).isNotNull();
                var result = JSON.readTree(stopped.summary());
                assertThat(result.path("passed").asBoolean()).isTrue();
                var saved = app.json(ExecutionApplicationFixture.A, "GET", "api/todos/" + result.path("id").asLong(), null, 200);
                assertThat(saved.path("title").asText()).isEqualTo(marker + "-edited");
                assertThat(saved.path("priority").asInt()).isEqualTo(2);
                assertThat(app.allTodos(ExecutionApplicationFixture.A)).containsAll(before);
                String oldPath = preview.path("iframeUrl").asText();
                app.stop(run);
                assertRejected(oldPath);
                var restarted = app.start(build, null);
                assertThat(restarted.id()).isNotEqualTo(run.id()); assertRejected(oldPath);
                assertThat(app.json(ExecutionApplicationFixture.A, "GET", "api/todos/" + result.path("id").asLong(), null, 200)).isEqualTo(saved);
                app.stop(restarted); app.assertTestDatabasesUnchanged();
                System.out.printf("delivery_browser_passed build=%s source=%s run=%s evidence=%s preservedRows=%d%n",
                        build.id(), build.sourceVersionId(), run.id(), evidence, before.size());
            } finally { sessions.deleteById(session.getId()); }
        }
    }
    private String request(String method, String path, String cookie, String csrf, String body, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8081" + path)).timeout(Duration.ofSeconds(40));
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
        if (body != null) builder.header("Content-Type", "application/json");
        var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(status); return response.body();
    }
    private void assertRejected(String path) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8081" + path)).timeout(Duration.ofSeconds(40)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isIn(404, 409);
        assertThat(response.body()).doesNotContain("<html", path);
    }
    private byte[] capture(InputStream stream, Path file) throws IOException {
        byte[] bytes = stream.readNBytes(16385);
        if (bytes.length > 16384) throw new IOException("EXECUTION_OUTPUT_LIMIT");
        Files.write(Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))), bytes);
        return bytes;
    }
}
