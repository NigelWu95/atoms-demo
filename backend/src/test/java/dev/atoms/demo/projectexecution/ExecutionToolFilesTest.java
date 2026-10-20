package dev.atoms.demo.projectexecution;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExecutionToolFilesTest {
    @TempDir Path temporary;
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }

    @Test void identityIncludesLibraryBytesNotJustExecutableAndDoesNotExposePaths() throws Exception {
        Path root = installation("stable");
        var identity = ExecutionToolFiles.capture(root, List.of("bin", "lib", "release"), budget());
        identity.verify(budget());
        assertThat(identity.summary().sha256()).matches("[a-f0-9]{64}");
        assertThat(identity.summary().files()).isEqualTo(3);
        assertThat(identity.summary().bytes()).isEqualTo(12);
        assertThat(identity.toString()).doesNotContain(root.toString());
        assertThat(identity.summary().toString()).doesNotContain(root.toString());
        Files.writeString(root.resolve("lib/runtime"), "edit"); // Same size and inode.
        assertThatThrownBy(() -> identity.verify(budget())).hasMessage("EXECUTION_TOOL_CHANGED").hasNoCause();
    }

    @Test void replacementPermissionsNewFilesLinksAndMissingLibrariesRevokeIdentity() throws Exception {
        for (String attack : List.of("replace", "mode", "extra", "symlink", "hardlink", "missing", "root")) {
            Path root = installation(attack);
            var identity = ExecutionToolFiles.capture(root, List.of("bin", "lib", "release"), budget());
            Path library = root.resolve("lib/runtime");
            switch (attack) {
                case "replace" -> {
                    Files.move(library, root.resolve("saved"));
                    file(library, "code");
                }
                case "mode" -> Files.setPosixFilePermissions(library, PosixFilePermissions.fromString("rw-rw-r--"));
                case "extra" -> file(root.resolve("lib/extension"), "code");
                case "symlink" -> Files.createSymbolicLink(root.resolve("lib/link"), root.resolve("release"));
                case "hardlink" -> Files.createLink(temporary.resolve("alias"), library);
                case "missing" -> Files.move(library, root.resolve("saved"));
                case "root" -> {
                    Files.move(root, temporary.resolve("old-root"));
                    Files.createSymbolicLink(root, temporary.resolve("old-root"));
                }
            }
            assertThatThrownBy(() -> identity.verify(budget())).hasMessage("EXECUTION_TOOL_CHANGED").hasNoCause();
        }
    }

    @Test void unsupportedInputsAndExpiredScanFailWithoutReturningIdentity() throws Exception {
        Path root = installation("invalid");
        for (var names : List.of(List.of("../outside"), List.of("/absolute"), List.of("bin", "bin"), List.of(".", "bin"),
                List.of("missing"), List.<String>of()))
            assertThatThrownBy(() -> ExecutionToolFiles.capture(root, names, budget())).hasMessage("EXECUTION_TOOL_UNSUPPORTED");
        assertThatThrownBy(() -> ExecutionToolFiles.capture(root, List.of("bin"), () -> Duration.ZERO))
                .hasMessage("EXECUTION_TIMEOUT");
        var calls = new java.util.concurrent.atomic.AtomicInteger(4);
        assertThatThrownBy(() -> ExecutionToolFiles.capture(root, List.of("bin", "lib"), () ->
                calls.decrementAndGet() > 0 ? Duration.ofSeconds(1) : Duration.ZERO)).hasMessage("EXECUTION_TIMEOUT");
        Files.createSymbolicLink(root.resolve("lib/external"), temporary);
        assertThatThrownBy(() -> ExecutionToolFiles.capture(root, List.of("lib"), budget())).hasMessage("EXECUTION_TOOL_UNSUPPORTED");
    }

    private Path installation(String name) throws Exception {
        Path root = Files.createDirectory(temporary.toRealPath().resolve(name));
        Files.createDirectory(root.resolve("bin")); Files.createDirectory(root.resolve("lib"));
        file(root.resolve("bin/tool"), "exec"); file(root.resolve("lib/runtime"), "code"); file(root.resolve("release"), "test");
        return root;
    }
    private void file(Path path, String value) throws Exception {
        Files.writeString(path, value, StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
    }
}
