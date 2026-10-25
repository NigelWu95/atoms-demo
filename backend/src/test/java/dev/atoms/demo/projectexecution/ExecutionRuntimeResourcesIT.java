package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native resource assembly against existing source/PG/bindings. Does not launch a generated application. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionRuntimeResourcesIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource database;
    @Autowired ProjectService projects;
    @Autowired Environment application;

    @Test void nativeFactoryUsesExistingSourceAndSeparateDatabasesWithoutStartingWorkDuringAssembly() throws Exception {
        assertThat(application.getProperty("atoms.project-execution.runtime-databases")).isEqualTo("");
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
        Path root = required("execution.runtime-fixture-root"), parent = required("execution.contract-directory");
        Path configuration = required("execution.test-config-directory");
        UUID owner = ExecutionBindingContractIT.OWNER, a = ExecutionBindingContractIT.A, b = ExecutionBindingContractIT.B;
        var businessFiles = Map.of(a, configuration.resolve(ExecutionBindingContractIT.A_DB + ".properties"),
                b, configuration.resolve(ExecutionBindingContractIT.B_DB + ".properties"));
        var business = ExecutionDatabaseBinding.load(businessFiles, List.of(root, parent), Set.of("atoms_demo", "atoms_demo_test"), Set.of("postgres", "atoms_test"));
        var beforeA = business.history(a, budget()); var beforeB = business.history(b, budget());
        var runs = new ExecutionRunRepository(jdbc); assertThat(runs.unfinished()).isEmpty();
        UUID id = jdbc.queryForObject("SELECT id FROM execution_runs WHERE project_id=? AND finished_at IS NOT NULL ORDER BY created_at DESC,id DESC LIMIT 1", UUID.class, a);
        var original = runs.get(a, id); int runCount = jdbc.queryForObject("SELECT count(*) FROM execution_runs", Integer.class);
        Path executionRoot = parent.resolve("runtime-environment-" + UUID.randomUUID());
        try (var source = new SourceAgentFixture(jdbc, projects, transactions, root)) {
            source.start(database);
            var config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                    .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
            for (String name : List.of("root", "node", "npm", "java", "maven", "policy"))
                config.withProperty("atoms.project-execution." + name, name.equals("root") ? executionRoot.toString() : parent.resolve("not-captured-" + name).toString());
            config.withProperty("atoms.project-execution.test-databases", new ObjectMapper().writeValueAsString(Map.of(
                    a.toString(), configuration.resolve(ExecutionBindingContractIT.A_TEST + ".properties").toString(),
                    b.toString(), configuration.resolve("atoms_exec_b_test_20261020.properties").toString())));
            var runtimeFiles = new HashMap<String, String>(); businessFiles.forEach((project, file) -> runtimeFiles.put(project.toString(), file.toString()));
            config.withProperty("atoms.project-execution.runtime-databases", new ObjectMapper().writeValueAsString(runtimeFiles));
            var edits = mock(ProjectSourceEditGateway.class); var preparations = mock(DependencyPreparationService.class);
            var factory = new ExecutionEnvironment(new ExecutionSettings(config), config, source.readGateway(), source.environment(),
                    projects, jdbc, transactions, edits, preparations);
            try (var lease = new ExecutionCoordinatorLease(database); var resources = factory.open()) {
                var checks = new AtomicInteger();
                var runtime = resources.runtime(() -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(lease.healthy()).isTrue(); checks.incrementAndGet();
                });
                assertThat(runtime).isNotNull(); assertThat(checks).hasValue(0);
                assertThat(executionRoot.resolve("runs")).isEmptyDirectory(); assertThat(executionRoot.resolve("builds")).isEmptyDirectory();
                assertThat(runtime.stop(owner, a, id)).isEqualTo(original); // Real authorized, idempotent terminal stop.
                assertThat(checks).hasValue(1); assertThat(runtime.hasHeldProcesses()).isFalse();
                verifyNoInteractions(edits, preparations);
                config.withProperty("atoms.project-execution.runtime-databases", "");
                assertThatThrownBy(() -> runtime.stop(owner, a, id)).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE").hasNoCause();
                runtime.requestStopOwned();
                assertThat(runs.get(a, id)).isEqualTo(original);
            }
            try (var reopened = new ExecutionPaths(executionRoot, List.of(root)); var lease = new ExecutionCoordinatorLease(database)) {
                assertThat(lease.healthy()).isTrue(); reopened.checkedRoot();
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_runs", Integer.class)).isEqualTo(runCount);
        assertThat(runs.unfinished()).isEmpty();
        assertThat(business.history(a, budget())).isEqualTo(beforeA); assertThat(business.history(b, budget())).isEqualTo(beforeB);
    }

    private Path required(String key) throws Exception {
        String raw = System.getProperty(key); assertThat(raw).as(key).isNotBlank();
        Path path = Path.of(raw); assertThat(path.isAbsolute()).isTrue(); ExecutionRootLease.noLinks(path); return path.toRealPath();
    }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(20)); }
}
