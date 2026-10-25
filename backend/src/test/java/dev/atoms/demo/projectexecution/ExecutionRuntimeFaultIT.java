package dev.atoms.demo.projectexecution;

import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** Isolated coordinator JVM retains unknown capabilities until exit; only its original owner can settle its HTTP fixture. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionRuntimeFaultIT {
    @Autowired HikariDataSource database;
    @Autowired JdbcTemplate jdbc;
    static final UUID PROJECT = ExecutionBindingContractIT.A;

    @ParameterizedTest @EnumSource(ExecutionRuntimeFaultProbe.Fault.class)
    void unavailableMetadataAndLostCompletionReceiptsRetainCapabilitiesWithoutReplaying(ExecutionRuntimeFaultProbe.Fault fault) throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
        Path parent = required("execution.contract-directory"), source = required("execution.runtime-fixture-root"), configuration = required("execution.test-config-directory");
        var runs = new ExecutionRunRepository(jdbc); assertThat(runs.unfinished()).isEmpty();
        var registry = ExecutionDatabaseBinding.load(Map.of(PROJECT, configuration.resolve(ExecutionBindingContractIT.A_DB + ".properties")),
                List.of(parent, source), Set.of("atoms_demo", "atoms_demo_test"), Set.of("postgres", "atoms_test"));
        var binding = registry.verify(PROJECT, budget()); var history = registry.history(PROJECT, budget());
        Path evidence = Files.createTempDirectory(parent, "runtime-fault-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path root = evidence.resolve("execution"), driver = ExecutionRootLease.directory(evidence.resolve("driver"));
        var input = new Properties(); input.setProperty("url", database.getJdbcUrl()); input.setProperty("user", database.getUsername());
        input.setProperty("password", database.getPassword()); input.setProperty("source", source.toString());
        input.setProperty("configuration", configuration.toString()); input.setProperty("fault", fault.name());
        Path file = Files.createFile(evidence.resolve("input.properties"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var out = Files.newOutputStream(file)) { input.store(out, "private runtime fault probe input"); }
        Path java = Path.of(System.getProperty("java.home"));
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).split(File.pathSeparator))
                .map(p -> Path.of(p).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        var invocation = new ExecutionProcess.Invocation(java.resolve("bin/java"), List.of("-cp", classpath,
                ExecutionRuntimeFaultProbe.class.getName(), file.toString()), driver, driver, driver, java, java.resolve("bin")).withPrivateJvmHome();
        UUID task = UUID.randomUUID(), execution = UUID.randomUUID();
        var threads = Executors.newSingleThreadExecutor();
        var child = threads.submit(() -> new ExecutionProcess().run(invocation, new ExecutionProcess.Deadline(Duration.ofSeconds(180)),
                () -> false, task, execution, () -> {}));
        try {
            await(() -> Files.exists(evidence.resolve("retained.properties")) || child.isDone());
            assertThat(evidence.resolve("failure.txt")).doesNotExist(); assertThat(child.isDone()).isFalse();
            var retained = read(evidence.resolve("retained.properties")); UUID id = UUID.fromString(retained.getProperty("run"));
            var row = runs.get(PROJECT, id);
            assertThat(row.status()).isEqualTo(retained.getProperty("status"));
            assertThat(row.executionPending()).isEqualTo(Boolean.parseBoolean(retained.getProperty("pending")));
            assertThat(row.finishedAt() != null).isEqualTo(fault == ExecutionRuntimeFaultProbe.Fault.COMMIT_RECEIPT_LOST);
            assertThat(retained.getProperty("held")).isEqualTo("true"); assertThat(retained.getProperty("launches")).isEqualTo("1");
            assertThat(retained.getProperty("processAlive")).isEqualTo("false");
            assertThatThrownBy(() -> { try (var other = new ExecutionPaths(root, List.of(source))) { other.checkedRoot(); } }).isInstanceOf(java.io.IOException.class);
            assertThatThrownBy(() -> { try (var other = new ExecutionCoordinatorLease(database)) { other.healthy(); } }).isInstanceOf(java.sql.SQLException.class);
            assertThat(runs.get(PROJECT, id)).isEqualTo(row);
            Files.writeString(evidence.resolve("release"), "verified", StandardOpenOption.CREATE_NEW);
            var result = child.get(60, TimeUnit.SECONDS);
            assertThat(result.stopped()).isNotNull(); assertThat(result.stopped().matches(task, execution, driver)).isTrue();
            assertThat(evidence.resolve("failure.txt")).doesNotExist();
            var verified = read(evidence.resolve("verified.properties")); var ended = runs.get(PROJECT, id);
            assertThat(ended.finishedAt()).isNotNull(); assertThat(ended.executionPending()).isFalse();
            assertThat(ended.stoppedSha256()).isEqualTo(verified.getProperty("stopDigest"));
            assertThat(ended.id().toString()).isEqualTo(verified.getProperty("run"));
            assertThat(verified.getProperty("heldUntilExit")).isEqualTo("true");
            if (fault == ExecutionRuntimeFaultProbe.Fault.COMMIT_RECEIPT_LOST) assertThat(ended).isEqualTo(row);
            try (var reopened = new ExecutionPaths(root, List.of(source)); var lease = new ExecutionCoordinatorLease(database)) {
                reopened.checkedRoot(); assertThat(lease.healthy()).isTrue();
            }
        } finally {
            // Let this probe's original owner finish its controlled fixture. Never synthesize a receipt in the parent.
            try {
                if (!child.isDone() && Files.exists(evidence.resolve("retained.properties"))) {
                    if (!Files.exists(evidence.resolve("release"))) Files.writeString(evidence.resolve("release"), "finish", StandardOpenOption.CREATE_NEW);
                    child.get(60, TimeUnit.SECONDS);
                }
            } finally {
                if (!child.isDone()) child.cancel(true);
                threads.shutdownNow(); assertThat(threads.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(runs.unfinished()).isEmpty(); assertThat(registry.history(PROJECT, budget())).isEqualTo(history);
        assertThat(new ExecutionBindingRepository(jdbc).get(PROJECT).target()).isEqualTo(binding);
    }

    private Properties read(Path file) throws Exception {
        var properties = new Properties(); try (var in = Files.newInputStream(file)) { properties.load(in); } return properties;
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(100).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    private Path required(String key) throws Exception {
        String value = System.getProperty(key); assertThat(value).as(key).isNotBlank();
        Path path = Path.of(value); assertThat(path.isAbsolute()).isTrue(); ExecutionRootLease.noLinks(path); return path.toRealPath();
    }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
}
