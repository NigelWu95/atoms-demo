package dev.atoms.demo.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Blocking worker-only bridge. Entry, paths and environment are trusted operator inputs, never HTTP fields. */
public final class NativeBuildProcess {
    private static final List<String> STAGES = List.of("PREPARING", "INSTALLING", "CHECKING_FRONTEND",
            "BUILDING_FRONTEND", "CHECKING_BACKEND", "VERIFYING", "COMPLETE");
    private static final Set<String> ERRORS = Set.of("BUILD_FAILED", "BUILD_TIMEOUT", "BUILD_CANCELLED", "BUILD_INTERRUPTED", "BUILD_INPUT_CHANGED");
    private final Duration timeout;
    private final Duration settlementTimeout;
    private final ObjectMapper json = new ObjectMapper();

    public NativeBuildProcess() { this(Duration.ofMinutes(10), Duration.ofSeconds(15)); }
    NativeBuildProcess(Duration timeout, Duration settlementTimeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0
                || settlementTimeout.isNegative() || settlementTimeout.isZero() || settlementTimeout.compareTo(Duration.ofSeconds(15)) > 0)
            throw new IllegalArgumentException("Invalid process deadline");
        this.timeout = timeout;
        this.settlementTimeout = settlementTimeout;
    }
    public record Result(boolean settled, String errorCode, String loadedRuntimeId) {
        static Result unknown() { return new Result(false, "BUILD_INTERRUPTED", null); }
    }

    public Result run(Path node, Path entry, Path task, UUID executionId, Path testConfig, Map<String, String> environment,
            BooleanSupplier cancelled, Consumer<String> stages) {
        var builder = new ProcessBuilder(node.toString(), entry.toString(), task.toString(), executionId.toString(), testConfig.toString());
        builder.directory(task.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        for (String key : List.of("PATH", "JAVA_HOME", "HOME", "TMPDIR", "LANG", "LC_ALL", "TERM"))
            if (environment.containsKey(key)) builder.environment().put(key, environment.get(key));
        Process child;
        try { child = builder.start(); }
        catch (IOException error) { return new Result(true, "BUILD_FAILED", null); }
        var invalid = new AtomicBoolean();
        var reader = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "engineering-build-control"); thread.setDaemon(true); return thread;
        });
        var control = reader.submit(() -> {
            try { return read(child.getInputStream(), executionId, stages); }
            catch (Exception error) { invalid.set(true); throw error; }
        });
        long deadline = System.nanoTime() + timeout.toNanos();
        long stopDeadline = Long.MAX_VALUE;
        String stopReason = null;
        try {
            while (!child.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (stopReason == null && (cancelled.getAsBoolean() || invalid.get() || System.nanoTime() >= deadline)) {
                    stopReason = invalid.get() ? "BUILD_INTERRUPTED" : System.nanoTime() >= deadline ? "BUILD_TIMEOUT" : "BUILD_CANCELLED";
                    stopDeadline = System.nanoTime() + settlementTimeout.toNanos();
                    child.getOutputStream().write("CANCEL\n".getBytes(StandardCharsets.US_ASCII));
                    child.getOutputStream().flush();
                }
                if (System.nanoTime() >= stopDeadline) return Result.unknown();
            }
            Result result = control.get(1, TimeUnit.SECONDS);
            if (invalid.get() || result == null || (result.errorCode() == null && child.exitValue() != 0)) return Result.unknown();
            if (!result.settled()) return Result.unknown();
            if (stopReason != null) return new Result(true, stopReason, null);
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); return Result.unknown();
        } catch (Exception error) { return Result.unknown(); }
        finally {
            // Closing stdin tells the supervisor its owner is gone. Only our Process handle may be terminated.
            // https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Process.html
            try { child.getOutputStream().close(); } catch (IOException ignored) { /* Result stays unresolved. */ }
            if (child.isAlive()) child.destroyForcibly();
            reader.shutdownNow();
        }
    }

    private Result read(InputStream input, UUID executionId, Consumer<String> stages) throws IOException {
        var frame = new ByteArrayOutputStream();
        Result completed = null;
        int count = 0, stageIndex = -1;
        for (int value; (value = input.read()) != -1;) {
            if (value != '\n') {
                if (frame.size() >= 2048) throw invalid();
                frame.write(value); continue;
            }
            if (++count > 1000 || completed != null || frame.size() == 0) throw invalid();
            JsonNode message = json.readTree(frame.toByteArray());
            frame.reset();
            if (!message.isObject() || !executionId.toString().equals(message.path("executionId").asText())) throw invalid();
            if ("stage".equals(message.path("type").asText()) && fields(message, Set.of("type", "executionId", "stage"))) {
                int index = STAGES.indexOf(message.path("stage").asText());
                if (index < stageIndex || index < 0) throw invalid();
                stageIndex = index;
                stages.accept(STAGES.get(index));
            } else if ("complete".equals(message.path("type").asText())
                    && fields(message, Set.of("type", "executionId", "settled", "errorCode", "loadedRuntimeId"))
                    && message.get("settled").isBoolean()) {
                String error = message.get("errorCode").isNull() ? null : message.get("errorCode").asText();
                String runtime = message.get("loadedRuntimeId").isNull() ? null : message.get("loadedRuntimeId").asText();
                if (error != null && !ERRORS.contains(error) || error == null && (runtime == null || !runtime.matches("[a-f0-9]{64}"))
                        || error != null && runtime != null || error == null && !message.get("settled").booleanValue()) throw invalid();
                completed = new Result(message.get("settled").booleanValue(), error, runtime);
            } else throw invalid();
        }
        if (frame.size() != 0) throw invalid();
        return completed;
    }
    private boolean fields(JsonNode node, Set<String> expected) {
        return node.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()).equals(expected);
    }
    private IOException invalid() { return new IOException("Invalid build control protocol"); }
}
