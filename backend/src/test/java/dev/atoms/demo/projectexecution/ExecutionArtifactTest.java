package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class ExecutionArtifactTest {
    @TempDir Path temporary;
    private static final String HASH = "a".repeat(64);
    private OwnedProcess.Budget budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
    private ExecutionBuild build() {
        return new ExecutionBuild(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                HASH, HASH, HASH, "PACKAGING", false, null, Instant.now(), null, 7);
    }
    private ExecutionArtifact.Evidence evidence() {
        var tools = new EnumMap<ExecutionToolchain.Kind, ExecutionToolFiles.Summary>(ExecutionToolchain.Kind.class);
        for (var kind : ExecutionToolchain.Kind.values()) tools.put(kind, new ExecutionToolFiles.Summary(HASH, 1, 20));
        return new ExecutionArtifact.Evidence(tools, new ExecutionNpmPolicy.Verified(HASH, HASH, HASH, 50),
                new ExecutionMavenPolicy.Verified(HASH, HASH), new ExecutionDiagnostics.Frontend(1, 2, 3),
                new ExecutionJavaReport.Summary(1, 2), new ExecutionPackaging.Summary(2, 100, HASH));
    }
    private Path input(ExecutionPaths paths, ExecutionPaths.Task task) throws Exception {
        Path backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
        Path target = Files.createDirectory(backend.resolve("target"));
        // Sealing checks byte identity; actual executable JAR acceptance belongs to the build worker/EX11.
        return Files.write(target.resolve("app.jar"), new byte[]{80, 75, 3, 4, 1, 2, 3});
    }

    @Test void sealsExactBytesAndFixedEvidenceWithoutOverwritingTheBuild() throws Exception {
        Path root = temporary.toRealPath().resolve("happy");
        try (var paths = new ExecutionPaths(root, List.of())) {
            var build = build();
            var task = paths.createBuild(build.id());
            Path input = input(paths, task);
            var artifact = ExecutionArtifact.seal(paths, task, build, evidence(), budget(), () -> {});
            Path directory = root.resolve("artifacts").resolve(build.id().toString());
            assertThat(artifact.jar()).isEqualTo(directory.resolve("app.jar"));
            assertThat(Files.readAllBytes(artifact.jar())).isEqualTo(Files.readAllBytes(input));
            assertThat(Files.getPosixFilePermissions(artifact.jar())).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            var json = new ObjectMapper().readTree(Files.readAllBytes(directory.resolve("manifest.json")));
            assertThat(json.path("format").intValue()).isEqualTo(1);
            assertThat(json.path("buildId").textValue()).isEqualTo(build.id().toString());
            assertThat(json.path("projectId").textValue()).isEqualTo(build.projectId().toString());
            assertThat(json.path("sourceVersionId").textValue()).isEqualTo(build.sourceVersionId().toString());
            assertThat(json.path("treeSha256").textValue()).isEqualTo(HASH);
            assertThat(json.path("evidence").path("tools").size()).isEqualTo(4);
            assertThat(json.path("evidence").path("frontend").path("tests").intValue()).isEqualTo(3);
            assertThat(json.path("evidence").path("javaTests").path("tests").intValue()).isEqualTo(2);
            var expectedJson = new ObjectMapper();
            assertThat(json.path("evidence")).isEqualTo(expectedJson.readTree(expectedJson.writeValueAsBytes(evidence())));
            assertThat(json.path("jarSha256").textValue()).isEqualTo(artifact.summary().jarSha256());
            assertThat(json.path("jarBytes").longValue()).isEqualTo(Files.size(input));
            assertThat(artifact.summary().jarSha256()).isEqualTo(HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))));
            assertThat(artifact.summary().manifestSha256()).isEqualTo(HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve("manifest.json")))));
            assertThat(json.toString()).doesNotContain(temporary.toString(), build.ownerId().toString(), "password", "stdout");
            artifact.verify(budget());
            byte[] manifest = Files.readAllBytes(directory.resolve("manifest.json"));
            assertThatThrownBy(() -> ExecutionArtifact.seal(paths, task, build, evidence(), budget(), () -> {}))
                    .hasMessage("EXECUTION_OUTPUT_NOT_CLEAN").hasNoCause();
            assertThat(Files.readAllBytes(directory.resolve("manifest.json"))).isEqualTo(manifest);
            assertThat(Files.readAllBytes(artifact.jar())).isEqualTo(Files.readAllBytes(input));
        }
    }

    @Test void refusesMissingEmptyOversizedLinkedAndWritableInputs() throws Exception {
        for (String mode : List.of("missing", "empty", "large", "symlink", "hardlink", "directory", "writable", "target-link")) {
            Path root = temporary.toRealPath().resolve(mode);
            try (var paths = new ExecutionPaths(root, List.of())) {
                var build = build(); var task = paths.createBuild(build.id()); Path jar = input(paths, task);
                switch (mode) {
                    case "missing" -> Files.move(jar, jar.resolveSibling("other.jar"));
                    case "empty" -> Files.write(jar, new byte[0]);
                    case "large" -> {
                        try (var channel = FileChannel.open(jar, StandardOpenOption.WRITE)) {
                            channel.position(ExecutionArtifact.MAX_JAR_BYTES); channel.write(ByteBuffer.wrap(new byte[]{0}));
                        }
                    }
                    case "symlink" -> {
                        Path other = Files.move(jar, jar.resolveSibling("other.jar")); Files.createSymbolicLink(jar, other);
                    }
                    case "hardlink" -> Files.createLink(jar.resolveSibling("alias.jar"), jar);
                    case "directory" -> { Files.move(jar, jar.resolveSibling("other.jar")); Files.createDirectory(jar); }
                    case "writable" -> Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("rw-rw-rw-"));
                    case "target-link" -> {
                        Path target = jar.getParent(), other = Files.move(target, target.resolveSibling("other"));
                        Files.createSymbolicLink(target, other);
                    }
                }
                assertThatThrownBy(() -> ExecutionArtifact.seal(paths, task, build, evidence(), budget(), () -> {}))
                        .as(mode).hasMessage(Set.of("symlink", "target-link").contains(mode)
                                ? "EXECUTION_STORAGE_UNAVAILABLE" : "EXECUTION_ARTIFACT_INVALID").hasNoCause();
                assertThat(root.resolve("artifacts").resolve(build.id().toString())).doesNotExist();
                assertThat(paths.project(task)).exists();
            }
        }
    }

    @Test void rejectsTamperingWithJarManifestPermissionsMembershipAndFileIdentity() throws Exception {
        for (String mode : List.of("jar", "manifest", "same-bytes", "symlink", "hardlink", "mode", "extra", "directory")) {
            Path root = temporary.toRealPath().resolve(mode);
            try (var paths = new ExecutionPaths(root, List.of())) {
                var build = build(); var task = paths.createBuild(build.id()); input(paths, task);
                var artifact = ExecutionArtifact.seal(paths, task, build, evidence(), budget(), () -> {});
                Path jar = artifact.jar(), directory = jar.getParent(), manifest = directory.resolve("manifest.json");
                switch (mode) {
                    case "jar" -> {
                        var time = Files.getLastModifiedTime(jar);
                        Files.write(jar, new byte[]{80, 75, 3, 4, 4, 5, 6});
                        Files.setLastModifiedTime(jar, time); // Neither size nor mtime can substitute for hashing.
                    }
                    case "manifest" -> Files.writeString(manifest, Files.readString(manifest).replace(HASH, "b".repeat(64)));
                    case "same-bytes" -> {
                        Path old = Files.move(jar, paths.directory(task).resolve("old.jar"));
                        Files.copy(old, jar, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                    case "symlink" -> {
                        Path old = Files.move(jar, paths.directory(task).resolve("old.jar"));
                        Files.createSymbolicLink(jar, old);
                    }
                    case "hardlink" -> Files.createLink(paths.directory(task).resolve("alias.jar"), jar);
                    case "mode" -> Files.setPosixFilePermissions(manifest, PosixFilePermissions.fromString("rw-r--r--"));
                    case "extra" -> Files.writeString(directory.resolve("extra"), "not allowed");
                    case "directory" -> Files.move(directory, directory.resolveSibling("displaced"));
                }
                assertThatThrownBy(() -> artifact.verify(budget())).isInstanceOf(IOException.class).hasNoCause();
            }
        }
    }

    @Test void validatesEvidenceAndBuildBindingBeforeCreatingArtifactDirectories() throws Exception {
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("metadata"), List.of())) {
            var build = build(); var task = paths.createBuild(build.id()); input(paths, task);
            var valid = evidence();
            var missingTool = new EnumMap<>(valid.tools()); missingTool.remove(ExecutionToolchain.Kind.JAVA);
            for (var invalid : List.of(
                    new ExecutionArtifact.Evidence(missingTool, valid.npm(), valid.maven(), valid.frontend(), valid.javaTests(), valid.resources()),
                    new ExecutionArtifact.Evidence(valid.tools(), valid.npm(), new ExecutionMavenPolicy.Verified(HASH, "b".repeat(64)),
                            valid.frontend(), valid.javaTests(), valid.resources()),
                    new ExecutionArtifact.Evidence(valid.tools(), new ExecutionNpmPolicy.Verified("PRIVATE_CANARY", HASH, HASH, 1),
                            valid.maven(), valid.frontend(), valid.javaTests(), valid.resources()),
                    new ExecutionArtifact.Evidence(valid.tools(), valid.npm(), valid.maven(), new ExecutionDiagnostics.Frontend(1, 1, 0),
                            valid.javaTests(), valid.resources()),
                    new ExecutionArtifact.Evidence(valid.tools(), valid.npm(), valid.maven(), valid.frontend(),
                            new ExecutionJavaReport.Summary(2, 1), valid.resources()),
                    new ExecutionArtifact.Evidence(valid.tools(), valid.npm(), valid.maven(), valid.frontend(), valid.javaTests(),
                            new ExecutionPackaging.Summary(1, (32L << 20) + 1, HASH)))) {
                assertThatThrownBy(() -> ExecutionArtifact.seal(paths, task, build, invalid, budget(), () -> {}))
                        .hasMessage("EXECUTION_ARTIFACT_INVALID").hasNoCause();
            }
            assertThatThrownBy(() -> ExecutionArtifact.seal(paths, task, build(), valid, budget(), () -> {}))
                    .hasMessage("EXECUTION_ARTIFACT_INVALID");
            try (var children = Files.list(temporary.toRealPath().resolve("metadata/artifacts"))) { assertThat(children).isEmpty(); }
            assertThatThrownBy(() -> valid.tools().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test void preservesPreviousArtifactAndFailureEvidenceWhenSourceChangesAfterCopy() throws Exception {
        Path root = temporary.toRealPath().resolve("failure");
        try (var paths = new ExecutionPaths(root, List.of())) {
            var first = build(); var firstTask = paths.createBuild(first.id()); input(paths, firstTask);
            var previous = ExecutionArtifact.seal(paths, firstTask, first, evidence(), budget(), () -> {});
            var next = build(); var nextTask = paths.createBuild(next.id()); Path input = input(paths, nextTask);
            var checks = new AtomicInteger();
            assertThatThrownBy(() -> ExecutionArtifact.seal(paths, nextTask, next, evidence(), budget(), () -> {
                if (checks.incrementAndGet() == 2) Files.write(input, new byte[]{80, 75, 3, 4, 4, 5, 6});
            })).hasMessage("EXECUTION_ARTIFACT_INVALID");
            Path failed = root.resolve("artifacts").resolve(next.id().toString());
            assertThat(failed.resolve("app.jar")).exists();
            assertThatThrownBy(() -> ExecutionArtifact.seal(paths, nextTask, next, evidence(), budget(), () -> {}))
                    .hasMessage("EXECUTION_OUTPUT_NOT_CLEAN");
            previous.verify(budget());
        }
    }

    @Test void honoursTransactionCancellationCapacityAndSharedDeadlineWithoutCleanup() throws Exception {
        Path root = temporary.toRealPath().resolve("boundary");
        try (var paths = new ExecutionPaths(root, List.of())) {
            var build = build(); var task = paths.createBuild(build.id()); Path jar = input(paths, task);
            assertThatThrownBy(() -> ExecutionArtifact.seal(paths, task, build, evidence(), () -> Duration.ZERO, () -> {}))
                    .hasMessage("EXECUTION_TIMEOUT");
            assertThatThrownBy(() -> ExecutionArtifact.seal(paths, task, build, evidence(), budget(),
                    () -> { throw new IOException("PRIVATE_CANARY"); })).hasMessage("EXECUTION_ARTIFACT_INVALID").hasNoCause();
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> ExecutionArtifact.seal(paths, task, build, evidence(), budget(), () -> {}))
                        .hasMessage("EXECUTION_CANCELLED");
            } finally { Thread.interrupted(); }
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> ExecutionArtifact.seal(paths, task, build, evidence(), budget(), () -> {}))
                        .hasMessage("EXECUTION_TRANSACTION_ACTIVE");
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            Path directory = root.resolve("artifacts").resolve(build.id().toString());
            assertThat(directory).doesNotExist();
            Files.write(jar, new byte[131072]);
            assertThatThrownBy(() -> ExecutionArtifact.seal(paths, task, build, evidence(), () -> {
                Path copied = directory.resolve("app.jar");
                return Files.exists(copied) && Files.size(copied) > 0 ? Duration.ZERO : Duration.ofSeconds(30);
            }, () -> {})).hasMessage("EXECUTION_TIMEOUT");
            assertThat(Files.size(directory.resolve("app.jar"))).isEqualTo(65536);
            assertThat(directory.resolve("manifest.json")).doesNotExist();
        }
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("capacity"), List.of(), 1024, 0)) {
            var build = build(); var task = paths.createBuild(build.id()); input(paths, task);
            assertThatThrownBy(() -> ExecutionArtifact.seal(paths, task, build, evidence(), budget(), () -> {}))
                    .hasMessage("EXECUTION_STORAGE_CAPACITY");
            assertThat(temporary.toRealPath().resolve("capacity/artifacts").resolve(build.id().toString())).exists();
        }
    }
}
