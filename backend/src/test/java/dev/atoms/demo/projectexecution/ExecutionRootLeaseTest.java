package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExecutionRootLeaseTest {
    @TempDir Path temporary;

    @Test void oneOwnerAcrossJvmsEvenAfterLocalDuplicateAndRepeatedChecks() throws Exception {
        Path root = temporary.toRealPath().resolve("execution");
        try (var lease = new ExecutionRootLease(root, List.of())) {
            assertThat(lease.checked()).isEqualTo(root);
            assertThat(Files.getPosixFilePermissions(root)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(root.resolve(".atoms-execution-root")))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThatThrownBy(() -> new ExecutionRootLease(root, List.of())).isInstanceOf(IOException.class);
            for (int i = 0; i < 3; i++) lease.checked();
            assertThat(probe(root)).isEqualTo(9);
        }
        assertThat(Files.isRegularFile(root.resolve(".atoms-execution-root"))).isTrue();
        assertThat(probe(root)).isZero();
    }

    @Test void refusesRootUidBroadUnmarkedOverlappingOrLinkedDirectoriesBeforeWriting() throws Exception {
        Path parent = temporary.toRealPath(), root = parent.resolve("execution");
        assertThatThrownBy(() -> new ExecutionRootLease(root, List.of(), 0)).isInstanceOf(IOException.class);
        assertThat(Files.exists(root)).isFalse();
        for (Path broad : List.of(Path.of("/"), Path.of(System.getProperty("user.home")), Path.of("").toRealPath()))
            assertThatThrownBy(() -> new ExecutionRootLease(broad, List.of())).isInstanceOf(IOException.class);
        Files.createDirectory(root);
        Files.writeString(root.resolve("keep"), "user-owned");
        assertThatThrownBy(() -> new ExecutionRootLease(root, List.of())).isInstanceOf(IOException.class);
        assertThat(Files.readString(root.resolve("keep"))).isEqualTo("user-owned");
        Path rejected = parent.resolve("new");
        for (Path protectedRoot : List.of(rejected, rejected.resolve("source"), parent))
            assertThatThrownBy(() -> new ExecutionRootLease(rejected, List.of(protectedRoot))).isInstanceOf(IOException.class);
        assertThat(Files.exists(rejected)).isFalse();
        Files.createSymbolicLink(parent.resolve("alias"), root);
        assertThatThrownBy(() -> new ExecutionRootLease(parent.resolve("alias/new"), List.of())).isInstanceOf(IOException.class);
        assertThat(Files.exists(root.resolve("new"))).isFalse();
    }

    @Test void rootReplacementPermissionDriftAndClosedLeaseAreRejected() throws Exception {
        Path parent = temporary.toRealPath(), root = parent.resolve("execution");
        var lease = new ExecutionRootLease(root, List.of());
        try {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
            assertThatThrownBy(lease::checked).isInstanceOf(IOException.class);
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
            Files.move(root, parent.resolve("saved"));
            Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            assertThatThrownBy(lease::checked).isInstanceOf(IOException.class);
        } finally { lease.close(); }
        lease.close();
        assertThatThrownBy(lease::checked).isInstanceOf(IOException.class);
    }

    @Test void markerContentReplacementAndHardLinkAliasesAreRejected() throws Exception {
        Path parent = temporary.toRealPath();
        for (String mode : List.of("content", "replace", "hardlink")) {
            Path root = parent.resolve(mode);
            try (var lease = new ExecutionRootLease(root, List.of())) {
                Path marker = root.resolve(".atoms-execution-root");
                if (mode.equals("content")) Files.writeString(marker, "altered");
                else if (mode.equals("hardlink")) Files.createLink(root.resolve("alias"), marker);
                else {
                    Files.move(marker, root.resolve("saved"));
                    Files.writeString(marker, "foreign-root-marker\n");
                    Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-------"));
                }
                assertThatThrownBy(lease::checked).isInstanceOf(IOException.class);
            }
            assertThatThrownBy(() -> new ExecutionRootLease(root, List.of())).isInstanceOf(IOException.class);
        }
    }

    private int probe(Path root) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), Probe.class.getName(), root.toString());
        builder.environment().clear();
        var child = builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            return child.exitValue();
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    public static final class Probe {
        public static void main(String[] arguments) {
            try (var lease = new ExecutionRootLease(Path.of(arguments[0]), List.of())) { lease.checked(); }
            catch (IOException error) { System.exit(9); }
        }
    }
}
