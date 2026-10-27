package dev.atoms.demo.projectexecution;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import dev.atoms.demo.execution.OwnedProcess;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Opt-in read-only database checks and controlled JVMs, not a generated Maven build. */
@Tag("project-execution-contract")
class ExecutionDatabaseLaunchIT {
    private static final UUID A = UUID.fromString("f7a7917c-7b41-4fd6-b224-25aeeaa9e579");
    private static final UUID B = UUID.fromString("cdcd56cb-98a1-4ef4-aac9-180589576a91");
    private static final String A_DB = "atoms_exec_a_test_20261020", B_DB = "atoms_exec_b_test_20261020";
    @TempDir Path temporary;
    private Path configuration, backend;

    @BeforeEach void explicitPrivateConfiguration() throws Exception {
        String path = System.getProperty("execution.test-config-directory");
        assertThat(path != null && !path.isBlank()).as("Explicit private test configuration is required").isTrue();
        configuration = Path.of(path);
        backend = Files.createDirectory(temporary.toRealPath().resolve("backend"));
    }

    @Test void onlyTheBoundTestDatabaseReachesRealParentAndChildWithoutChangingInvocation() throws Exception {
        var registry = registry(Map.of(A, file(A_DB), B, file(B_DB)));
        var first = registry.verify(A, deadline()); var second = registry.verify(B, deadline());
        for (var target : Map.of(A, A_DB, B, B_DB).entrySet()) {
            UUID build = UUID.randomUUID(), execution = UUID.randomUUID();
            var budget = deadline();
            var launch = registry.authorizeLaunch(target.getKey(), build, execution, backend, budget);
            var invocation = invocation(backend, target.getValue(), "spawn");
            var environment = Map.copyOf(invocation.builder().environment());
            var command = List.copyOf(invocation.builder().command());
            var result = new ExecutionProcess().run(invocation, budget, () -> false, build, execution, () -> {}, launch);
            assertThat(result.stopped().matches(build, execution, backend)).isTrue();
            assertThat(result.summary().stdoutBytes()).isEqualTo(4); // "ok" from both real JVMs.
            assertThat(invocation.builder().environment()).isEqualTo(environment).doesNotContainKeys(
                    "APP_DB_URL", "APP_DB_USERNAME", "APP_DB_PASSWORD", "APP_PORT", "APP_TEST_CONFIG");
            assertThat(invocation.builder().command()).isEqualTo(command);
            assertThat(launch.toString()).doesNotContain(target.getValue(), configuration.toString());
            assertThat(result.toString()).doesNotContain(target.getValue(), configuration.toString(), "PRIVATE_OUTPUT_CANARY");
        }
        assertThat(registry.verify(A, deadline())).isEqualTo(first);
        assertThat(registry.verify(B, deadline())).isEqualTo(second);
    }

    @Test void grantCannotBeReplayedOrUsedForAnotherBuildExecutionOrDirectory() throws Exception {
        var registry = registry(Map.of(A, file(A_DB)));
        for (String mismatch : List.of("build", "execution", "directory", "replay")) {
            UUID build = UUID.randomUUID(), execution = UUID.randomUUID();
            var budget = deadline();
            var launch = registry.authorizeLaunch(A, build, execution, backend, budget);
            var invocation = invocation(mismatch.equals("directory") ? temporary.toRealPath() : backend, A_DB, "single");
            var process = new ExecutionProcess();
            if (mismatch.equals("replay"))
                process.run(invocation, budget, () -> false, build, execution, () -> {}, launch);
            assertThatThrownBy(() -> process.run(invocation, budget, () -> false,
                    mismatch.equals("build") ? UUID.randomUUID() : build,
                    mismatch.equals("execution") ? UUID.randomUUID() : execution, () -> {}, launch))
                    .hasMessage("EXECUTION_DB_LAUNCH_REJECTED").hasNoCause().hasNoSuppressedExceptions();
        }
    }

    @Test void privateConfigurationChangesAfterGrantOrDuringPreflightPreventLaunch() throws Exception {
        for (String change : List.of("content", "replace", "hardlink", "mode", "parent-mode")) {
            Path copy = copy(A_DB, Map.of(), Set.of());
            var registry = registry(Map.of(A, copy));
            UUID build = UUID.randomUUID(), execution = UUID.randomUUID();
            var budget = deadline();
            var grant = registry.authorizeLaunch(A, build, execution, backend, budget);
            assertThatThrownBy(() -> new ExecutionProcess().run(invocation(backend, A_DB, "single"), budget,
                    () -> false, build, execution, () -> {
                        switch (change) {
                            case "content" -> Files.writeString(copy, "replaced-config-canary");
                            case "replace" -> {
                                Files.move(copy, copy.resolveSibling("original"));
                                Files.copy(copy.resolveSibling("original"), copy);
                                Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rw-------"));
                            }
                            case "hardlink" -> Files.createLink(copy.resolveSibling("alias"), copy);
                            case "mode" -> Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rw-r--r--"));
                            case "parent-mode" -> Files.setPosixFilePermissions(copy.getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
                        }
                    }, grant)).isInstanceOfSatisfying(OwnedProcess.Failure.class, failure -> {
                        assertThat(failure.getMessage()).isEqualTo("EXECUTION_PREFLIGHT_FAILED");
                        assertThat(failure.stopped().matches(build, execution, backend)).isTrue();
                        assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
                    });
            assertThat(backend.resolve("started")).doesNotExist();
            assertThatThrownBy(() -> new ExecutionProcess().run(invocation(backend, A_DB, "single"), budget,
                    () -> false, build, execution, () -> {}, grant)).hasMessage("EXECUTION_DB_LAUNCH_REJECTED");
        }
        Path beforeAttach = copy(A_DB, Map.of(), Set.of());
        UUID build = UUID.randomUUID(), execution = UUID.randomUUID();
        var grant = registry(Map.of(A, beforeAttach)).authorizeLaunch(A, build, execution, backend, deadline());
        Files.writeString(beforeAttach, "changed-before-attach");
        assertThatThrownBy(() -> new ExecutionProcess().run(invocation(backend, A_DB, "single"), deadline(),
                () -> false, build, execution, () -> {}, grant))
                .hasMessage("EXECUTION_DB_CONFIGURATION_INVALID").hasNoCause().hasNoSuppressedExceptions();
        assertThat(backend.resolve("started")).doesNotExist();
    }

    @Test void concurrentUseOfOneGrantOnlyLaunchesOnce() throws Exception {
        UUID build = UUID.randomUUID(), execution = UUID.randomUUID();
        var budget = deadline();
        var grant = registry(Map.of(A, file(A_DB))).authorizeLaunch(A, build, execution, backend, budget);
        var invocation = invocation(backend, A_DB, "single");
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> attempt = () -> {
                start.await();
                try {
                    var result = new ExecutionProcess().run(invocation, budget, () -> false, build, execution, () -> {}, grant);
                    assertThat(result.stopped().matches(build, execution, backend)).isTrue();
                    return "started";
                } catch (java.io.IOException error) { return error.getMessage(); }
            };
            var first = pool.submit(attempt); var second = pool.submit(attempt); start.countDown();
            assertThat(List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS), second.get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("started", "EXECUTION_DB_LAUNCH_REJECTED");
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void absentInvalidOrActualRuntimeBindingCannotIssueCredentials() throws Exception {
        var registry = registry(Map.of(A, file(A_DB)));
        UUID build = UUID.randomUUID(), execution = UUID.randomUUID();
        assertThatThrownBy(() -> registry.authorizeLaunch(B, build, execution, backend, deadline()))
                .hasMessage("EXECUTION_DB_BINDING_NOT_FOUND");
        assertThatThrownBy(() -> registry.authorizeLaunch(A, null, execution, backend, deadline()))
                .hasMessage("EXECUTION_DB_LAUNCH_REJECTED");
        assertThatThrownBy(() -> registry.authorizeLaunch(A, build, null, backend, deadline()))
                .hasMessage("EXECUTION_DB_LAUNCH_REJECTED");
        for (Path bad : List.of(Path.of("backend"), backend.resolve(".."), temporary.toRealPath()))
            assertThatThrownBy(() -> registry.authorizeLaunch(A, build, execution, bad, deadline()))
                    .hasMessage("EXECUTION_DB_LAUNCH_REJECTED");
        for (var fixture : List.of(
                Map.entry(copy(A_DB, Map.of("password", "WRONG_PASSWORD_CANARY"), Set.of()), "EXECUTION_DB_UNAVAILABLE"),
                Map.entry(copy(A_DB, Map.of("bindingId", UUID.randomUUID().toString()), Set.of()), "EXECUTION_DB_TARGET_REJECTED"),
                Map.entry(copy("atoms_exec_a_app_20261020", Map.of("purpose", "test"), Set.of("port")), "EXECUTION_DB_TARGET_REJECTED")))
            assertThatThrownBy(() -> registry(Map.of(A, fixture.getKey())).authorizeLaunch(A, build, execution, backend, deadline()))
                    .hasMessage(fixture.getValue()).hasNoCause().hasNoSuppressedExceptions();
        assertThat(backend.resolve("started")).doesNotExist();
    }

    @Test void cancellationAndExpiredBudgetCannotStartAChildOrReuseTheGrant() throws Exception {
        var registry = registry(Map.of(A, file(A_DB)));
        for (boolean cancel : List.of(true, false)) {
            UUID build = UUID.randomUUID(), execution = UUID.randomUUID();
            var clock = new AtomicLong();
            var budget = new ExecutionProcess.Deadline(Duration.ofSeconds(20), clock::get);
            var grant = registry.authorizeLaunch(A, build, execution, backend, budget);
            if (!cancel) clock.set(Duration.ofSeconds(20).toNanos());
            assertThatThrownBy(() -> new ExecutionProcess().run(invocation(backend, A_DB, "single"), budget,
                    () -> cancel, build, execution, () -> {}, grant))
                    .isInstanceOfSatisfying(OwnedProcess.Failure.class, failure -> {
                        assertThat(failure.getMessage()).isEqualTo(cancel ? "EXECUTION_CANCELLED" : "EXECUTION_TIMEOUT");
                        assertThat(failure.stopped().matches(build, execution, backend)).isTrue();
                    });
            assertThatThrownBy(() -> new ExecutionProcess().run(invocation(backend, A_DB, "single"), deadline(),
                    () -> false, build, execution, () -> {}, grant)).hasMessage("EXECUTION_DB_LAUNCH_REJECTED");
        }
        assertThat(backend.resolve("started")).doesNotExist();
    }

    @Test void ambientTransactionPreventsGrantAndAlsoPreventsAnAlreadyAuthorizedLaunch() throws Exception {
        var registry = registry(Map.of(A, file(A_DB)));
        UUID build = UUID.randomUUID(), execution = UUID.randomUUID();
        var budget = deadline();
        var grant = registry.authorizeLaunch(A, build, execution, backend, budget);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> registry.authorizeLaunch(A, build, execution, backend, budget))
                    .hasMessage("EXECUTION_TRANSACTION_ACTIVE");
            assertThatThrownBy(() -> new ExecutionProcess().run(invocation(backend, A_DB, "single"), budget,
                    () -> false, build, execution, () -> {}, grant))
                    .isInstanceOfSatisfying(OwnedProcess.Failure.class, failure -> {
                        assertThat(failure.getMessage()).isEqualTo("EXECUTION_PREFLIGHT_FAILED");
                        assertThat(failure.stopped().matches(build, execution, backend)).isTrue();
                    });
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertThat(backend.resolve("started")).doesNotExist();
    }

    @Test void productionJavaEntryCannotUseAnUnregisteredProjectOrFallBackToHostCredentials() throws Exception {
        UUID build = UUID.randomUUID();
        var budget = deadline();
        var tools = ExecutionToolchain.capture(ExecutionToolchainTest.installation(temporary.toRealPath()), List.of(), budget);
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of())) {
            var task = paths.createBuild(build);
            ExecutionRootLease.directory(paths.project(task).resolve("backend"));
            var home = ExecutionToolHome.create(paths, task, budget);
            var stages = new ArrayList<ExecutionJava.Stage>(); var starts = new ArrayList<UUID>();
            var journal = new ExecutionJava.Journal() {
                public void begin(UUID execution) { starts.add(execution); }
                public void finish(ExecutionJava.Stage stage) { stages.add(stage); }
            };
            var stage = new ExecutionJava(paths, task, tools, home, () -> {}, registry(Map.of(A, file(A_DB))), B);
            assertThatThrownBy(() -> stage.run(build, budget, () -> false, journal)).hasMessage("EXECUTION_PROCESS_UNSETTLED");
            assertThat(starts).hasSize(1); assertThat(stages).hasSize(1);
            assertThat(stages.get(0).stopped()).isNull(); assertThat(stages.get(0).tests()).isNull();
            assertThat(stages.get(0).execution()).isEqualTo(starts.get(0));
            assertThat(paths.project(task).resolve("backend/target/app.jar")).doesNotExist();
        }
    }

    private Path copy(String name, Map<String, String> changes, Set<String> removed) throws Exception {
        var properties = new Properties();
        Path source = file(name);
        ExecutionRootLease.noLinks(source);
        assertThat(Files.size(source) <= 8192).isTrue();
        assertThat(Files.getPosixFilePermissions(source)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) { properties.load(input); }
        properties.putAll(changes); removed.forEach(properties::remove);
        Path parent = Files.createDirectory(temporary.toRealPath().resolve(UUID.randomUUID().toString()),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path copy = Files.createFile(parent.resolve("fixture.properties"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var writer = Files.newBufferedWriter(copy)) { properties.store(writer, "Private short-lived test fixture"); }
        return copy;
    }

    private ExecutionTestDatabase registry(Map<UUID, Path> files) throws Exception {
        return ExecutionTestDatabase.load(files, List.of(backend), Set.of("atoms_demo", "atoms_demo_test"),
                Set.of("postgres", "atoms_test"));
    }
    private Path file(String name) { return configuration.resolve(name + ".properties"); }
    private ExecutionProcess.Deadline deadline() { return new ExecutionProcess.Deadline(Duration.ofSeconds(20)); }

    private ExecutionProcess.Invocation invocation(Path directory, String database, String mode) throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home"));
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                        .split(java.io.File.pathSeparator))
                .map(path -> Path.of(path).toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        return new ExecutionProcess.Invocation(javaHome.resolve("bin/java"),
                List.of("-cp", classpath, Fixture.class.getName(), database, mode, file(database).toString()),
                directory, temporary.toRealPath(), temporary.toRealPath(), javaHome, temporary.toRealPath());
    }

    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            System.in.readAllBytes();
            Files.writeString(Path.of("started"), "yes");
            if (!"0".equals(System.getenv("APP_PORT")) || !args[0].equals(System.getenv("APP_DB_USERNAME"))) System.exit(11);
            if (System.getenv().keySet().stream().anyMatch(key -> key.contains("PROXY") || key.contains("API_KEY")
                    || key.equals("SPRING_APPLICATION_JSON") || key.startsWith("SPRING_DATASOURCE")
                    || key.equals("SMTP_PASSWORD") || key.equals("JAVA_TOOL_OPTIONS"))) System.exit(12);
            if (!System.getenv().keySet().stream().filter(key -> key.startsWith("APP_")).collect(java.util.stream.Collectors.toSet())
                    .equals(Set.of("APP_DB_URL", "APP_DB_USERNAME", "APP_DB_PASSWORD", "APP_PORT", "APP_TEST_CONFIG"))) System.exit(13);
            if (args.length != 3 || !args[2].equals(System.getenv("APP_TEST_CONFIG"))) System.exit(18);
            Path testConfig = Path.of(args[2]);
            if (!testConfig.isAbsolute() || !Files.isRegularFile(testConfig, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(testConfig).equals(PosixFilePermissions.fromString("rw-------"))) System.exit(19);
            var environment = new org.springframework.core.env.StandardEnvironment();
            String url = environment.resolveRequiredPlaceholders("${APP_DB_URL}");
            if (!url.equals("jdbc:postgresql://127.0.0.1:5432/" + args[0] + "?currentSchema=generated_app")) System.exit(14);
            var properties = new Properties();
            properties.setProperty("user", System.getenv("APP_DB_USERNAME"));
            properties.setProperty("password", environment.resolveRequiredPlaceholders("${APP_DB_PASSWORD}"));
            properties.setProperty("connectTimeout", "3"); properties.setProperty("socketTimeout", "5");
            try (var connection = java.sql.DriverManager.getConnection(url, properties)) {
                connection.setReadOnly(true); connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(3);
                    try (var rows = statement.executeQuery("SELECT current_database(),current_user,current_schema()")) {
                        if (!rows.next() || !args[0].equals(rows.getString(1)) || !args[0].equals(rows.getString(2))
                                || !"generated_app".equals(rows.getString(3))) System.exit(15);
                    }
                } finally { connection.rollback(); }
            } catch (java.sql.SQLException error) { System.exit(16); }
            if (args[1].equals("spawn")) {
                var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), args[0], "single", args[2])
                        .inheritIO().start();
                if (child.waitFor() != 0) System.exit(17);
            }
            System.out.print("ok"); System.err.print("PRIVATE_OUTPUT_CANARY");
        }
    }
}
