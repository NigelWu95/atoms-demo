package dev.atoms.demo.execution;

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
public final class OwnedProcess {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OwnedProcess.class);
    private enum UnsettledReason {
        DESCENDANT_LIMIT, DESCENDANT_IDENTITY_UNAVAILABLE, DESCENDANT_ENUMERATION_FAILED,
        DESCENDANT_IDENTITY_CHANGED, PROCESS_EXIT_TIMEOUT, IO_DRAIN_TIMEOUT, CLEANUP_FAILED
    }
    private enum ObservationStage { NONE, PROCESS_ENUMERATION, CHILD_OBSERVATION }
    private enum ObservationFailure {
        NONE, ACCESS_DENIED, UNSUPPORTED, IO_FAILURE, CONCURRENT_CHANGE, INVALID_STATE, RUNTIME_FAILURE;
        static ObservationFailure classify(RuntimeException error) {
            if (error instanceof SecurityException) return ACCESS_DENIED;
            if (error instanceof UnsupportedOperationException) return UNSUPPORTED;
            if (error instanceof java.io.UncheckedIOException) return IO_FAILURE;
            if (error instanceof java.util.ConcurrentModificationException) return CONCURRENT_CHANGE;
            if (error instanceof IllegalStateException) return INVALID_STATE;
            return RUNTIME_FAILURE;
        }
    }
    private static final Set<String> STREAM_FAILURES = Set.of("AGENT_PROTOCOL_INVALID", "AGENT_CLI_UNSUPPORTED",
            "AGENT_TOOL_FAILED", "AGENT_TOOL_PATH_REJECTED", "AGENT_OUTPUT_LIMIT", "AGENT_MODEL_INCOMPLETE", "AGENT_TURN_LIMIT",
            "AGENT_PROVIDER_AUTH_FAILED", "AGENT_PROVIDER_RATE_LIMITED", "AGENT_PROVIDER_UNAVAILABLE", "AGENT_PROVIDER_ERROR");
    private static final Set<String> PREFLIGHT_FAILURES = Set.of("AGENT_CANCELLED", "AGENT_TIMEOUT",
            "AGENT_CLI_CHANGED", "AGENT_STATE_UNAVAILABLE", "AGENT_STATE_CAPACITY");
    private enum Policy {
        AGENT, EXECUTION;
        String code(String suffix) { return name() + "_" + suffix; }
        boolean streamFailure(String code) {
            return this == AGENT ? STREAM_FAILURES.contains(code)
                    : Set.of("EXECUTION_OUTPUT_LIMIT", "EXECUTION_REPORT_INVALID").contains(code);
        }
        boolean preflightFailure(String code) {
            return this == AGENT ? PREFLIGHT_FAILURES.contains(code)
                    : Set.of("EXECUTION_CANCELLED", "EXECUTION_TIMEOUT", "EXECUTION_TOOL_CHANGED",
                    "EXECUTION_STORAGE_UNAVAILABLE", "EXECUTION_STORAGE_CAPACITY").contains(code);
        }
    }
    private final java.util.function.Function<Process, List<ProcessHandle>> descendants;

    public OwnedProcess() { this(process -> process.descendants().limit(129).toList()); }
    public OwnedProcess(java.util.function.Function<Process, List<ProcessHandle>> descendants) {
        this.descendants = java.util.Objects.requireNonNull(descendants);
    }

    @FunctionalInterface public interface Budget { Duration remaining() throws IOException; }
    @FunctionalInterface public interface Decoder<T> { T read(InputStream stream) throws IOException; }
    @FunctionalInterface public interface Preflight { void check() throws IOException; }
    public record Result<T>(T summary, Stopped stopped) {}
    /** Read-only identity of the actual root process; liveness is not health or stop evidence. */
    public static final class Running {
        private final Process process;
        private final java.util.UUID task, execution;
        private final java.nio.file.Path directory;
        private Running(Process process, java.util.UUID task, java.util.UUID execution, java.nio.file.Path directory) {
            this.process = process; this.task = task; this.execution = execution;
            this.directory = directory.toAbsolutePath().normalize();
        }
        public boolean matches(java.util.UUID task, java.util.UUID execution, java.nio.file.Path directory) {
            return this.task.equals(task) && this.execution.equals(execution)
                    && directory != null && this.directory.equals(directory.toAbsolutePath().normalize());
        }
        public boolean isAlive() { return process.isAlive(); }
    }
    /** Issued only after this owner's processes and I/O have settled; never reconstructed from a PID. */
    public static final class Stopped {
        private final java.util.UUID task, execution;
        private final java.nio.file.Path directory;
        private final String digest;
        private Stopped(java.util.UUID task, java.util.UUID execution, java.nio.file.Path directory) {
            this.task = task; this.execution = execution; this.directory = directory.toAbsolutePath().normalize();
            try {
                this.digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(("atoms-process-stopped-v1\n" + task + "\n" + execution + "\n" + java.util.UUID.randomUUID())
                                .getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        public boolean matches(java.util.UUID task, java.util.UUID execution, java.nio.file.Path directory) {
            return this.task.equals(task) && this.execution.equals(execution)
                    && directory != null && this.directory.equals(directory.toAbsolutePath().normalize());
        }
        public String digest() { return digest; }
    }
    public static final class Failure extends IOException {
        private final Stopped stopped;
        private Failure(String code, Stopped stopped) { super(code); this.stopped = stopped; }
        public Stopped stopped() { return stopped; }
    }

    public <T> Result<T> run(ProcessBuilder builder, String prompt, Budget deadline, BooleanSupplier cancelled,
                             java.util.UUID task, java.util.UUID execution, Decoder<T> decoder) throws IOException {
        return run(builder, prompt, deadline, cancelled, task, execution, decoder, () -> {});
    }

    public <T> Result<T> run(ProcessBuilder builder, String prompt, Budget deadline, BooleanSupplier cancelled,
                             java.util.UUID task, java.util.UUID execution, Decoder<T> decoder, Preflight preflight) throws IOException {
        return run(builder, prompt, deadline, cancelled, task, execution, decoder,
                stream -> { discard(stream); return null; }, preflight, ignored -> {}, Policy.AGENT);
    }

    /** Trusted in-process caller only. It owns argv/environment and bounded decoders; no shell or HTTP API. */
    public <T> Result<T> runCommand(ProcessBuilder builder, Budget deadline, BooleanSupplier cancelled,
                                    java.util.UUID task, java.util.UUID execution, Decoder<T> stdout,
                                    Decoder<Void> stderr, Preflight preflight) throws IOException {
        return runObservedCommand(builder, deadline, cancelled, task, execution, stdout, stderr, preflight, ignored -> {});
    }

    /** Callback runs once after launch and I/O ownership; callback failure still settles owned processes. */
    public <T> Result<T> runObservedCommand(ProcessBuilder builder, Budget deadline, BooleanSupplier cancelled,
                                    java.util.UUID task, java.util.UUID execution, Decoder<T> stdout,
                                    Decoder<Void> stderr, Preflight preflight,
                                    java.util.function.Consumer<Running> started) throws IOException {
        return run(builder, "", deadline, cancelled, task, execution, stdout, stderr, preflight, started, Policy.EXECUTION);
    }

    private <T> Result<T> run(ProcessBuilder builder, String prompt, Budget deadline, BooleanSupplier cancelled,
                              java.util.UUID task, java.util.UUID execution, Decoder<T> decoder,
                              Decoder<Void> errorDecoder, Preflight preflight,
                              java.util.function.Consumer<Running> started, Policy policy) throws IOException {
        java.util.Objects.requireNonNull(task); java.util.Objects.requireNonNull(execution);
        java.util.Objects.requireNonNull(decoder); java.util.Objects.requireNonNull(errorDecoder);
        java.util.Objects.requireNonNull(preflight); java.util.Objects.requireNonNull(started);
        byte[] input = prompt.getBytes(StandardCharsets.UTF_8);
        if (input.length > (1 << 20) || prompt.indexOf('\0') >= 0
                || builder.directory() == null || !builder.directory().isAbsolute()
                || builder.redirectInput() != ProcessBuilder.Redirect.PIPE
                || builder.redirectOutput() != ProcessBuilder.Redirect.PIPE
                || builder.redirectError() != ProcessBuilder.Redirect.PIPE || builder.redirectErrorStream())
            throw new Failure(policy.code("INVOCATION_REJECTED"), null);
        // No launch has occurred: cancellation/preflight failure has positive no-process evidence.
        try { check(deadline, cancelled, policy); preflight.check(); check(deadline, cancelled, policy); }
        catch (IOException | RuntimeException error) {
            String code = error.getMessage();
            throw new Failure(policy.preflightFailure(code == null ? "" : code) ? code : policy.code("PREFLIGHT_FAILED"),
                    new Stopped(task, execution, builder.directory().toPath()));
        }
        final Process process;
        try { process = builder.start(); }
        catch (IOException error) { throw new Failure(policy.code("START_FAILED"), null); }
        var owner = new Owned(process, descendants);
        var streams = Executors.newFixedThreadPool(3, action -> {
            var thread = new Thread(action, policy == Policy.AGENT ? "atoms-agent-io" : "atoms-execution-io");
            thread.setDaemon(true); return thread;
        });
        String failure = null;
        T summary = null;
        boolean interrupted = false, settled;
        try {
            Future<T> stdout = streams.submit(() -> {
                try (var stream = process.getInputStream()) {
                    return decoder.read(stream);
                }
            });
            Future<Void> stderr = streams.submit(() -> {
                try (var stream = process.getErrorStream()) { return errorDecoder.read(stream); }
            });
            Future<Void> stdin = streams.submit(() -> {
                try (var stream = process.getOutputStream()) { stream.write(input); }
                return null;
            });
            started.accept(new Running(process, task, execution, builder.directory().toPath()));
            while (true) {
                check(deadline, cancelled, policy); owner.observe();
                if (owner.uncertain) throw new IOException(policy.code("PROCESS_UNSETTLED"));
                for (var future : List.of(stdout, stderr, stdin)) if (future.isDone()) completed(future, policy);
                if (!process.isAlive()) {
                    if (process.exitValue() != 0) throw new IOException(policy.code("PROCESS_FAILED"));
                    if (owner.childrenAlive()) throw new IOException(policy.code("CHILD_PROCESS_REMAINS"));
                    if (stdout.isDone() && stderr.isDone() && stdin.isDone()) { summary = completed(stdout, policy); break; }
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(20), remaining(deadline, policy).toNanos()));
            }
        } catch (InterruptedException error) {
            interrupted = true; failure = policy.code("CANCELLED");
        } catch (IOException error) {
            failure = error.getMessage();
        } catch (RuntimeException error) {
            failure = policy.code("IO_FAILED");
        } finally {
            interrupted |= Thread.interrupted();
            settled = owner.settle(streams);
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (!settled) {
            LOG.warn("{\"event\":\"owned_process_unsettled\",\"entryPoint\":\"owned_process\",\"requestId\":\"{}\",\"executionId\":\"{}\",\"reason\":\"{}\",\"observationStage\":\"{}\",\"failureKind\":\"{}\",\"observationInterrupted\":{}}",
                    task, execution, owner.reason, owner.observationStage, owner.observationFailure, owner.observationInterrupted);
            throw new Failure(policy.code("PROCESS_UNSETTLED"), null);
        }
        var stopped = new Stopped(task, execution, builder.directory().toPath());
        if (failure != null) throw new Failure(failure, stopped);
        return new Result<>(summary, stopped);
    }

    private static void check(Budget deadline, BooleanSupplier cancelled, Policy policy) throws IOException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new IOException(policy.code("CANCELLED"));
        remaining(deadline, policy);
    }

    private static Duration remaining(Budget deadline, Policy policy) throws IOException {
        if (policy == Policy.AGENT) return deadline.remaining();
        try {
            Duration remaining = deadline.remaining();
            if (remaining == null || remaining.isNegative() || remaining.isZero()) throw new IOException();
            return remaining;
        } catch (IOException | RuntimeException error) { throw new IOException("EXECUTION_TIMEOUT"); }
    }

    private static <T> T completed(Future<T> future, Policy policy) throws IOException, InterruptedException {
        try { return future.get(); } // Only called after isDone: no fresh wait budget.
        catch (ExecutionException error) {
            String code = error.getCause() instanceof IOException ? error.getCause().getMessage() : null;
            throw new IOException(policy.streamFailure(code == null ? "" : code) ? code : policy.code("IO_FAILED"));
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
        private UnsettledReason reason;
        private ObservationStage observationStage = ObservationStage.NONE;
        private ObservationFailure observationFailure = ObservationFailure.NONE;
        private boolean observationInterrupted;
        Owned(Process process, java.util.function.Function<Process, List<ProcessHandle>> descendants) {
            this.process = process; this.descendants = descendants;
        }

        void observe() {
            ObservationStage stage = ObservationStage.PROCESS_ENUMERATION;
            try {
                if (process.isAlive()) {
                    var observed = descendants.apply(process);
                    stage = ObservationStage.CHILD_OBSERVATION;
                    for (var child : observed) {
                        if (children.size() >= 128 && !children.containsKey(child)) { uncertain(UnsettledReason.DESCENDANT_LIMIT); continue; }
                        var start = child.info().startInstant();
                        if (start.isPresent()) children.putIfAbsent(child, start.get());
                        else if (child.isAlive()) uncertain(UnsettledReason.DESCENDANT_IDENTITY_UNAVAILABLE);
                    }
                }
            } catch (RuntimeException error) {
                // Preserve the first failure, without retaining exception text, classes or process info.
                if (reason == null) {
                    observationStage = stage;
                    observationFailure = ObservationFailure.classify(error);
                    observationInterrupted = Thread.currentThread().isInterrupted();
                }
                uncertain(UnsettledReason.DESCENDANT_ENUMERATION_FAILED);
            }
        }
        private void uncertain(UnsettledReason cause) { uncertain = true; failed(cause); }
        private boolean failed(UnsettledReason cause) { if (reason == null) reason = cause; return false; }
        boolean childrenAlive() { return children.keySet().stream().anyMatch(ProcessHandle::isAlive); }

        boolean settle(ExecutorService streams) {
            long start = System.nanoTime(), budget = Duration.ofSeconds(10).toNanos();
            boolean interrupted = false;
            try {
                observe();
                signal(false);
                while (process.isAlive() || childrenAlive()) {
                    if (System.nanoTime() - start >= budget) return failed(UnsettledReason.PROCESS_EXIT_TIMEOUT);
                    observe();
                    if (System.nanoTime() - start > Duration.ofMillis(300).toNanos()) signal(true);
                    try { TimeUnit.MILLISECONDS.sleep(10); }
                    catch (InterruptedException error) { interrupted = true; }
                }
                streams.shutdownNow();
                // Cancellation can arrive repeatedly. Finish observing our own I/O within the original
                // cleanup deadline; interruption alone is neither stopped evidence nor proof of failure.
                while (true) {
                    long remaining = budget - (System.nanoTime() - start);
                    if (remaining <= 0) return failed(UnsettledReason.IO_DRAIN_TIMEOUT);
                    try {
                        if (!streams.awaitTermination(remaining, TimeUnit.NANOSECONDS)) return failed(UnsettledReason.IO_DRAIN_TIMEOUT);
                        return !uncertain;
                    } catch (InterruptedException error) { interrupted = true; }
                }
            } catch (RuntimeException error) { return failed(UnsettledReason.CLEANUP_FAILED); }
            finally { streams.shutdownNow(); if (interrupted) Thread.currentThread().interrupt(); }
        }

        private void signal(boolean force) {
            for (var entry : children.entrySet()) {
                ProcessHandle child = entry.getKey();
                if (!child.isAlive()) continue;
                if (!child.info().startInstant().filter(entry.getValue()::equals).isPresent()) {
                    uncertain(UnsettledReason.DESCENDANT_IDENTITY_CHANGED); continue;
                }
                if (force) child.destroyForcibly(); else child.destroy();
            }
            if (process.isAlive()) { if (force) process.destroyForcibly(); else process.destroy(); }
        }
    }
}
