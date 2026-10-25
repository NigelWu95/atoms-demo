package dev.atoms.demo.projectexecution;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in, read-only access to the already approved business DBs; never starts the command or runs SQL migrations. */
@Tag("project-execution-contract")
class ExecutionRuntimeLaunchContractIT {
    @TempDir Path temporary;
    static final UUID A = ExecutionBindingContractIT.A, B = ExecutionBindingContractIT.B;
    static final String HASH = "a".repeat(64);
    Path configuration;
    @BeforeEach void configured() {
        String value = System.getProperty("execution.test-config-directory");
        assertThat(value).as("Explicit approved private directory is required").isNotBlank(); configuration = Path.of(value);
    }

    @Test void businessCredentialsAreBoundToOneFixedCommandAndNeverWrittenToArgumentsOrConfiguration() throws Exception {
        var registry = registry(file(ExecutionBindingContractIT.A_DB)); var target = registry.verify(A, budget());
        long before = registry.history(A, budget()).state().objects();
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of())) {
            UUID run = UUID.randomUUID(), execution = UUID.randomUUID();
            var command = command(paths, target, run, execution);
            var grant = registry.authorizeLaunch(A, run, execution, command, budget());
            var builder = grant.builder(budget());
            assertThat(builder.environment().keySet()).contains("APP_DB_URL", "APP_DB_USERNAME", "APP_DB_PASSWORD", "APP_PORT");
            // Assertions only expose booleans, never real credentials in failure diagnostics.
            assertThat(builder.environment().get("APP_DB_URL").startsWith("jdbc:postgresql://127.0.0.1:5432/" + target.database() + "?")).isTrue();
            assertThat(builder.environment().get("APP_DB_USERNAME").equals(target.role())).isTrue();
            assertThat(builder.environment().get("APP_PORT").equals(Integer.toString(target.port()))).isTrue();
            String secret = builder.environment().get("APP_DB_PASSWORD"); assertThat(secret != null && secret.length() >= 8).isTrue();
            assertThat(builder.command().stream().noneMatch(arg -> arg.contains(secret))).isTrue();
            assertThat(!Files.readString(builder.directory().toPath().resolve("config/application.properties")).contains(secret)).isTrue();
            grant.verify(budget());
            assertThatThrownBy(() -> grant.builder(budget())).hasMessage("RUN_DB_LAUNCH_REJECTED").hasNoCause();
        }
        assertThat(registry.history(A, budget()).state().objects()).isEqualTo(before);
    }

    @Test void wrongProjectExecutionAndTestPurposeCannotReceiveBusinessCredentials() throws Exception {
        var registry = registry(file(ExecutionBindingContractIT.A_DB)); var target = registry.verify(A, budget());
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("wrong"), List.of())) {
            UUID run = UUID.randomUUID(), execution = UUID.randomUUID(); var command = command(paths, target, run, execution);
            assertThatThrownBy(() -> registry.authorizeLaunch(B, run, execution, command, budget())).hasMessage("RUN_DB_LAUNCH_REJECTED");
            assertThatThrownBy(() -> registry.authorizeLaunch(A, UUID.randomUUID(), execution, command, budget())).hasMessage("RUN_DB_LAUNCH_REJECTED");
            assertThatThrownBy(() -> registry.authorizeLaunch(A, run, UUID.randomUUID(), command, budget())).hasMessage("RUN_DB_LAUNCH_REJECTED");
            assertThatThrownBy(() -> registry.authorizeLaunch(A, run, execution, command, () -> Duration.ZERO)).hasMessage("EXECUTION_TIMEOUT");
            var test = ExecutionPrivateConfig.read(file(ExecutionBindingContractIT.A_TEST), List.of());
            var builder = command.builder();
            assertThatThrownBy(() -> test.applyRuntimeEnvironment(builder)).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
            assertThat(builder.environment().keySet()).doesNotContain("APP_DB_PASSWORD");
        }
    }

    @Test void changedPrivateConfigurationRevokesAnUnconsumedGrant() throws Exception {
        Path directory = ExecutionRootLease.directory(temporary.toRealPath().resolve("private"));
        Path copy = directory.resolve("runtime.properties"); Files.copy(file(ExecutionBindingContractIT.A_DB), copy);
        Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rw-------"));
        var registry = registry(copy); var target = registry.verify(A, budget());
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("changed"), List.of())) {
            UUID run = UUID.randomUUID(), execution = UUID.randomUUID(); var command = command(paths, target, run, execution);
            var grant = registry.authorizeLaunch(A, run, execution, command, budget());
            Files.writeString(copy, "changed-private-configuration");
            assertThatThrownBy(() -> grant.builder(budget())).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID").hasNoCause();
            assertThatThrownBy(() -> grant.builder(budget())).hasMessage("RUN_DB_LAUNCH_REJECTED");
        }
    }

    private ExecutionDatabaseBinding registry(Path a) throws Exception {
        return ExecutionDatabaseBinding.load(Map.of(A, a, B, file(ExecutionBindingContractIT.B_DB)), List.of(),
                Set.of("atoms_demo", "atoms_demo_test", ExecutionBindingContractIT.A_TEST, "atoms_exec_b_test_20261020"),
                Set.of("postgres", "atoms_test", ExecutionBindingContractIT.A_TEST, "atoms_exec_b_test_20261020"));
    }
    private Path file(String name) { return configuration.resolve(name + ".properties"); }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }

    private ExecutionRunCommand command(ExecutionPaths paths, ExecutionBindingRepository.Target target, UUID run, UUID execution) throws Exception {
        var tools = ExecutionToolchain.capture(ExecutionToolchainTest.installation(temporary.toRealPath().resolve("tools-" + run)), List.of(), budget());
        var build = new ExecutionBuild(UUID.randomUUID(), target.project(), ExecutionBindingContractIT.OWNER, UUID.randomUUID(),
                HASH, HASH, HASH, "SUCCEEDED", false, null, Instant.now(), Instant.now(), 7);
        var task = paths.createBuild(build.id()); Path backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
        Files.write(Files.createDirectory(backend.resolve("target")).resolve("app.jar"), new byte[]{80, 75, 3, 4, 1});
        var identities = new EnumMap<ExecutionToolchain.Kind, ExecutionToolFiles.Summary>(ExecutionToolchain.Kind.class);
        for (var kind : ExecutionToolchain.Kind.values()) identities.put(kind, tools.identity(kind));
        var evidence = new ExecutionArtifact.Evidence(identities, new ExecutionNpmPolicy.Verified(HASH, HASH, HASH, 1),
                new ExecutionMavenPolicy.Verified(HASH, HASH), new ExecutionDiagnostics.Frontend(1, 1, 1),
                new ExecutionJavaReport.Summary(1, 1), new ExecutionPackaging.Summary(1, 1, HASH));
        var artifact = ExecutionArtifact.seal(paths, task, build, evidence, budget(), () -> {});
        return ExecutionRunCommand.prepare(paths, run, execution, tools, artifact, build, target, budget());
    }
}
