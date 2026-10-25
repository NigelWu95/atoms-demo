package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Same coordinator/root/PG lease; actual resident and build JVMs, with explicit controlled executable fixtures. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionRuntimeSchedulerIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource database;
    static final UUID OWNER = ExecutionBindingContractIT.OWNER, PROJECT = ExecutionBindingContractIT.A;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void coordinatorOwnsAdmissionResidenceAndShutdownWhileBuildWorkersRemainAvailable(boolean closeActive) throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo("atoms_demo_test.atoms_verify_20261024_ex16");
        var runs = new ExecutionRunRepository(jdbc); assertThat(runs.unfinished()).isEmpty();
        Path root = required("execution.runtime-fixture-root"), parent = required("execution.contract-directory"), configuration = required("execution.test-config-directory");
        var registry = ExecutionDatabaseBinding.load(Map.of(PROJECT, configuration.resolve(ExecutionBindingContractIT.A_DB + ".properties")),
                List.of(root, parent), Set.of("atoms_demo", "atoms_demo_test"), Set.of("postgres", "atoms_test"));
        var target = registry.verify(PROJECT, budget()); var history = registry.history(PROJECT, budget());
        var tree = new ProjectSourcePolicy().validate(List.of(new SourceTree.File(ExecutionMigrations.SOURCE + "V001__execution_contract_fixture.sql",
                ExecutionRunAdmissionIT.SQL.getBytes(StandardCharsets.UTF_8), 0100644)));
        try (var source = new SourceAgentFixture(jdbc, projects, transactions, root)) {
            source.start(database); var base = source.initializeExisting(OWNER, PROJECT, tree);
            var tools = ExecutionToolchain.capture(ExecutionToolchainTest.installation(parent.resolve("dispatcher-tools-" + UUID.randomUUID())), List.of(root), budget());
            Path executionRoot = parent.resolve("runtime-dispatch-" + UUID.randomUUID());
            var environment = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                    .withProperty("atoms.project-execution.maintainer-ids", OWNER.toString())
                    .withProperty("atoms.project-execution.max-running-projects", "1")
                    .withProperty("atoms.project-execution.root", executionRoot.toString());
            for (String name : List.of("node", "npm", "java", "maven", "policy")) environment.withProperty("atoms.project-execution." + name, parent.resolve(name).toString());
            environment.withProperty("atoms.project-execution.java", tools.javaHome().resolve("bin/java").toString());
            var settings = new ExecutionSettings(environment);
            var fixture = new ExecutionRuntimeFixture(settings, projects, jdbc, transactions, source.readGateway(), tools, registry, target);
            var first = fixture.seal(base, tree); var second = fixture.seal(base, tree);
            var builds = new ExecutionBuildRepository(jdbc);
            var coordinator = new ExecutionCoordinator(settings, builds, jdbc, transactions, database, source.readGateway()::readyForExecution, () -> fixture);
            try {
                coordinator.start(); assertThat(coordinator.isRunning()).isTrue();
                String key = "managed-" + UUID.randomUUID();
                var admitted = concurrentStart(coordinator, first.id(), target.id(), key);
                UUID lastRun = admitted.id();
                await(() -> runs.get(PROJECT, admitted.id()).status().equals("READY"));
                assertThat(coordinator.isRunning()).isTrue(); assertThat(fixture.launches).hasValue(1);
                assertThat(fixture.process.get().isServing()).isTrue();
                assertThat(coordinator.startApplication(OWNER, PROJECT, first.id(), target.id(), null, key).toCompletableFuture().get().id()).isEqualTo(admitted.id());
                if (!closeActive) {
                    var buildService = new ProjectExecutionService(settings, builds, projects, source.readGateway(), transactions);
                    var work = buildService.submit(OWNER, PROJECT, base.versionId(), "while-serving-" + UUID.randomUUID());
                    Path marker = executionRoot.resolve("builds/" + work.id() + "/project/frontend/started");
                    await(() -> Files.exists(marker));
                    assertThat(runs.get(PROJECT, admitted.id()).status()).isEqualTo("READY");
                    assertThat(fixture.process.get().isServing()).isTrue();
                    buildService.cancel(OWNER, PROJECT, work.id()); await(() -> builds.get(PROJECT, work.id()).finishedAt() != null);
                    assertThat(fixture.builds.stopped).containsKey(work.id());
                    var stopping = coordinator.replaceApplication(OWNER, PROJECT, admitted.id(), second.id(), target.id(), null, key + "-replace")
                            .toCompletableFuture().get(30, TimeUnit.SECONDS);
                    assertThat(stopping.id()).isEqualTo(admitted.id()); assertThat(stopping.stopRequested()).isTrue();
                    await(() -> runs.get(PROJECT, admitted.id()).finishedAt() != null);
                    assertThat(runs.get(PROJECT, admitted.id()).status()).isEqualTo("STOPPED");
                    var replacement = replaceAfterSettlement(coordinator, admitted.id(), second.id(), target.id(), key + "-replace");
                    lastRun = replacement.id();
                    await(() -> runs.get(PROJECT, replacement.id()).status().equals("READY"));
                    assertThat(fixture.launches).hasValue(2);
                    coordinator.stopApplication(OWNER, PROJECT, replacement.id()); await(() -> runs.get(PROJECT, replacement.id()).finishedAt() != null);
                    assertThat(runs.get(PROJECT, replacement.id()).status()).isEqualTo("STOPPED");
                }
                coordinator.close();
                assertThat(fixture.builds.closed).isTrue(); assertThat(fixture.process.get().isAlive()).isFalse();
                assertThat(runs.unfinished()).isEmpty();
                var outcome = fixture.process.get().await(Duration.ofSeconds(1)); assertThat(outcome.stopped()).isNotNull();
                var last = runs.get(PROJECT, lastRun);
                assertThat(last.stoppedSha256()).isEqualTo(outcome.stopped().digest());
                assertThat(outcome.stopped().matches(lastRun, last.executionId(), executionRoot.resolve("runs/" + lastRun))).isTrue();
                try (var reopened = new ExecutionPaths(executionRoot, List.of(root)); var lease = new ExecutionCoordinatorLease(database)) {
                    reopened.checkedRoot(); assertThat(lease.healthy()).isTrue();
                }
            } finally { coordinator.close(); }
        }
        assertThat(registry.history(PROJECT, budget())).isEqualTo(history);
    }

    private ExecutionRun concurrentStart(ExecutionCoordinator coordinator, UUID build, UUID binding, String key) throws Exception {
        var callers = Executors.newFixedThreadPool(2); var gate = new CyclicBarrier(2);
        try {
            Callable<ExecutionRun> call = () -> { gate.await(5, TimeUnit.SECONDS); return coordinator.startApplication(OWNER, PROJECT, build, binding, null, key).toCompletableFuture().get(30, TimeUnit.SECONDS); };
            var first = callers.submit(call); var second = callers.submit(call);
            var a = first.get(35, TimeUnit.SECONDS); var b = second.get(35, TimeUnit.SECONDS); assertThat(a.id()).isEqualTo(b.id()); return a;
        } finally { callers.shutdownNow(); assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    private ExecutionRun replaceAfterSettlement(ExecutionCoordinator coordinator, UUID old, UUID build, UUID binding, String key) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            try { return coordinator.replaceApplication(OWNER, PROJECT, old, build, binding, null, key).toCompletableFuture().get(30, TimeUnit.SECONDS); }
            catch (ApiException busy) { if (!busy.code().equals("RUN_PROJECT_BUSY") || System.nanoTime() >= deadline) throw busy; Thread.sleep(25); }
        }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    private Path required(String key) throws Exception {
        String value = System.getProperty(key); assertThat(value).as(key).isNotBlank();
        Path path = Path.of(value); assertThat(path.isAbsolute()).isTrue(); ExecutionRootLease.noLinks(path); return path.toRealPath();
    }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
}
