package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal resident-process owner. Admission, durable intent and HTTP health validation belong to the worker. */
final class ExecutionRunProcess {
    private enum Phase { STARTING, SERVING, STOP_REQUESTED, TIMED_OUT, FINISHED }
    record Output(long stdoutBytes, long stderrBytes) {}
    record Outcome(String code, Output output, OwnedProcess.Stopped stopped) {}
    private final UUID run, execution;
    private final Path directory;
    private final OwnedProcess.Budget startup;
    private final AtomicLong stdout = new AtomicLong(), stderr = new AtomicLong();
    private final CompletableFuture<Outcome> completion = new CompletableFuture<>();
    private Phase phase = Phase.STARTING;
    private OwnedProcess.Running running;
    private ExecutionBindingRepository.Target confirmedTarget;

    private ExecutionRunProcess(ProcessBuilder builder, UUID run, UUID execution, OwnedProcess.Budget startup) {
        this.run = Objects.requireNonNull(run); this.execution = Objects.requireNonNull(execution);
        this.startup = Objects.requireNonNull(startup); directory = builder.directory().toPath().normalize();
    }

    static ExecutionRunProcess start(ProcessBuilder fixedCommand, UUID run, UUID execution,
                                     OwnedProcess.Budget startup, OwnedProcess.Preflight preflight) {
        return start(new OwnedProcess(), fixedCommand, run, execution, startup, preflight);
    }
    static ExecutionRunProcess start(OwnedProcess owner, ProcessBuilder fixedCommand, UUID run, UUID execution,
                                     OwnedProcess.Budget startup, OwnedProcess.Preflight preflight) {
        Objects.requireNonNull(owner); Objects.requireNonNull(preflight);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("EXECUTION_TRANSACTION_ACTIVE");
        if (fixedCommand.directory() == null || !fixedCommand.directory().isAbsolute()
                || fixedCommand.redirectInput() != ProcessBuilder.Redirect.PIPE
                || fixedCommand.redirectOutput() != ProcessBuilder.Redirect.PIPE
                || fixedCommand.redirectError() != ProcessBuilder.Redirect.PIPE || fixedCommand.redirectErrorStream())
            throw new IllegalArgumentException("RUN_INVOCATION_REJECTED");
        // Freeze the caller's builder before crossing the thread boundary; no inherited host environment.
        var builder = new ProcessBuilder(List.copyOf(fixedCommand.command())).directory(fixedCommand.directory());
        builder.environment().clear(); builder.environment().putAll(Map.copyOf(fixedCommand.environment()));
        var result = new ExecutionRunProcess(builder, run, execution, startup);
        var thread = new Thread(() -> result.own(owner, builder, preflight), "atoms-run-owner");
        thread.setDaemon(true); thread.start(); return result;
    }

    synchronized boolean isAlive() { return running != null && running.isAlive(); }
    synchronized boolean isServing() { return phase == Phase.SERVING && isAlive(); }
    /** Read-only identity check; unlike confirmReady this cannot promote a starting process. */
    synchronized boolean serves(UUID expectedRun, UUID expectedExecution) {
        return run.equals(expectedRun) && execution.equals(expectedExecution) && phase == Phase.SERVING
                && running != null && running.matches(run, execution, directory) && running.isAlive();
    }

    /** Caller must first validate the fixed-target HTTP response. These IDs alone do not prove health. */
    synchronized boolean confirmReady(UUID echoedRun, UUID echoedExecution) {
        if (!run.equals(echoedRun) || !execution.equals(echoedExecution)
                || running == null || !running.matches(run, execution, directory) || !running.isAlive()) return false;
        if (phase == Phase.SERVING) return true;
        if (phase != Phase.STARTING) return false;
        try { remaining(); } catch (IOException error) { return false; }
        phase = Phase.SERVING; return true;
    }

    /** Only the fixed-target HTTP health probe supplies a binding; it cannot be changed for this process. */
    synchronized boolean confirmReady(UUID echoedRun, UUID echoedExecution, ExecutionBindingRepository.Target target) {
        if (target == null || confirmedTarget != null && !confirmedTarget.equals(target)
                || !confirmReady(echoedRun, echoedExecution)) return false;
        confirmedTarget = target; return true;
    }

    /** Package-only: no caller-supplied port and no recovery from a database READY row. */
    synchronized ExecutionBindingRepository.Target confirmedTarget(UUID expectedRun, UUID expectedExecution) throws IOException {
        if (confirmedTarget == null || !serves(expectedRun, expectedExecution)) throw new IOException("RUN_TARGET_UNAVAILABLE");
        return confirmedTarget;
    }

    synchronized void requestStop() {
        if (phase == Phase.STARTING || phase == Phase.SERVING) phase = Phase.STOP_REQUESTED;
    }
    Output output() { return new Output(stdout.get(), stderr.get()); }
    Outcome await(Duration wait) throws InterruptedException, TimeoutException {
        if (wait == null || wait.isNegative() || wait.isZero() || wait.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("RUN_INVALID_WAIT");
        try { return completion.get(wait.toNanos(), TimeUnit.NANOSECONDS); }
        catch (ExecutionException impossible) { throw new IllegalStateException("RUN_OUTCOME_UNAVAILABLE"); }
    }

    private synchronized boolean cancelled() { return phase == Phase.STOP_REQUESTED; }
    private synchronized Duration remaining() throws IOException {
        // Stop can arrive after OwnedProcess's cancellation check but before its next budget/poll check.
        // Do not reactivate the startup deadline in that interval; the next cancellation check settles it.
        if (phase == Phase.SERVING || phase == Phase.STOP_REQUESTED) return Duration.ofSeconds(1);
        if (phase == Phase.TIMED_OUT) throw new IOException("EXECUTION_TIMEOUT");
        try {
            Duration left = startup.remaining();
            if (left == null || left.isNegative() || left.isZero()) throw new IOException();
            return left;
        } catch (IOException | RuntimeException error) {
            if (phase == Phase.STARTING) phase = Phase.TIMED_OUT;
            throw new IOException("EXECUTION_TIMEOUT");
        }
    }

    private void own(OwnedProcess owner, ProcessBuilder builder, OwnedProcess.Preflight preflight) {
        String code; OwnedProcess.Stopped stopped = null;
        try {
            var result = owner.runObservedCommand(builder, this::remaining, this::cancelled, run, execution,
                    stream -> { drain(stream, stdout); return null; }, stream -> { drain(stream, stderr); return null; },
                    preflight, identity -> { synchronized (this) { running = identity; } });
            code = "RUN_EXITED"; stopped = result.stopped();
        } catch (OwnedProcess.Failure error) {
            code = switch (error.getMessage()) {
                case "EXECUTION_CANCELLED" -> "RUN_STOPPED";
                case "EXECUTION_TIMEOUT" -> "RUN_START_TIMEOUT";
                case "EXECUTION_START_FAILED" -> "RUN_START_FAILED";
                case "EXECUTION_PROCESS_FAILED" -> "RUN_PROCESS_FAILED";
                case "EXECUTION_CHILD_PROCESS_REMAINS" -> "RUN_CHILD_PROCESS_REMAINS";
                case "EXECUTION_PROCESS_UNSETTLED" -> "RUN_PROCESS_UNSETTLED";
                case "EXECUTION_PREFLIGHT_FAILED" -> "RUN_PREFLIGHT_FAILED";
                default -> "RUN_EXECUTION_FAILED";
            };
            stopped = error.stopped();
        } catch (IOException | RuntimeException error) {
            code = "RUN_EXECUTION_FAILED"; // No fabricated receipt when ownership was not confirmed.
        }
        synchronized (this) { phase = Phase.FINISHED; }
        completion.complete(new Outcome(code, output(), stopped));
    }

    private static void drain(InputStream stream, AtomicLong bytes) throws IOException {
        byte[] buffer = new byte[8192]; int count;
        while ((count = stream.read(buffer)) != -1) {
            final int read = count;
            bytes.updateAndGet(total -> total > Long.MAX_VALUE - read ? Long.MAX_VALUE : total + read);
        }
    }
}
