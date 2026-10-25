package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class ExecutionRunCommandTest {
    @TempDir Path temporary;
    private static final String HASH = "a".repeat(64);
    private OwnedProcess.Budget budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
    private record Fixture(ExecutionBuild build, ExecutionArtifact artifact, ExecutionToolchain tools,
                           ExecutionBindingRepository.Target binding) {}

    @Test void launchesOnlySealedClasspathWithPrivateConfigurationAndExplicitEnvironment() throws Exception {
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of())) {
            var fixture = fixture(paths); UUID run = UUID.randomUUID(), execution = UUID.randomUUID();
            var command = prepare(paths, fixture, run, execution, budget());
            var builder = command.builder(); Path cwd = builder.directory().toPath();
            Path config = cwd.resolve("config/application.properties");
            assertThat(builder.command()).containsExactly(fixture.tools().javaHome().resolve("bin/java").toString(),
                    "-Duser.home=" + cwd.resolve("home"), "-Djava.io.tmpdir=" + cwd.resolve("tmp"),
                    "-cp", fixture.artifact().jar().toString(), "org.springframework.boot.loader.launch.JarLauncher",
                    "--spring.config.location=" + config.toUri().toASCIIString());
            assertThat(builder.environment().keySet()).containsExactlyInAnyOrder("PATH", "HOME", "JAVA_HOME", "TMPDIR", "TMP", "TEMP",
                    "LANG", "LC_ALL", "TZ", "JDK_JAVA_OPTIONS");
            assertThat(builder.environment().get("JDK_JAVA_OPTIONS")).isEqualTo(
                    "\"-Duser.home=" + cwd.resolve("home") + "\" \"-Djava.io.tmpdir=" + cwd.resolve("tmp") + "\"");
            assertThat(Files.readString(config)).contains("server.address=127.0.0.1\n", "server.port=18181\n",
                    "atoms.runtime.run-id=" + run, "atoms.runtime.execution-id=" + execution,
                    "spring.datasource.password=${APP_DB_PASSWORD}", "spring.flyway.clean-disabled=true",
                    "spring.flyway.baseline-on-migrate=false", "spring.flyway.out-of-order=false",
                    "spring.flyway.placeholder-replacement=false", "spring.sql.init.mode=never");
            assertThat(Files.getPosixFilePermissions(config)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            for (String dir : List.of("", "home", "tmp", "config"))
                assertThat(Files.getPosixFilePermissions(cwd.resolve(dir))).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            builder.command().clear(); builder.environment().put("PRIVATE", "canary");
            assertThat(command.builder().command()).hasSize(7);
            assertThat(command.builder().environment()).doesNotContainKey("PRIVATE");
            command.verify(budget());
            byte[] original = Files.readAllBytes(config);
            assertThatThrownBy(() -> prepare(paths, fixture, run, execution, budget())).hasMessage("RUN_WORKSPACE_NOT_CLEAN");
            assertThat(Files.readAllBytes(config)).isEqualTo(original);
        }
    }

    @Test void refusesTamperedConfigurationDirectoriesJarAndJavaBeforeLaunch() throws Exception {
        for (String mode : List.of("contents", "same-bytes", "permissions", "extra", "directory", "jar", "java")) {
            try (var paths = new ExecutionPaths(temporary.toRealPath().resolve(mode), List.of())) {
                var fixture = fixture(paths); var command = prepare(paths, fixture, UUID.randomUUID(), UUID.randomUUID(), budget());
                Path cwd = command.builder().directory().toPath(), config = cwd.resolve("config/application.properties");
                switch (mode) {
                    case "contents" -> Files.writeString(config, "server.port=19999\n");
                    case "same-bytes" -> {
                        Path old = Files.move(config, temporary.resolve(UUID.randomUUID().toString()));
                        Files.copy(old, config, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                    case "permissions" -> Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-r--r--"));
                    case "extra" -> Files.writeString(cwd.resolve("config/extra.properties"), "x=y");
                    case "directory" -> {
                        Files.move(cwd.resolve("home"), temporary.resolve(UUID.randomUUID().toString()));
                        ExecutionRootLease.directory(cwd.resolve("home"));
                    }
                    case "jar" -> Files.writeString(fixture.artifact().jar(), "changed");
                    case "java" -> Files.writeString(fixture.tools().javaHome().resolve("lib/modules"), "changed");
                }
                assertThatThrownBy(() -> command.verify(budget())).as(mode).isInstanceOf(IOException.class).hasNoCause();
            }
        }
    }

    @Test void rejectsWrongBuildBindingDeadlineAndTransactionWithoutAllocatingRunDirectory() throws Exception {
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("rejected"), List.of())) {
            var fixture = fixture(paths); UUID run = UUID.randomUUID(), execution = UUID.randomUUID();
            var b = fixture.build();
            var failed = new ExecutionBuild(b.id(), b.projectId(), b.ownerId(), b.sourceVersionId(), b.treeSha256(),
                    b.keySha256(), b.requestSha256(), "FAILED", false, "FAILED", b.createdAt(), b.finishedAt(), b.lastEventSeq());
            assertThatThrownBy(() -> ExecutionRunCommand.prepare(paths, run, execution, fixture.tools(), fixture.artifact(), failed,
                    fixture.binding(), budget())).hasMessage("RUN_COMMAND_INVALID");
            var wrong = new ExecutionBindingRepository.Target(UUID.randomUUID(), UUID.randomUUID(), 5432, "other", "other", 18182, 31, 41);
            assertThatThrownBy(() -> ExecutionRunCommand.prepare(paths, run, execution, fixture.tools(), fixture.artifact(), b,
                    wrong, budget())).hasMessage("RUN_COMMAND_INVALID");
            assertThatThrownBy(() -> prepare(paths, fixture, run, execution, () -> Duration.ZERO)).hasMessage("EXECUTION_TIMEOUT");
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThatThrownBy(() -> prepare(paths, fixture, run, execution, budget())).hasMessage("EXECUTION_TRANSACTION_ACTIVE"); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            try (var entries = Files.list(paths.checkedRoot().resolve("runs"))) { assertThat(entries).isEmpty(); }
        }
    }

    private ExecutionRunCommand prepare(ExecutionPaths paths, Fixture fixture, UUID run, UUID execution, OwnedProcess.Budget budget) throws Exception {
        return ExecutionRunCommand.prepare(paths, run, execution, fixture.tools(), fixture.artifact(), fixture.build(), fixture.binding(), budget);
    }
    private Fixture fixture(ExecutionPaths paths) throws Exception {
        var locations = ExecutionToolchainTest.installation(temporary.toRealPath().resolve("tools-" + UUID.randomUUID()));
        var tools = ExecutionToolchain.capture(locations, List.of(paths.checkedRoot()), budget());
        var build = new ExecutionBuild(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                HASH, HASH, HASH, "SUCCEEDED", false, null, Instant.now(), Instant.now(), 7);
        var task = paths.createBuild(build.id());
        var backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
        Files.write(Files.createDirectory(backend.resolve("target")).resolve("app.jar"), new byte[]{80, 75, 3, 4, 1});
        var identities = new EnumMap<ExecutionToolchain.Kind, ExecutionToolFiles.Summary>(ExecutionToolchain.Kind.class);
        for (var kind : ExecutionToolchain.Kind.values()) identities.put(kind, tools.identity(kind));
        var evidence = new ExecutionArtifact.Evidence(identities, new ExecutionNpmPolicy.Verified(HASH, HASH, HASH, 1),
                new ExecutionMavenPolicy.Verified(HASH, HASH), new ExecutionDiagnostics.Frontend(1, 1, 1),
                new ExecutionJavaReport.Summary(1, 1), new ExecutionPackaging.Summary(1, 1, HASH));
        var artifact = ExecutionArtifact.seal(paths, task, build, evidence, budget(), () -> {});
        return new Fixture(build, artifact, tools,
                new ExecutionBindingRepository.Target(build.projectId(), UUID.randomUUID(), 5432, "sample", "sample", 18181, 30, 40));
    }
}
