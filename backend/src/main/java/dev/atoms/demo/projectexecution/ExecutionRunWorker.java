package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import org.springframework.transaction.PlatformTransactionManager;

/** Holds one runtime for its entire lifetime. A future coordinator supplies its exclusive lease and bounded executor. */
final class ExecutionRunWorker {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ExecutionRunWorker.class);
    @FunctionalInterface interface ToolCapture {
        ExecutionToolchain load(ExecutionRunService.Claim claim) throws IOException;
    }
    @FunctionalInterface interface Launcher {
        ExecutionRunProcess start(ProcessBuilder command, UUID run, UUID execution, OwnedProcess.Budget budget, OwnedProcess.Preflight preflight) throws IOException;
    }
    private final ExecutionSettings settings;
    private final ExecutionRunService service;
    private final ExecutionRunRepository runs;
    private final PlatformTransactionManager transactions;
    private final ExecutionPaths paths;
    private final ToolCapture toolCapture;
    private final ExecutionDatabaseBinding databases;
    private final OwnedProcess.Preflight coordination;
    private final Launcher launcher;
    private final Map<UUID, ExecutionRunProcess> owned = new ConcurrentHashMap<>();

    ExecutionRunWorker(ExecutionSettings settings, ExecutionRunService service, ExecutionRunRepository runs,
            PlatformTransactionManager transactions, ExecutionPaths paths, ExecutionToolchain tools,
            ExecutionDatabaseBinding databases, OwnedProcess.Preflight coordination) {
        this(settings, service, runs, transactions, paths, tools, databases, coordination, ExecutionRunProcess::start);
    }

    /** Retains the original capability for coordinator cleanup; never reconstruct it from persistent IDs or ports. */
    ExecutionRunProcess ownedProcess(UUID run) { return owned.get(run); }
    boolean hasHeldProcesses() { return !owned.isEmpty(); }
    void requestStopOwned() { owned.values().forEach(ExecutionRunProcess::requestStop); }

    /** Capture tools after taking the original claim, using its remaining startup budget, never during application boot. */
    static ExecutionRunWorker nativeWorker(ExecutionSettings settings, ExecutionRunService service, ExecutionRunRepository runs,
            PlatformTransactionManager transactions, ExecutionPaths paths, List<Path> protectedRoots,
            ExecutionDatabaseBinding databases, OwnedProcess.Preflight coordination) {
        var roots = List.copyOf(protectedRoots);
        return new ExecutionRunWorker(settings, service, runs, transactions, paths, claim -> {
            var config = claim.configuration;
            var excluded = new ArrayList<>(roots); excluded.add(config.root());
            return ExecutionToolchain.capture(new ExecutionToolchain.Locations(config.node(), config.npm(), config.java(), config.maven()),
                    excluded, claim.budget);
        }, databases, coordination, ExecutionRunProcess::start);
    }
    /** Package-private fixture seam. No executable, path, launcher or control callback is accepted from a user request. */
    ExecutionRunWorker(ExecutionSettings settings, ExecutionRunService service, ExecutionRunRepository runs,
            PlatformTransactionManager transactions, ExecutionPaths paths, ExecutionToolchain tools,
            ExecutionDatabaseBinding databases, OwnedProcess.Preflight coordination, Launcher launcher) {
        this(settings, service, runs, transactions, paths, claim -> tools, databases, coordination, launcher);
    }

    ExecutionRunWorker(ExecutionSettings settings, ExecutionRunService service, ExecutionRunRepository runs,
            PlatformTransactionManager transactions, ExecutionPaths paths, ToolCapture toolCapture,
            ExecutionDatabaseBinding databases, OwnedProcess.Preflight coordination, Launcher launcher) {
        this.settings = settings; this.service = service; this.runs = runs; this.transactions = transactions;
        this.paths = paths; this.toolCapture = Objects.requireNonNull(toolCapture); this.databases = databases;
        this.coordination = Objects.requireNonNull(coordination); this.launcher = Objects.requireNonNull(launcher);
    }

    /** Blocking lifetime operation; callers submit it to their managed executor, never an HTTP/request transaction. */
    ExecutionRun run(ExecutionRunService.Ticket ticket) throws IOException {
        var claim = service.take(ticket); var run = claim.run; UUID execution = UUID.randomUUID();
        var journal = new ExecutionRunJournal(settings, claim.configuration, runs, transactions, run,
                claim.configuration.root().resolve("runs").resolve(run.id().toString()));
        ExecutionRunProcess process = null; ExecutionRunProcess.Outcome outcome = null;
        boolean attempted = false, committed = false, interrupted = false;
        String failure = null; long started = System.nanoTime();
        try {
            coordination.check(); remaining(claim.budget);
            var tools = toolCapture.load(claim); phase(run.id(), "TOOLS_VERIFIED", started);
            remaining(claim.budget); verifyResources(claim, tools); service.recheck(claim, null);
            phase(run.id(), "INPUTS_RECHECKED", started);
            var command = ExecutionRunCommand.prepare(paths, run.id(), execution, tools, claim.artifact,
                    claim.identity.build(), claim.identity.binding(), claim.budget);
            phase(run.id(), "COMMAND_PREPARED", started);
            var grant = databases.authorizeLaunch(run.projectId(), run.id(), execution, command, claim.budget);
            phase(run.id(), "DATABASE_AUTHORIZED", started);
            var builder = grant.builder(claim.budget); phase(run.id(), "COMMAND_BOUND", started);
            portAvailable(claim.identity.binding().port()); coordination.check(); remaining(claim.budget);
            attempted = true;
            journal.begin(execution); // Commit acknowledgement is required before calling even the launcher adapter.
            committed = true;
            phase(run.id(), "EXECUTION_JOURNALED", started);
            process = launcher.start(builder, run.id(), execution, claim.budget, () -> {
                phase(run.id(), "OWNED_PREFLIGHT_BEGIN", started);
                coordination.check(); verifyResources(claim, tools); service.recheck(claim, execution);
                phase(run.id(), "OWNED_INPUTS_RECHECKED", started);
                grant.verify(claim.budget); portAvailable(claim.identity.binding().port());
                phase(run.id(), "OWNED_PREFLIGHT_COMPLETE", started);
            });
            owned.put(run.id(), process);
            outcome = serve(claim, execution, process, command, journal);
            LOG.debug("application_run_ended run={} outcome={} stopEvidence={}", run.id(), outcome.code(), outcome.stopped() != null);
        } catch (InterruptedException error) {
            interrupted = true; failure = "RUN_CANCELLED";
        } catch (IOException | RuntimeException error) {
            interrupted = Thread.interrupted(); failure = failureCode(error);
        }
        // No completion write is inside the preceding catch: a failed/ambiguous completion is never retried.
        try {
            if (attempted && !committed) throw new IOException("RUN_STATE_UNAVAILABLE");
            if (failure != null && process != null) {
                process.requestStop();
                long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
                // Shutdown may interrupt after the original failure. Keep awaiting the same owner's receipt,
                // without extending the cleanup budget or retrying the completion transaction below.
                while (outcome == null) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) { outcome = unknown(process.output()); break; }
                    try { outcome = process.await(Duration.ofNanos(remaining)); }
                    catch (InterruptedException error) { interrupted = true; }
                    catch (TimeoutException error) { outcome = unknown(process.output()); }
                }
            }
            if (!attempted) return journal.notStarted(failure == null ? "RUN_START_FAILED" : failure);
            if (outcome == null) outcome = unknown(new ExecutionRunProcess.Output(0, 0));
            var settled = journal.finish(execution, outcome, failure);
            if (process != null && outcome.stopped() != null && !settled.executionPending()
                    && outcome.stopped().digest().equals(settled.stoppedSha256())) owned.remove(run.id(), process);
            return settled;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private ExecutionRunProcess.Outcome serve(ExecutionRunService.Claim claim, UUID execution, ExecutionRunProcess process,
            ExecutionRunCommand command, ExecutionRunJournal journal) throws IOException, InterruptedException {
        boolean ready = false, stopping = false; long nextCheck = System.nanoTime();
        var health = new ExecutionHealth();
        while (true) {
            try { return process.await(Duration.ofMillis(250)); }
            catch (TimeoutException stillRunning) { /* Awaiting completion neither stops the process nor fabricates evidence. */ }
            if (System.nanoTime() - nextCheck >= 0) {
                coordination.check();
                if (service.stopRequested(claim, execution)) { stopping = true; process.requestStop(); }
                nextCheck = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            }
            if (!ready && !stopping) {
                var proof = health.probe(process, claim.run.id(), execution, claim.identity.binding(), claim.budget);
                if (proof != null) {
                    coordination.check(); command.verify(claim.budget);
                    journal.ready(execution, claim.identity.binding(), proof, claim.budget); ready = true;
                }
            }
        }
    }
    private void verifyResources(ExecutionRunService.Claim claim, ExecutionToolchain tools) throws IOException {
        if (!paths.checkedRoot().equals(claim.configuration.root())
                || !claim.configuration.java().toRealPath().equals(tools.javaHome().resolve("bin/java")))
            throw new IOException("RUN_CONFIGURATION_CHANGED");
    }
    private static void portAvailable(int port) throws IOException {
        try (var socket = new ServerSocket()) {
            // Ignore closed TCP connections in TIME_WAIT; this does not enable SO_REUSEPORT or identify a live owner.
            // https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/ServerSocket.html#setReuseAddress(boolean)
            socket.setReuseAddress(true); socket.bind(new InetSocketAddress("127.0.0.1", port));
        } catch (IOException error) { throw new IOException("RUN_PORT_IN_USE"); }
    }
    private static void remaining(OwnedProcess.Budget budget) throws IOException {
        var left = budget.remaining();
        if (Thread.currentThread().isInterrupted()) throw new IOException("RUN_CANCELLED");
        if (left == null || left.isZero() || left.isNegative()) throw new IOException("RUN_START_TIMEOUT");
    }
    private static ExecutionRunProcess.Outcome unknown(ExecutionRunProcess.Output output) {
        return new ExecutionRunProcess.Outcome("RUN_PROCESS_UNSETTLED", output, null);
    }
    private static void phase(UUID run, String phase, long started) {
        // Fixed labels and internal UUID only: no argv, private paths, credentials or raw exception messages.
        LOG.debug("application_start_phase run={} phase={} elapsedMs={}", run, phase,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
    private static String failureCode(Exception error) {
        String code = error instanceof ApiException api ? api.code() : Objects.toString(error.getMessage(), "");
        if (Set.of("RUN_PORT_IN_USE", "RUN_HEALTH_INVALID", "RUN_START_TIMEOUT", "RUN_CANCELLED", "RUN_CONFIGURATION_CHANGED",
                "RUN_COORDINATOR_UNAVAILABLE").contains(code)) return code;
        if (Set.of("EXECUTION_COORDINATOR_UNAVAILABLE", "EXECUTION_ENVIRONMENT_UNAVAILABLE").contains(code))
            return "RUN_COORDINATOR_UNAVAILABLE";
        if (code.equals("EXECUTION_TIMEOUT")) return "RUN_START_TIMEOUT";
        if (code.equals("EXECUTION_CANCELLED")) return "RUN_CANCELLED";
        if (code.equals("EXECUTION_DISABLED") || code.equals("EXECUTION_CONFIGURATION_INVALID")) return "RUN_CONFIGURATION_CHANGED";
        if (Set.of("EXECUTION_FORBIDDEN", "PROJECT_NOT_FOUND", "RUN_PROJECT_ARCHIVED").contains(code)) return "RUN_AUTHORIZATION_CHANGED";
        return "RUN_START_FAILED";
    }
}
