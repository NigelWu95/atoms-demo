package dev.atoms.demo.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Owns only the process it starts and observed descendants; not an OS process sandbox. */
final class AgentProcess {
    private static final Set<String> STREAM_FAILURES = Set.of("AGENT_PROTOCOL_INVALID", "AGENT_CLI_UNSUPPORTED",
            "AGENT_TOOL_FAILED", "AGENT_TOOL_PATH_REJECTED", "AGENT_OUTPUT_LIMIT", "AGENT_MODEL_INCOMPLETE");
    private final java.util.function.Function<Process, List<ProcessHandle>> descendants;

    AgentProcess() { this(process -> process.descendants().limit(129).toList()); }
    AgentProcess(java.util.function.Function<Process, List<ProcessHandle>> descendants) {
        this.descendants = java.util.Objects.requireNonNull(descendants);
    }

    record Result(AgentStreamDecoder.Summary summary, Stopped stopped) {}
    static final class Stopped { private Stopped() {} }
    static final class Failure extends IOException {
        private final Stopped stopped;
        private Failure(String code, Stopped stopped) { super(code); this.stopped = stopped; }
        Stopped stopped() { return stopped; }
    }

    Result run(ProcessBuilder builder, String prompt, AgentDeadline deadline, BooleanSupplier cancelled) throws IOException {
        byte[] input = prompt.getBytes(StandardCharsets.UTF_8);
        if (input.length == 0 || input.length > (1 << 20) || prompt.indexOf('\0') >= 0
                || builder.directory() == null || !builder.directory().isAbsolute()
                || builder.redirectInput() != ProcessBuilder.Redirect.PIPE
                || builder.redirectOutput() != ProcessBuilder.Redirect.PIPE
                || builder.redirectError() != ProcessBuilder.Redirect.PIPE || builder.redirectErrorStream())
            throw new Failure("AGENT_INVOCATION_REJECTED", null);
        check(deadline, cancelled);
        final Process process;
        try { process = builder.start(); }
        catch (IOException error) { throw new Failure("AGENT_START_FAILED", null); }
        var owner = new Owned(process, descendants);
        var streams = Executors.newFixedThreadPool(3, action -> {
            var thread = new Thread(action, "atoms-agent-io"); thread.setDaemon(true); return thread;
        });
        String failure = null;
        AgentStreamDecoder.Summary summary = null;
        boolean interrupted = false, settled;
        try {
            Future<AgentStreamDecoder.Summary> stdout = streams.submit(() -> {
                try (var stream = process.getInputStream()) {
                    return new AgentStreamDecoder(builder.directory().toPath()).read(stream);
                }
            });
            Future<Void> stderr = streams.submit(() -> { discard(process.getErrorStream()); return null; });
            Future<Void> stdin = streams.submit(() -> {
                try (var stream = process.getOutputStream()) { stream.write(input); }
                return null;
            });
            while (true) {
                check(deadline, cancelled); owner.observe();
                if (owner.uncertain) throw new IOException("AGENT_PROCESS_UNSETTLED");
                for (var future : List.of(stdout, stderr, stdin)) if (future.isDone()) completed(future);
                if (!process.isAlive()) {
                    if (process.exitValue() != 0) throw new IOException("AGENT_PROCESS_FAILED");
                    if (owner.childrenAlive()) throw new IOException("AGENT_CHILD_PROCESS_REMAINS");
                    if (stdout.isDone() && stderr.isDone() && stdin.isDone()) { summary = completed(stdout); break; }
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(20), deadline.remaining().toNanos()));
            }
        } catch (InterruptedException error) {
            interrupted = true; failure = "AGENT_CANCELLED";
        } catch (IOException error) {
            failure = error.getMessage();
        } catch (RuntimeException error) {
            failure = "AGENT_IO_FAILED";
        } finally {
            interrupted |= Thread.interrupted();
            settled = owner.settle(streams);
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (!settled) throw new Failure("AGENT_PROCESS_UNSETTLED", null);
        var stopped = new Stopped();
        if (failure != null) throw new Failure(failure, stopped);
        return new Result(summary, stopped);
    }

    private static void check(AgentDeadline deadline, BooleanSupplier cancelled) throws IOException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new IOException("AGENT_CANCELLED");
        deadline.remaining();
    }

    private static <T> T completed(Future<T> future) throws IOException, InterruptedException {
        try { return future.get(); } // Only called after isDone: no fresh wait budget.
        catch (ExecutionException error) {
            String code = error.getCause() instanceof IOException ? error.getCause().getMessage() : null;
            throw new IOException(STREAM_FAILURES.contains(code == null ? "" : code) ? code : "AGENT_IO_FAILED");
        }
    }

    private static void discard(InputStream stream) throws IOException {
        try (stream) {
            long total = 0; int count; byte[] buffer = new byte[8192];
            while ((count = stream.read(buffer)) != -1) if ((total += count) > (256 << 10))
                throw new IOException("AGENT_OUTPUT_LIMIT");
        }
    }

    private static final class Owned {
        private final Process process;
        private final java.util.function.Function<Process, List<ProcessHandle>> descendants;
        private final Map<ProcessHandle, java.time.Instant> children = new LinkedHashMap<>();
        private boolean uncertain;
        Owned(Process process, java.util.function.Function<Process, List<ProcessHandle>> descendants) {
            this.process = process; this.descendants = descendants;
        }

        void observe() {
            try {
                if (process.isAlive()) descendants.apply(process).forEach(child -> {
                    if (children.size() >= 128 && !children.containsKey(child)) { uncertain = true; return; }
                    var start = child.info().startInstant();
                    if (start.isPresent()) children.putIfAbsent(child, start.get());
                    else if (child.isAlive()) uncertain = true;
                });
            } catch (RuntimeException error) { uncertain = true; }
        }
        boolean childrenAlive() { return children.keySet().stream().anyMatch(ProcessHandle::isAlive); }

        boolean settle(ExecutorService streams) {
            long start = System.nanoTime(), budget = Duration.ofSeconds(10).toNanos();
            boolean interrupted = false;
            try {
                observe();
                signal(false);
                while (process.isAlive() || childrenAlive()) {
                    if (System.nanoTime() - start >= budget) return false;
                    observe();
                    if (System.nanoTime() - start > Duration.ofMillis(300).toNanos()) signal(true);
                    try { TimeUnit.MILLISECONDS.sleep(10); }
                    catch (InterruptedException error) { interrupted = true; }
                }
                streams.shutdownNow();
                long remaining = budget - (System.nanoTime() - start);
                if (remaining <= 0) return false;
                try { return streams.awaitTermination(remaining, TimeUnit.NANOSECONDS) && !uncertain; }
                catch (InterruptedException error) { interrupted = true; return false; }
            } catch (RuntimeException error) { return false; }
            finally { streams.shutdownNow(); if (interrupted) Thread.currentThread().interrupt(); }
        }

        private void signal(boolean force) {
            for (var entry : children.entrySet()) {
                ProcessHandle child = entry.getKey();
                if (!child.isAlive()) continue;
                if (!child.info().startInstant().filter(entry.getValue()::equals).isPresent()) { uncertain = true; continue; }
                if (force) child.destroyForcibly(); else child.destroy();
            }
            if (process.isAlive()) { if (force) process.destroyForcibly(); else process.destroy(); }
        }
    }
}
