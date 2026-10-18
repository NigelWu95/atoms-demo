package dev.atoms.demo.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceFileProcessTest {
    @TempDir Path temporary;
    static Path python() throws IOException {
        return Path.of(Files.exists(Path.of("/usr/local/bin/python3")) ? "/usr/local/bin/python3" : "/usr/bin/python3").toRealPath();
    }

    @Test void runsBundledHelperAndPassesDataOnlyThroughStdin() throws Exception {
        Path root = temporary.toRealPath();
        var request = Map.<String, Object>of("operation", "create", "root", root.toString(),
                "rootIdentity", SourceFileProcess.identity(root), "task", UUID.randomUUID().toString(),
                "marker", "CANARY-owned-marker\n", "files", List.of(Map.of("path", "hello.txt", "mode", 0100644, "content", "aGVsbG8=")));
        var process = new SourceFileProcess(python());
        var evidence = process.run(request);
        assertThat(evidence.path("project").size()).isEqualTo(2);
        assertThat(Files.readString(root.resolve(request.get("task") + "/project/hello.txt"))).isEqualTo("hello");
        var builder = process.builder();
        assertThat(builder.environment()).containsOnlyKeys("PATH", "LANG", "LC_ALL");
        assertThat(builder.command()).startsWith(python().toString(), "-I", "-S", "-B", "-X", "utf8", "-c");
        assertThat(builder.command().toString()).doesNotContain("CANARY-owned-marker");
    }

    @Test void malformedAndOversizeRequestsDoNotLeakTheirContents() throws Exception {
        var process = new SourceFileProcess(python());
        assertThatThrownBy(() -> process.run(Map.of("secret", "CANARY-private")))
                .isInstanceOf(IOException.class).hasMessage("SOURCE_FILES_FAILED");
        assertThatThrownBy(() -> process.run(Map.of("padding", "x".repeat((24 << 20) + 1))))
                .isInstanceOf(IOException.class).hasMessage("SOURCE_FILES_INPUT_LIMIT");
        try (var paths = Files.list(temporary)) { assertThat(paths.count()).isZero(); }
    }

    @Test void timeoutConfirmsTheOwnedHelperHasExited() throws Exception {
        var started = new AtomicReference<ProcessHandle>();
        var process = new SourceFileProcess(python(), Duration.ofNanos(1), started::set);
        assertThatThrownBy(() -> process.run(Map.of("operation", "check")))
                .isInstanceOf(IOException.class).hasMessage("SOURCE_FILES_TIMEOUT");
        assertThat(started.get()).isNotNull();
        assertThat(started.get().isAlive()).isFalse();
    }
}
