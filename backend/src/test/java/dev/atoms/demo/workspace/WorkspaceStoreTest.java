package dev.atoms.demo.workspace;

import dev.atoms.demo.engineering.EngineeringBundle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class WorkspaceStoreTest {
    @TempDir Path temporary;
    @Test void materializesOnlyFreshTaskAndNeverFollowsOrOverwritesPaths() throws Exception {
        Path root = temporary.toRealPath();
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        var store = new WorkspaceStore(new WorkspacePaths(root));
        UUID id = UUID.randomUUID();
        var bundle = new EngineeringBundle("ab".repeat(32), List.of(
                new EngineeringBundle.File("README.md", new byte[]{1}),
                new EngineeringBundle.File("backend/mvnw", new byte[]{2})));
        var prepared = store.prepare(id, bundle);
        assertThat(Files.readAllBytes(prepared.work().resolve("README.md"))).containsExactly((byte) 1);
        assertThat(Files.getPosixFilePermissions(prepared.work())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(prepared.work().resolve("README.md")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.isExecutable(prepared.work().resolve("backend/mvnw"))).isTrue();
        assertThatThrownBy(() -> store.prepare(id, bundle)).isInstanceOf(java.io.IOException.class);
        UUID malicious = UUID.randomUUID();
        assertThatThrownBy(() -> store.prepare(malicious, new EngineeringBundle("cd".repeat(32),
                List.of(new EngineeringBundle.File("../escape", new byte[]{3})))))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class);
        assertThat(Files.exists(root.resolve("escape"))).isFalse();
        assertThat(Files.exists(root.resolve(malicious.toString()))).isFalse();
    }

    @Test void revalidatesFrozenArchiveAndSourcesWithoutReassemblingThem() throws Exception {
        Path root = temporary.toRealPath();
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        var store = new WorkspaceStore(new WorkspacePaths(root));
        UUID id = UUID.randomUUID();
        var bundle = new EngineeringBundle("ab".repeat(32), List.of(new EngineeringBundle.File("README.md", new byte[]{1})));
        var prepared = store.prepare(id, bundle);
        byte[] expected = dev.atoms.demo.engineering.EngineeringZip.write(bundle);
        String hash = BuildArtifacts.hash(expected);
        assertThat(store.archive(id, hash)).isEqualTo(expected);
        store.verifySources(id, bundle);
        Files.write(prepared.work().resolve("README.md"), new byte[]{2});
        assertThatThrownBy(() -> store.verifySources(id, bundle)).isInstanceOf(java.io.IOException.class);
        assertThat(store.archive(id, hash)).isEqualTo(expected);
        Files.write(prepared.archive(), new byte[]{3});
        assertThatThrownBy(() -> store.archive(id, hash)).isInstanceOf(java.io.IOException.class);
        Files.move(prepared.archive(), prepared.archive().resolveSibling("saved.zip"));
        Files.createSymbolicLink(prepared.archive(), prepared.archive().resolveSibling("saved.zip"));
        assertThatThrownBy(() -> store.archive(id, hash)).isInstanceOf(java.io.IOException.class);
    }

    @Test void boundedReadsRejectOversizeAndUnsafeFileTypes() throws Exception {
        Path root = temporary.toRealPath();
        Path file = Files.createFile(root.resolve("file"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(file, new byte[]{1, 2});
        assertThat(BuildArtifacts.read(file, 2)).containsExactly((byte) 1, (byte) 2);
        assertThatThrownBy(() -> BuildArtifacts.read(file, 1)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> BuildArtifacts.read(root, 2)).isInstanceOf(java.io.IOException.class);
        Path linked = Files.createLink(root.resolve("hardlink"), file);
        assertThatThrownBy(() -> BuildArtifacts.read(linked, 2)).isInstanceOf(java.io.IOException.class);
    }
}
