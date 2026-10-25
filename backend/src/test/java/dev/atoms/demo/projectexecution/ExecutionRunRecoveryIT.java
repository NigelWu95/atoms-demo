package dev.atoms.demo.projectexecution;

import com.zaxxer.hikari.HikariDataSource;
import dev.atoms.demo.execution.OwnedProcess;
import java.io.File;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Actual coordinator JVM halt, PG commits, root/database leases and HTTP child; no generated app or migration. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionRunRecoveryIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired HikariDataSource database;
    @TempDir Path temporary;
    static final UUID OWNER = ExecutionBindingContractIT.OWNER, PROJECT = ExecutionBindingContractIT.A;

    @ParameterizedTest @EnumSource(ExecutionRunCrashProbe.Window.class)
    void actualCoordinatorCrashNeverReplaysOrAdoptsThePreviousApplication(ExecutionRunCrashProbe.Window window) throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo("atoms_demo_test.atoms_verify_20261024_ex16");
        Path parent = required("execution.contract-directory"), configuration = required("execution.test-config-directory");
        var registry = ExecutionDatabaseBinding.load(Map.of(PROJECT, configuration.resolve(ExecutionBindingContractIT.A_DB + ".properties")),
                List.of(parent), Set.of("atoms_demo", "atoms_demo_test"), Set.of("postgres", "atoms_test"));
        var target = registry.verify(PROJECT, budget()); var history = registry.history(PROJECT, budget());
        var runs = new ExecutionRunRepository(jdbc); assertThat(runs.unfinished()).isEmpty();
        assertThat(new ExecutionBindingRepository(jdbc).get(PROJECT).target()).isEqualTo(target);
        // Reuse an existing real source/build identity; recovery itself never opens or launches its artifact.
        UUID build = jdbc.queryForObject("SELECT id FROM execution_builds WHERE project_id=? AND status='SUCCEEDED' ORDER BY created_at DESC,id DESC LIMIT 1", UUID.class, PROJECT);
        var run = new TransactionTemplate(transactions).execute(tx -> runs.admit(OWNER, PROJECT,
                ExecutionRun.request(build, target.id(), null, "crash-" + UUID.randomUUID())));
        Path evidence = Files.createTempDirectory(parent, "run-recovery-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path root = evidence.resolve("execution"), childDirectory = ExecutionRootLease.directory(evidence.resolve("coordinator"));
        var config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", OWNER.toString());
        for (String name : List.of("root", "node", "npm", "java", "maven", "policy"))
            config.withProperty("atoms.project-execution." + name, name.equals("root") ? root.toString() : evidence.resolve(name).toString());
        var settings = new ExecutionSettings(config); var builds = new ExecutionBuildRepository(jdbc);
        Path input = crashInput(root, run, window);
        Path java = Path.of(System.getProperty("java.home"));
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).split(File.pathSeparator))
                .map(p -> Path.of(p).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        var invocation = new ExecutionProcess.Invocation(java.resolve("bin/java"),
                List.of("-cp", classpath, ExecutionRunCrashProbe.class.getName(), input.toString()),
                childDirectory, childDirectory, childDirectory, java, java.resolve("bin"));
        UUID probeTask = UUID.randomUUID(), probeExecution = UUID.randomUUID();
        var threads = Executors.newSingleThreadExecutor();
        Future<Void> child = threads.submit(() -> {
            try {
                new ExecutionProcess().run(invocation, new ExecutionProcess.Deadline(Duration.ofSeconds(60)),
                        () -> false, probeTask, probeExecution, () -> {});
                throw new AssertionError("Crash probe must halt");
            } catch (OwnedProcess.Failure failure) {
                assertThat(failure.stopped()).isNotNull();
                assertThat(failure.stopped().matches(probeTask, probeExecution, childDirectory)).isTrue();
            }
            return null;
        });
        ExecutionRunProcess process = null; ExecutionCoordinator coordinator = null;
        Path cwd = root.resolve("runs/" + run.id());
        try {
            if (window != ExecutionRunCrashProbe.Window.BEFORE_INTENT) {
                await(() -> Files.exists(evidence.resolve("request")) || child.isDone());
                assertThat(evidence.resolve("request")).exists();
                UUID execution = UUID.fromString(Files.readString(evidence.resolve("request")));
                assertThat(runs.get(PROJECT, run.id()).executionPending()).isTrue();
                var command = new ProcessBuilder(java.resolve("bin/java").toString(), "--add-modules", "jdk.httpserver", "-cp",
                        Path.of("target/test-classes").toAbsolutePath().toString(), ExecutionHealthTest.Child.class.getName(),
                        Integer.toString(target.port()), run.id().toString(), execution.toString(), "valid").directory(cwd.toFile());
                command.environment().clear(); // HTTP-only fixture gets neither business credentials nor SQL capability.
                process = ExecutionRunProcess.start(command, run.id(), execution, budget(), () -> {});
                var proof = health(process, run.id(), execution, target);
                if (window == ExecutionRunCrashProbe.Window.AFTER_READY)
                    new TransactionTemplate(transactions).execute(tx -> runs.ready(run, execution, target, proof));
                Files.writeString(evidence.resolve("executed"), "observed", StandardOpenOption.CREATE_NEW);
            }
            child.get(50, TimeUnit.SECONDS);
            assertThat(evidence.resolve("fixture-failure")).doesNotExist();
            assertThat(Files.readString(evidence.resolve("halt-window"))).isEqualTo(window.name());
            assertThat(evidence.resolve("graceful-shutdown")).doesNotExist();
            var before = runs.get(PROJECT, run.id());
            if (window == ExecutionRunCrashProbe.Window.BEFORE_INTENT) {
                // A real unavailable connection pool (only this fixture's pool) must not consume the abandoned row.
                var unavailablePool = new HikariDataSource(); unavailablePool.close();
                try (var lease = new ExecutionCoordinatorLease(database)) {
                    var recovery = new ExecutionRunRecovery(settings, new ExecutionRunRepository(new JdbcTemplate(unavailablePool)),
                            new org.springframework.jdbc.datasource.DataSourceTransactionManager(unavailablePool), lease, () -> true);
                    assertThatThrownBy(() -> recovery.reconcile(before)).hasMessage("RUN_RECOVERY_UNAVAILABLE").hasNoCause();
                    assertThat(runs.get(PROJECT, run.id())).isEqualTo(before);
                }
            }
            var opened = new AtomicInteger(); var closed = new AtomicInteger();
            ExecutionCoordinator.Factory factory = () -> {
                var paths = new ExecutionPaths(root, List.of()); opened.incrementAndGet();
                return new ExecutionCoordinator.WorkerResources() {
                    @Override public void check() throws java.io.IOException { paths.checkedRoot(); }
                    @Override public ExecutionBuild run(ExecutionBuild work, BooleanSupplier cancelled, OwnedProcess.Preflight coordination) {
                        throw new AssertionError("Recovery must not execute a build");
                    }
                    @Override public void close() throws java.io.IOException { paths.close(); closed.incrementAndGet(); }
                };
            };
            coordinator = new ExecutionCoordinator(settings, builds, jdbc, transactions, database, () -> true, factory);
            coordinator.start();
            var recovered = runs.get(PROJECT, run.id());
            boolean neverIssued = window == ExecutionRunCrashProbe.Window.BEFORE_INTENT;
            assertThat(recovered.status()).isEqualTo(neverIssued ? "FAILED" : "NEEDS_RECONCILIATION");
            assertThat(recovered.errorCode()).isEqualTo(neverIssued ? "RUN_INTERRUPTED" : "RUN_RECOVERY_UNCERTAIN");
            assertThat(recovered.executionId()).isEqualTo(before.executionId());
            assertThat(recovered.executionPending()).isEqualTo(!neverIssued);
            assertThat(recovered.readyAt()).isEqualTo(before.readyAt()); assertThat(recovered.stoppedSha256()).isNull();
            assertThat(coordinator.isRunning()).isEqualTo(neverIssued);
            assertThat(opened).hasValue(1);
            if (process != null) assertThat(process.isAlive()).isTrue();
            if (neverIssued) verifyUnownedIntentStopsDispatch(coordinator, runs, run, root, closed);
            coordinator.close(); assertThat(closed).hasValue(1);
            // A second coordinator actually reopens both leases; no previous ticket or process is reconstructed.
            coordinator = new ExecutionCoordinator(settings, builds, jdbc, transactions, database, () -> true, factory);
            coordinator.start(); assertThat(runs.get(PROJECT, run.id())).isEqualTo(recovered);
            assertThat(coordinator.isRunning()).isEqualTo(neverIssued); coordinator.close();
            assertThat(opened).hasValue(2); assertThat(closed).hasValue(2);
            assertThat(registry.history(PROJECT, budget())).isEqualTo(history);
            assertThat(new ExecutionBindingRepository(jdbc).get(PROJECT).target()).isEqualTo(target);
            Files.writeString(evidence.resolve("verified"), run.id() + "\n" + window + "\n" + recovered.status() + "\n", StandardOpenOption.CREATE_NEW);
        } finally {
            if (!child.isDone()) child.cancel(true);
            threads.shutdownNow(); assertThat(threads.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            if (process != null) {
                process.requestStop(); var stopped = process.await(Duration.ofSeconds(12)).stopped();
                assertThat(stopped).isNotNull();
                var row = runs.get(PROJECT, run.id());
                assertThat(stopped.matches(run.id(), row.executionId(), cwd)).isTrue();
                // Test-only cleanup: this exact owned HTTP fixture never received a DB credential or ran a migration.
                // Retain its audit row and real receipt. Production recovery never has this data/ownership knowledge.
                new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    jdbc.update("UPDATE execution_runs SET status='NEEDS_RECONCILIATION',execution_pending=false,stopped_sha256=? WHERE id=?", stopped.digest(), run.id());
                    jdbc.update("UPDATE execution_runs SET status='FAILED',error_code='RUN_FIXTURE_STOPPED',finished_at=clock_timestamp() WHERE id=?", run.id());
                });
            } else {
                var row = runs.get(PROJECT, run.id());
                if (row.finishedAt() == null) new TransactionTemplate(transactions).execute(tx -> runs.notStarted(row, "RUN_CANCELLED"));
            }
            if (coordinator != null) coordinator.close();
        }
        assertThat(runs.unfinished()).isEmpty();
    }

    private void verifyUnownedIntentStopsDispatch(ExecutionCoordinator coordinator, ExecutionRunRepository runs,
                                                 ExecutionRun old, Path root, AtomicInteger closed) throws Exception {
        var unowned = new TransactionTemplate(transactions).execute(tx -> runs.admit(OWNER, PROJECT,
                ExecutionRun.request(old.buildId(), old.bindingId(), null, "unowned-" + UUID.randomUUID())));
        try {
            await(() -> !coordinator.isRunning());
            assertThatThrownBy(coordinator::close).isInstanceOf(dev.atoms.demo.common.ApiException.class);
            assertThat(closed).hasValue(0);
            assertThat(runs.get(PROJECT, unowned.id())).isEqualTo(unowned);
            assertThatThrownBy(() -> { try (var other = new ExecutionPaths(root, List.of())) { other.checkedRoot(); } })
                    .isInstanceOf(java.io.IOException.class);
            assertThatThrownBy(() -> { try (var other = new ExecutionCoordinatorLease(database)) { other.healthy(); } })
                    .isInstanceOf(java.sql.SQLException.class);
        } finally {
            // This fixture owns the sole admission and never issued an execution intent.
            new TransactionTemplate(transactions).execute(tx -> runs.notStarted(unowned, "RUN_CANCELLED"));
        }
    }

    private Path crashInput(Path root, ExecutionRun run, ExecutionRunCrashProbe.Window window) throws Exception {
        var input = new Properties(); input.setProperty("url", database.getJdbcUrl()); input.setProperty("user", database.getUsername());
        input.setProperty("password", database.getPassword()); input.setProperty("root", root.toString());
        input.setProperty("project", PROJECT.toString()); input.setProperty("run", run.id().toString()); input.setProperty("window", window.name());
        Path file = Files.createFile(temporary.resolve("crash.properties"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var out = Files.newOutputStream(file)) { input.store(out, "isolated crash fixture input"); }
        return file;
    }
    private ExecutionHealth.Confirmed health(ExecutionRunProcess process, UUID run, UUID execution, ExecutionBindingRepository.Target target) throws Exception {
        var budget = budget();
        while (true) {
            var proof = new ExecutionHealth().probe(process, run, execution, target, budget);
            if (proof != null) return proof;
            Thread.sleep(20);
        }
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (!condition.getAsBoolean()) { if (System.nanoTime() >= deadline) throw new AssertionError("Crash handshake timed out"); Thread.sleep(25); }
    }
    private Path required(String key) throws Exception {
        String raw = System.getProperty(key); assertThat(raw).as(key).isNotBlank();
        Path path = Path.of(raw); assertThat(path.isAbsolute()).isTrue(); ExecutionRootLease.noLinks(path); return path.toRealPath();
    }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(20)); }
}
