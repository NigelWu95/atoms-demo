package dev.atoms.demo.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class WorkspacePathsTest {
    @TempDir Path temporary;
    @Test void requiresDedicatedPrivateRealDirectoryAndRefusesBroadRoots() throws Exception {
        Path root = temporary.toRealPath();
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        var paths = new WorkspacePaths(root);
        UUID id = UUID.randomUUID();
        assertThat(paths.create(id)).isEqualTo(root.resolve(id.toString()));
        assertThatThrownBy(() -> paths.create(id)).isInstanceOf(java.io.IOException.class);
        for (Path broad : java.util.List.of(Path.of("/"), Path.of(System.getProperty("user.home")),
                Path.of("").toAbsolutePath(), Path.of("..").toAbsolutePath().normalize()))
            assertThatThrownBy(() -> new WorkspacePaths(broad)).isInstanceOf(java.io.IOException.class);
        Path link = root.resolve("alias");
        Files.createSymbolicLink(link, root.resolve(id.toString()));
        assertThatThrownBy(() -> new WorkspacePaths(link)).isInstanceOf(java.io.IOException.class);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatThrownBy(() -> paths.task(id)).isInstanceOf(java.io.IOException.class);
    }

    @Test void detectsReplacedRootAndDoesNotTouchExternalFiles() throws Exception {
        Path parent = temporary.toRealPath();
        Path root = Files.createDirectory(parent.resolve("root"), PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        var paths = new WorkspacePaths(root);
        UUID id = UUID.randomUUID();
        Files.move(root, parent.resolve("saved"));
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        assertThatThrownBy(() -> paths.create(id)).isInstanceOf(java.io.IOException.class);
        try (var children = Files.list(root)) { assertThat(children.toList()).isEmpty(); }
    }
}
