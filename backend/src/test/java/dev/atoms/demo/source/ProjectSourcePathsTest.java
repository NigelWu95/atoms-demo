package dev.atoms.demo.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ProjectSourcePathsTest {
    @TempDir Path temporary;

    @Test void createsPrivateMarkedRootsAndNeverAdoptsUnmarkedDirectories() throws Exception {
        Path parent = temporary.toRealPath(), source = parent.resolve("source"), work = parent.resolve("work");
        try (var paths = new ProjectSourcePaths(source, work)) {
            assertThat(Files.getPosixFilePermissions(source)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            var project = paths.createProject(UUID.randomUUID());
            assertThat(project.getParent()).isEqualTo(source);
            assertThat(Files.getPosixFilePermissions(project)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        }
        try (var paths = new ProjectSourcePaths(source, work)) { assertThat(paths.root()).isEqualTo(source); }
        Path foreign = Files.createDirectory(parent.resolve("foreign"));
        Files.writeString(foreign.resolve("keep.txt"), "keep");
        assertThatThrownBy(() -> new ProjectSourcePaths(foreign, parent.resolve("other-work"))).isInstanceOf(IOException.class);
        assertThat(Files.readString(foreign.resolve("keep.txt"))).isEqualTo("keep");
        assertThat(Files.exists(parent.resolve("other-work"))).isFalse();
    }

    @Test void refusesNestedBroadOrSymlinkRootsAndDoesNotRepairTheirPermissions() throws Exception {
        Path parent = temporary.toRealPath();
        for (Path forbidden : java.util.List.of(Path.of("/"), Path.of(System.getProperty("user.home")),
                Path.of("").toAbsolutePath(), Path.of("..").toAbsolutePath().normalize())) {
            assertThatThrownBy(() -> new ProjectSourcePaths(forbidden, parent.resolve("work"))).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> new ProjectSourcePaths(parent.resolve("source"), parent.resolve("source/work")))
                .isInstanceOf(IOException.class);
        Path alias = Files.createSymbolicLink(parent.resolve("alias"), parent);
        assertThatThrownBy(() -> new ProjectSourcePaths(alias.resolve("source"), parent.resolve("work")))
                .isInstanceOf(IOException.class);
        Path source = parent.resolve("source"), work = parent.resolve("work");
        try (var paths = new ProjectSourcePaths(source, work)) {
            Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rwxr-xr-x"));
            assertThatThrownBy(paths::root).isInstanceOf(IOException.class);
        }
        assertThat(Files.getPosixFilePermissions(source)).isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test void onlyOneWriterOwnsTheStableRootLeaseAcrossProcesses() throws Exception {
        Path parent = temporary.toRealPath(), source = parent.resolve("source"), work = parent.resolve("work");
        try (var paths = new ProjectSourcePaths(source, work)) {
            assertThatThrownBy(() -> new ProjectSourcePaths(source, work)).isInstanceOf(IOException.class);
            assertThat(probe(source, work)).isEqualTo(9);
            paths.verify();
            assertThat(probe(source, work)).isEqualTo(9);
        }
        assertThat(probe(source, work)).isZero();
        assertThat(Files.isRegularFile(source.resolve(".atoms-source-root"))).isTrue();
    }

    @Test void refusesReplacedRootsAndAliasedLockWithoutChangingOtherDirectories() throws Exception {
        Path parent = temporary.toRealPath(), source = parent.resolve("source"), work = parent.resolve("work");
        try (var paths = new ProjectSourcePaths(source, work)) {
            Files.move(source, parent.resolve("saved"));
            Files.createDirectory(source, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            assertThatThrownBy(() -> paths.createProject(UUID.randomUUID())).isInstanceOf(IOException.class);
            try (var children = Files.list(source)) { assertThat(children.toList()).isEmpty(); }
        }
        Files.createLink(parent.resolve("saved/marker-alias"), parent.resolve("saved/.atoms-source-root"));
        assertThatThrownBy(() -> new ProjectSourcePaths(parent.resolve("saved"), work)).isInstanceOf(IOException.class);
    }

    private int probe(Path source, Path work) throws Exception {
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), Probe.class.getName(), source.toString(), work.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            return child.exitValue();
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    public static class Probe {
        public static void main(String[] args) {
            try (var paths = new ProjectSourcePaths(Path.of(args[0]), Path.of(args[1]))) { paths.verify(); }
            catch (Exception error) { System.exit(9); }
        }
    }
}
