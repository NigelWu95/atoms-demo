package dev.atoms.demo.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class BuildRootLeaseTest {
    @org.junit.jupiter.api.Test void toleratesOnlyDisappearingTransientChildrenDuringUsageScan() throws Exception {
        var visitor=BuildRootLease.budgetVisitor(1024);
        Path transientFile=Path.of("/private/tmp/build-transient-test");
        assertThat(visitor.visitFileFailed(transientFile,new java.nio.file.NoSuchFileException("gone")))
                .isEqualTo(java.nio.file.FileVisitResult.CONTINUE);
        assertThat(visitor.postVisitDirectory(transientFile,new java.nio.file.NoSuchFileException("gone")))
                .isEqualTo(java.nio.file.FileVisitResult.CONTINUE);
        assertThatThrownBy(()->visitor.visitFileFailed(transientFile,new java.nio.file.AccessDeniedException("denied")))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->visitor.postVisitDirectory(transientFile,new java.io.IOException("broken")))
                .isInstanceOf(java.io.IOException.class);
    }
    @TempDir Path temporary;
    private WorkspacePaths paths() throws Exception {
        Path root = temporary.toRealPath();
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        return new WorkspacePaths(root);
    }
    @Test void oneSchedulerPerRootAcrossRealProcessesAndNoLockFileDeletion() throws Exception {
        var paths = paths();
        try (var lease = new BuildRootLease(paths)) {
            assertThatThrownBy(() -> new BuildRootLease(paths)).isInstanceOf(java.io.IOException.class);
            assertThat(probe(paths.root())).isEqualTo(9);
            lease.checkCapacity();
        }
        assertThat(Files.isRegularFile(paths.root().resolve(".scheduler.lock"))).isTrue();
        assertThat(probe(paths.root())).isZero();
    }
    @Test void directoryAndByteBudgetsRejectWithoutDeletingExistingArtifacts() throws Exception {
        var paths = paths();
        try (var lease = new BuildRootLease(paths, 2, 8, 0)) {
            Path one = paths.create(UUID.randomUUID());
            lease.checkCapacity();
            Path evidence = Files.write(one.resolve("evidence"), new byte[9]);
            assertThatThrownBy(lease::checkCapacity).isInstanceOf(java.io.IOException.class);
            assertThat(Files.size(evidence)).isEqualTo(9);
        }
        try (var lease = new BuildRootLease(paths, 2, 100, 0)) {
            paths.create(UUID.randomUUID());
            assertThatThrownBy(lease::checkCapacity).isInstanceOf(java.io.IOException.class);
        }
    }
    private int probe(Path root) throws Exception {
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), BuildLockProbe.class.getName(), root.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return child.exitValue();
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }
}
