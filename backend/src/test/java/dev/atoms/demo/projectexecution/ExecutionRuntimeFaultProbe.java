package dev.atoms.demo.projectexecution;

import com.zaxxer.hikari.HikariDataSource;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;

/** Real PG and owned HTTP process, isolated so an unknown completion can retain resources until JVM exit. */
public final class ExecutionRuntimeFaultProbe {
    enum Fault { METADATA_UNAVAILABLE, COMMIT_RECEIPT_LOST }
    private static final UUID OWNER = ExecutionBindingContractIT.OWNER, PROJECT = ExecutionBindingContractIT.A;
    private ExecutionRuntimeFixture fixture;
    private String stage = "input";

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]), evidence = file.getParent();
        var probe = new ExecutionRuntimeFaultProbe();
        try {
            var input = new Properties(); try (var in = Files.newInputStream(file)) { input.load(in); }
            probe.run(input, evidence);
            System.exit(0); // Deliberately retain unknown worker capabilities and the coordination lease until process exit.
        } catch (Throwable error) {
            StringBuilder detail = new StringBuilder(probe.stage).append('\n').append(error.getClass().getName()).append('\n');
            for (var frame : error.getStackTrace()) if (frame.getClassName().startsWith("dev.atoms.demo.projectexecution."))
                detail.append(frame.getClassName()).append(':').append(frame.getLineNumber()).append('\n');
            Files.writeString(evidence.resolve("failure.txt"), detail, StandardOpenOption.CREATE_NEW);
            // Only this JVM's original capability may stop its fixture; never infer ownership from a PID or port.
            if (probe.fixture != null && probe.fixture.process.get() != null) {
                probe.fixture.process.get().requestStop();
                try { probe.fixture.process.get().await(Duration.ofSeconds(12)); } catch (Exception ignored) { }
            }
            System.exit(71);
        }
    }

    private void run(Properties input, Path evidence) throws Exception {
        Fault fault = Fault.valueOf(input.getProperty("fault"));
        Path sourceRoot = Path.of(input.getProperty("source")), configuration = Path.of(input.getProperty("configuration"));
        Path root = evidence.resolve("execution");
        // These pools must not be auto-closed before the parent verifies retention and this JVM exits.
        var database = pool(input, "fault-coordinator"); var jdbc = jdbc(database);
        var transactions = new DataSourceTransactionManager(database);
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
        var runs = new ExecutionRunRepository(jdbc); assertThat(runs.unfinished()).isEmpty();
        var projects = new ProjectService(jdbc);
        stage = "source";
        var source = new SourceAgentFixture(jdbc, projects, transactions, sourceRoot); source.start(database);
        var tree = new ProjectSourcePolicy().validate(List.of(new SourceTree.File(ExecutionMigrations.SOURCE + "V001__execution_contract_fixture.sql",
                ExecutionRunAdmissionIT.SQL.getBytes(StandardCharsets.UTF_8), 0100644)));
        var base = source.initializeExisting(OWNER, PROJECT, tree);
        var registry = ExecutionDatabaseBinding.load(Map.of(PROJECT, configuration.resolve(ExecutionBindingContractIT.A_DB + ".properties")),
                List.of(sourceRoot, evidence.getParent()), Set.of("atoms_demo", "atoms_demo_test"), Set.of("postgres", "atoms_test"));
        var target = registry.verify(PROJECT, budget());
        var tools = ExecutionToolchain.capture(ExecutionToolchainTest.installation(evidence.resolve("tools")), List.of(sourceRoot), budget());
        var environment = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", OWNER.toString())
                .withProperty("atoms.project-execution.max-running-projects", "1")
                .withProperty("atoms.project-execution.root", root.toString());
        for (String name : List.of("node", "npm", "java", "maven", "policy"))
            environment.withProperty("atoms.project-execution." + name, evidence.resolve(name).toString());
        environment.withProperty("atoms.project-execution.java", tools.javaHome().resolve("bin/java").toString());
        var settings = new ExecutionSettings(environment);
        var runtimeDatabase = pool(input, "fault-runtime"); var runtimeJdbc = jdbc(runtimeDatabase);
        var armed = new AtomicBoolean(); var lostAcknowledgements = new AtomicInteger(); var runId = new AtomicReference<UUID>();
        var runtimeTransactions = new DataSourceTransactionManager(runtimeDatabase) {
            @Override protected void doCommit(DefaultTransactionStatus status) {
                boolean lose = armed.get() && Boolean.TRUE.equals(runtimeJdbc.queryForObject(
                        "SELECT finished_at IS NOT NULL FROM execution_runs WHERE id=?", Boolean.class, runId.get()))
                        && armed.compareAndSet(true, false);
                super.doCommit(status); // Actual PostgreSQL commit precedes the controlled loss of its acknowledgement.
                if (lose) { lostAcknowledgements.incrementAndGet(); throw new TransactionSystemException("private-probe-commit-receipt"); }
            }
        };
        stage = "admission";
        fixture = new ExecutionRuntimeFixture(settings, new ProjectService(runtimeJdbc), runtimeJdbc, runtimeTransactions,
                source.readGateway(), tools, registry, target);
        var build = fixture.seal(base, tree);
        var coordinator = new ExecutionCoordinator(settings, new ExecutionBuildRepository(jdbc), jdbc, transactions, database,
                source.readGateway()::readyForExecution, () -> fixture);
        coordinator.start(); assertThat(coordinator.isRunning()).isTrue();
        String key = "fault-" + UUID.randomUUID();
        var admitted = coordinator.startApplication(OWNER, PROJECT, build.id(), target.id(), null, key).toCompletableFuture().get(30, TimeUnit.SECONDS);
        runId.set(admitted.id()); stage = "readiness";
        await(() -> runs.get(PROJECT, admitted.id()).status().equals("READY"));
        assertThat(fixture.launches).hasValue(1); assertThat(fixture.process.get().isServing()).isTrue();
        stage = "fault";
        if (fault == Fault.METADATA_UNAVAILABLE) runtimeDatabase.close();
        else { armed.set(true); coordinator.stopApplication(OWNER, PROJECT, admitted.id()); }
        await(() -> !coordinator.isRunning() && !fixture.process.get().isAlive());
        var outcome = fixture.process.get().await(Duration.ofSeconds(12));
        var retained = runs.get(PROJECT, admitted.id());
        assertThat(outcome.stopped()).isNotNull();
        assertThat(outcome.stopped().matches(retained.id(), retained.executionId(), root.resolve("runs/" + retained.id()))).isTrue();
        assertThat(retained.readyAt()).isNotNull();
        if (fault == Fault.METADATA_UNAVAILABLE) {
            assertThat(retained.status()).isEqualTo("READY"); assertThat(retained.finishedAt()).isNull();
            assertThat(retained.executionPending()).isTrue(); assertThat(retained.stoppedSha256()).isNull();
            assertThat(lostAcknowledgements).hasValue(0);
        } else {
            assertThat(retained.status()).isEqualTo("STOPPED"); assertThat(retained.finishedAt()).isNotNull();
            assertThat(retained.executionPending()).isFalse(); assertThat(retained.stoppedSha256()).isEqualTo(outcome.stopped().digest());
            assertThat(lostAcknowledgements).hasValue(1); assertThat(armed).isFalse();
        }
        stage = "retention";
        assertThatThrownBy(coordinator::close).isInstanceOf(ApiException.class);
        assertThat(fixture.runtime.hasHeldProcesses()).isTrue(); assertThat(fixture.builds.closed).isFalse();
        assertThatThrownBy(() -> coordinator.startApplication(OWNER, PROJECT, build.id(), target.id(), null, key)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> coordinator.startApplication(OWNER, PROJECT, build.id(), target.id(), null, key + "-new")).isInstanceOf(ApiException.class);
        assertThat(fixture.launches).hasValue(1); assertThat(runs.get(PROJECT, admitted.id())).isEqualTo(retained);
        record(evidence, "retained", Map.of("run", retained.id().toString(), "status", retained.status(),
                "pending", Boolean.toString(retained.executionPending()), "held", "true", "launches", "1", "processAlive", "false"));
        stage = "parent";
        await(() -> Files.exists(evidence.resolve("release")));
        stage = "fixture-settlement";
        // The original test owner settles only its own credential-free HTTP fixture using the still-held authentic receipt.
        // No worker retry or production cleanup API is added, and the worker/dispatcher retain their unknown capabilities.
        if (fault == Fault.METADATA_UNAVAILABLE) new TransactionTemplate(transactions).execute(tx ->
                runs.finish(retained, retained.executionId(), root.resolve("runs/" + retained.id()), outcome, "RUN_COORDINATOR_UNAVAILABLE"));
        var ended = runs.get(PROJECT, retained.id());
        assertThat(ended.finishedAt()).isNotNull(); assertThat(ended.executionPending()).isFalse();
        assertThat(ended.stoppedSha256()).isEqualTo(outcome.stopped().digest());
        if (fault == Fault.COMMIT_RECEIPT_LOST) assertThat(ended).isEqualTo(retained);
        assertThatThrownBy(coordinator::close).isInstanceOf(ApiException.class);
        assertThat(fixture.runtime.hasHeldProcesses()).isTrue(); assertThat(fixture.builds.closed).isFalse();
        assertThat(fixture.launches).hasValue(1); assertThat(fixture.process.get().isAlive()).isFalse();
        source.close();
        record(evidence, "verified", Map.of("run", ended.id().toString(), "stopDigest", outcome.stopped().digest(), "heldUntilExit", "true"));
    }

    private static HikariDataSource pool(Properties input, String name) {
        var pool = new HikariDataSource(); pool.setJdbcUrl(input.getProperty("url")); pool.setUsername(input.getProperty("user"));
        pool.setPassword(input.getProperty("password")); pool.setMaximumPoolSize(4); pool.setMinimumIdle(0);
        pool.setConnectionTimeout(3000); pool.setPoolName(name); return pool;
    }
    private static JdbcTemplate jdbc(HikariDataSource pool) { var jdbc = new JdbcTemplate(pool); jdbc.setQueryTimeout(3); return jdbc; }
    private static ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    private static void record(Path evidence, String name, Map<String, String> values) throws Exception {
        var properties = new Properties(); properties.putAll(values);
        Path temporary = Files.createFile(evidence.resolve(name + ".tmp"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var out = Files.newOutputStream(temporary)) { properties.store(out, "runtime fault evidence"); }
        Files.move(temporary, evidence.resolve(name + ".properties"), StandardCopyOption.ATOMIC_MOVE);
    }
}
