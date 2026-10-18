package dev.atoms.demo.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Executes only the packaged stdlib helper, never a path or script supplied by a project. */
final class SourceFileProcess {
    private static final int LIMIT = 24 << 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path executable;
    private final Duration timeout;
    private final Consumer<ProcessHandle> started;

    SourceFileProcess(Path executable) { this(executable, Duration.ofSeconds(60), handle -> {}); }
    SourceFileProcess(Path executable, Duration timeout, Consumer<ProcessHandle> started) {
        this.executable = executable;
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid helper timeout");
        this.timeout = timeout; this.started = started;
    }

    ProcessBuilder builder() throws IOException {
        if (!executable.isAbsolute() || !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                || !Files.isExecutable(executable)) throw failure("UNAVAILABLE");
        String script;
        try (var input = SourceFileProcess.class.getResourceAsStream("/source/workspace_helper.py")) {
            if (input == null) throw failure("UNAVAILABLE");
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        // -I omits cwd/user packages and ignores PYTHON*; -S also disables site initialization.
        // The script is a trusted classpath resource, while all variable data travels on stdin.
        var builder = new ProcessBuilder(executable.toString(), "-I", "-S", "-B", "-X", "utf8", "-c", script)
                .directory(Path.of("/").toFile());
        builder.environment().clear();
        builder.environment().putAll(Map.of("PATH", "/usr/bin:/bin", "LANG", "C", "LC_ALL", "C"));
        return builder;
    }

    JsonNode run(Map<String, ?> request) throws IOException {
        byte[] input = JSON.writeValueAsBytes(request);
        if (input.length > LIMIT) throw failure("INPUT_LIMIT");
        Process process = builder().start();
        var streams = Executors.newFixedThreadPool(3, task -> new Thread(task, "atoms-source-files-io"));
        try {
            started.accept(process.toHandle());
            var stdout = streams.submit(() -> read(process.getInputStream(), LIMIT, process));
            var stderr = streams.submit(() -> read(process.getErrorStream(), 64 << 10, process));
            var stdin = streams.submit(() -> {
                try (var output = process.getOutputStream()) { output.write(input); }
                catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            });
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) throw failure("TIMEOUT");
            byte[] result = completed(stdout); completed(stderr);
            if (process.exitValue() != 0) throw failure("FAILED");
            completed(stdin);
            try {
                JsonNode value = JSON.readTree(result);
                if (value == null || !value.isObject()) throw failure("PROTOCOL");
                return value;
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw failure("PROTOCOL"); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw failure("CANCELLED");
        } finally {
            try { stop(process); }
            finally { streams.shutdownNow(); }
        }
    }

    static List<Long> identity(Path path) throws IOException {
        var attributes = Files.readAttributes(path, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
        return List.of(((Number) attributes.get("dev")).longValue(), ((Number) attributes.get("ino")).longValue());
    }

    private static byte[] read(InputStream input, int limit, Process process) throws IOException {
        try (input; var bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) {
                if (bytes.size() + count > limit) { process.destroyForcibly(); throw failure("OUTPUT_LIMIT"); }
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private static <T> T completed(Future<T> future) throws IOException, InterruptedException {
        try { return future.get(5, TimeUnit.SECONDS); }
        catch (ExecutionException error) { throw failure("IO_FAILED"); }
        catch (TimeoutException error) { throw failure("IO_UNSETTLED"); }
    }

    private static void stop(Process process) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            // This fixed helper never forks or invokes another executable. No cached PID/name lookup.
            if (process.isAlive()) process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw failure("PROCESS_UNSETTLED");
        } catch (InterruptedException error) {
            interrupted = true; throw failure("PROCESS_UNSETTLED");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static IOException failure(String suffix) { return new IOException("SOURCE_FILES_" + suffix); }
}
