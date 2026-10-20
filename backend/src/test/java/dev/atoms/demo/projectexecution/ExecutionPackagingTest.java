package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class ExecutionPackagingTest {
    @TempDir Path temporary;
    private ExecutionPaths paths(String name) throws Exception {
        return new ExecutionPaths(temporary.toRealPath().resolve(name), List.of());
    }
    private Path input(ExecutionPaths paths, ExecutionPaths.Task task) throws Exception {
        Path project = paths.project(task);
        ExecutionRootLease.directory(project.resolve("frontend"));
        ExecutionRootLease.directory(project.resolve("backend"));
        Path dist = Files.createDirectory(project.resolve("frontend/dist"));
        Files.writeString(dist.resolve("index.html"), "<html>application</html>");
        Files.createDirectory(dist.resolve("assets"));
        Files.writeString(dist.resolve("assets/app.js"), "console.log('app');");
        return dist;
    }
    private OwnedProcess.Budget budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }

    @Test void copiesExactFrontendBytesOutsideSourceAndVerifiesPrivateSnapshot() throws Exception {
        try (var paths = paths("happy")) {
            var task = paths.createBuild(UUID.randomUUID());
            Path dist = input(paths, task);
            var checks = new AtomicInteger();
            var resource = ExecutionPackaging.assemble(paths, task, budget(), checks::incrementAndGet);
            assertThat(resource.directory()).isEqualTo(paths.directory(task).resolve("resources"));
            assertThat(resource.summary().files()).isEqualTo(2);
            assertThat(resource.summary().bytes()).isEqualTo(Files.size(dist.resolve("index.html")) + Files.size(dist.resolve("assets/app.js")));
            assertThat(resource.summary().sha256()).matches("[a-f0-9]{64}");
            assertThat(Files.readAllBytes(resource.directory().resolve("static/index.html")))
                    .isEqualTo(Files.readAllBytes(dist.resolve("index.html")));
            assertThat(Files.readString(resource.directory().resolve("static/assets/app.js"))).isEqualTo("console.log('app');");
            assertThat(Files.getPosixFilePermissions(resource.directory())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(resource.directory().resolve("static/index.html")))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThat(paths.project(task).resolve("backend/target")).doesNotExist();
            resource.verify(budget());
            assertThat(checks.get()).isGreaterThanOrEqualTo(3);
            assertThatThrownBy(() -> ExecutionPackaging.assemble(paths, task, budget(), () -> {}))
                    .hasMessage("EXECUTION_OUTPUT_NOT_CLEAN");
        }
    }

    @Test void rejectsUnsafeMissingOversizedOrLinkedOutputWithoutOverwritingAnything() throws Exception {
        for (String mode : List.of("missing", "empty", "secret", "symlink", "directory-link", "hardlink", "writable",
                "large", "total", "many", "deep", "stale-target", "source-static")) {
            try (var paths = paths(mode)) {
                var task = paths.createBuild(UUID.randomUUID());
                Path dist = input(paths, task), index = dist.resolve("index.html");
                switch (mode) {
                    case "missing" -> Files.move(index, dist.resolve("not-index.html"));
                    case "empty" -> Files.writeString(index, "");
                    case "secret" -> Files.writeString(dist.resolve(".env"), "PRIVATE_CANARY");
                    case "symlink" -> Files.createSymbolicLink(dist.resolve("alias.js"), index);
                    case "directory-link" -> Files.createSymbolicLink(dist.resolve("alias"), dist.resolve("assets"));
                    case "hardlink" -> Files.createLink(dist.resolve("alias.js"), index);
                    case "writable" -> Files.setPosixFilePermissions(index, PosixFilePermissions.fromString("rw-rw-rw-"));
                    case "large" -> sparse(dist.resolve("large.js"), (8L << 20) + 1);
                    case "total" -> { for (int i = 0; i < 5; i++) sparse(dist.resolve("large" + i + ".js"), 8L << 20); }
                    case "many" -> { for (int i = 0; i < 1025; i++) Files.createFile(dist.resolve("file" + i)); }
                    case "deep" -> { Path nested = dist; for (int i = 0; i < 14; i++) nested = Files.createDirectory(nested.resolve("a")); }
                    case "stale-target" -> Files.createDirectory(paths.project(task).resolve("backend/target"));
                    case "source-static" -> Files.createDirectories(paths.project(task).resolve("backend/src/main/resources/static"));
                }
                assertThatThrownBy(() -> ExecutionPackaging.assemble(paths, task, budget(), () -> {}))
                        .isInstanceOf(java.io.IOException.class).hasNoCause();
                assertThat(dist).exists();
                if (mode.equals("secret")) assertThat(Files.readString(dist.resolve(".env"))).isEqualTo("PRIVATE_CANARY");
            }
        }
    }

    @Test void detectsSourceResourceAndDirectoryChangesAfterAssembly() throws Exception {
        for (String mode : List.of("source", "target", "same-bytes-new-inode", "extra", "directory", "mode")) {
            try (var paths = paths(mode)) {
                var task = paths.createBuild(UUID.randomUUID());
                Path dist = input(paths, task);
                var resource = ExecutionPackaging.assemble(paths, task, budget(), () -> {});
                Path index = resource.directory().resolve("static/index.html");
                switch (mode) {
                    case "source" -> Files.writeString(dist.resolve("index.html"), "changed");
                    case "target" -> Files.writeString(index, "changed");
                    case "same-bytes-new-inode" -> {
                        Files.move(index, paths.directory(task).resolve("original-index"));
                        Files.copy(paths.directory(task).resolve("original-index"), index);
                        Files.setPosixFilePermissions(index, PosixFilePermissions.fromString("rw-------"));
                    }
                    case "extra" -> Files.writeString(resource.directory().resolve("static/extra.js"), "changed");
                    case "directory" -> Files.move(resource.directory().resolve("static/assets"), resource.directory().resolve("static/other"));
                    case "mode" -> Files.setPosixFilePermissions(index, PosixFilePermissions.fromString("rw-r--r--"));
                }
                assertThatThrownBy(() -> resource.verify(budget())).hasMessage("EXECUTION_PACKAGING_INVALID").hasNoCause();
            }
        }
    }

    @Test void respectsBudgetSourceCheckAndTransactionBoundary() throws Exception {
        try (var paths = paths("boundaries")) {
            var task = paths.createBuild(UUID.randomUUID());
            input(paths, task);
            assertThatThrownBy(() -> ExecutionPackaging.assemble(paths, task, () -> Duration.ZERO, () -> {}))
                    .hasMessage("EXECUTION_TIMEOUT");
            assertThatThrownBy(() -> ExecutionPackaging.assemble(paths, task, budget(), () -> { throw new java.io.IOException("PRIVATE_CANARY"); }))
                    .hasMessage("EXECUTION_PACKAGING_INVALID").hasNoCause();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> ExecutionPackaging.assemble(paths, task, budget(), () -> {}))
                        .hasMessage("EXECUTION_TRANSACTION_ACTIVE");
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            assertThat(paths.directory(task).resolve("resources")).doesNotExist();
            var checks = new AtomicInteger();
            assertThatThrownBy(() -> ExecutionPackaging.assemble(paths, task, budget(), () -> {
                if (checks.incrementAndGet() > 1) throw new java.io.IOException("EXECUTION_SOURCE_CHANGED");
            })).hasMessage("EXECUTION_SOURCE_CHANGED");
            assertThat(paths.directory(task).resolve("resources")).exists(); // Failure evidence remains; never auto-delete.
        }
    }

    private static void sparse(Path file, long size) throws Exception {
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(size - 1); channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
    }
}
