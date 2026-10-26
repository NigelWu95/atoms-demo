package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded resident workers under the existing coordinator. No lease acquisition, process adoption or durable restart queue. */
final class ExecutionRuntimeDispatcher {
    private final ExecutionSettings settings;
    private final ExecutionRuntimeResources resources;
    private final ExecutionRunRepository runs;
    private final OwnedProcess.Preflight authority;
    private final Runnable failed;
    private final ThreadPoolExecutor executor;
    private final int capacity;
    private final Object lock = new Object();
    private final Map<UUID, Job> jobs = new HashMap<>();
    private int operations;
    private volatile boolean stopping;

    ExecutionRuntimeDispatcher(ExecutionSettings settings, ExecutionRuntimeResources resources, ExecutionRunRepository runs,
            OwnedProcess.Preflight authority, Runnable failed) {
        this.settings = settings; this.resources = resources; this.runs = runs;
        this.authority = authority; this.failed = failed;
        capacity = settings.load().maxRunningProjects();
        executor = new ThreadPoolExecutor(capacity, capacity, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
                task -> new Thread(task, "atoms-runtime-worker"), new ThreadPoolExecutor.AbortPolicy());
    }

    /** Completion means admitted (or still waiting for the predecessor), not READY. Callers never receive a launch ticket. */
    CompletionStage<ExecutionRun> submit(UUID owner, UUID project, ExecutionRun.Request request) throws IOException {
        beginOperation();
        try {
            authority.check();
            if (project == null || !ExecutionRun.valid(request)) throw ExecutionRun.error(422, "RUN_INVALID_INPUT");
            ExecutionRun existing;
            if (request.predecessor() == null) existing = resources.existing(owner, project, request);
            else {
                var step = resources.prepareReplacement(owner, project, request);
                if (step.admission() != null) existing = step.admission().run();
                else if (step.stopping().finishedAt() == null || step.stopping().executionPending()) existing = step.stopping();
                else existing = null;
            }
            synchronized (lock) {
                requireOpen();
                if (existing != null) return CompletableFuture.completedFuture(existing).minimalCompletionStage();
                var current = jobs.get(project);
                if (current != null) {
                    if (current.owner.equals(owner) && current.request.equals(request)) return current.result.minimalCompletionStage();
                    throw ExecutionRun.error(409, current.request.keyHash().equals(request.keyHash()) ? "RUN_IDEMPOTENCY_CONFLICT" : "RUN_PROJECT_BUSY");
                }
                if (jobs.size() >= Math.min(capacity, settings.load().maxRunningProjects())) throw ExecutionRun.error(429, "RUN_CAPACITY_FULL");
                var job = new Job(owner, project, request);
                jobs.put(project, job); // Present before any admission can commit, including the receipt/handoff window.
                try { executor.execute(job); }
                catch (RuntimeException error) { jobs.remove(project, job); throw unavailable(); }
                return job.result.minimalCompletionStage();
            }
        } finally { endOperation(); }
    }

    ExecutionRun stop(UUID owner, UUID project, UUID run) throws IOException {
        beginOperation();
        try { authority.check(); return resources.stop(owner, project, run); }
        finally { endOperation(); }
    }

    /** Only the short bounded metadata read shares the ownership lock; no artifact/tool/DB preflight runs under it. */
    void checkOwnedRuns() throws IOException {
        outsideTransaction();
        synchronized (lock) {
            var rows = runs.unfinished();
            if (rows.size() > 4) throw unavailable();
            for (var row : rows) {
                var job = jobs.get(row.projectId());
                if (job == null || !job.matches(row) || row.status().equals("NEEDS_RECONCILIATION")) throw unavailable();
            }
        }
    }

    void requestStop() {
        List<Runnable> waiting;
        synchronized (lock) {
            if (stopping) return;
            stopping = true; waiting = executor.shutdownNow();
            for (var queued : waiting) if (queued instanceof Job job) jobs.remove(job.project, job);
            lock.notifyAll();
        }
        // A dropped queue entry has never entered admission. An executing job always consumes any ticket it receives.
        for (var queued : waiting) if (queued instanceof Job job) job.result.completeExceptionally(unavailable());
        resources.requestStopOwned();
    }

    /** Metadata checks only: never reconstructs a process receipt or adopts a persisted row. */
    void checkStoppedFailure(ExecutionRun failed) throws IOException {
        synchronized (lock) {
            var job = failed == null ? null : jobs.get(failed.projectId());
            if (!stopping || !executor.isTerminated() || operations != 0 || resources.hasHeldProcesses()
                    || job == null || job.stoppedFailure == null || !job.stoppedFailure.equals(failed)) throw unavailable();
        }
    }

    void acceptStoppedFailure(ExecutionRun failed, ExecutionRunReconciliationRepository.Audit audit) throws IOException {
        outsideTransaction();
        synchronized (lock) {
            if (failed == null || audit == null) throw unavailable();
            var job = jobs.get(failed.projectId());
            if (job == null) return; // Already released or never ours. Never adopt it for a replay.
            checkStoppedFailure(failed);
            if (!audit.runId().equals(failed.id()) || !audit.projectId().equals(failed.projectId())
                    || !audit.approvedBy().equals(failed.ownerId()) || !audit.executionId().equals(failed.executionId())
                    || !audit.stoppedSha256().equals(failed.stoppedSha256())) throw unavailable();
            jobs.remove(failed.projectId(), job); lock.notifyAll();
        }
    }

    boolean isTerminated() {
        synchronized (lock) {
            return executor.isTerminated() && operations == 0 && jobs.isEmpty() && !resources.hasHeldProcesses();
        }
    }
    boolean awaitTermination(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        if (!executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) return false;
        synchronized (lock) {
            while (operations != 0) {
                long left = deadline - System.nanoTime(); if (left <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(lock, left);
            }
            return isTerminated();
        }
    }

    private void beginOperation() throws IOException {
        outsideTransaction();
        synchronized (lock) { requireOpen(); operations++; }
    }
    private void endOperation() { synchronized (lock) { operations--; lock.notifyAll(); } }
    private void requireOpen() throws IOException { if (stopping) throw unavailable(); }
    private static void outsideTransaction() throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
    }
    private static IOException unavailable() { return new IOException("RUN_DISPATCH_UNAVAILABLE"); }

    private final class Job implements Runnable {
        final UUID owner, project;
        final ExecutionRun.Request request;
        final CompletableFuture<ExecutionRun> result = new CompletableFuture<>();
        ExecutionRun admitted, stoppedFailure;
        Job(UUID owner, UUID project, ExecutionRun.Request request) { this.owner = owner; this.project = project; this.request = request; }
        boolean matches(ExecutionRun row) {
            return owner.equals(row.ownerId()) && project.equals(row.projectId()) && request.build().equals(row.buildId())
                    && request.binding().equals(row.bindingId()) && Objects.equals(request.review(), row.migrationReviewId())
                    && request.keyHash().equals(row.keySha256()) && request.bodyHash().equals(row.requestSha256())
                    && (admitted != null ? admitted.id().equals(row.id())
                        : row.status().equals("STARTING") && row.executionId() == null && !row.executionPending());
        }
        private boolean knownStoppedFailure(ExecutionRun ended) {
            return matches(ended) && admitted.sourceVersionId().equals(ended.sourceVersionId())
                    && admitted.treeSha256().equals(ended.treeSha256()) && admitted.jarSha256().equals(ended.jarSha256())
                    && admitted.manifestSha256().equals(ended.manifestSha256())
                    && admitted.bindingIdentitySha256().equals(ended.bindingIdentitySha256()) && admitted.createdAt().equals(ended.createdAt())
                    && ended.status().equals("NEEDS_RECONCILIATION") && !ended.executionPending() && ended.executionId() != null
                    && ended.stoppedSha256() != null && ended.stoppedSha256().matches("[a-f0-9]{64}")
                    && ended.errorCode() != null && ended.readyAt() == null && ended.finishedAt() == null;
        }
        @Override public void run() {
            boolean uncertain = false, hasTicket = false;
            try {
                requireOpen(); authority.check();
                var admission = resources.submit(owner, project, request);
                hasTicket = admission.ticket() != null;
                synchronized (lock) { admitted = admission.run(); }
                result.complete(admission.run());
                if (hasTicket) {
                    // Even shutdown after the commit must hand the original ticket to the worker for notStarted/stop settlement.
                    var ended = resources.execute(admission.ticket());
                    synchronized (lock) { if (knownStoppedFailure(ended)) stoppedFailure = ended; }
                    uncertain = !ended.id().equals(admission.run().id()) || ended.finishedAt() == null || ended.executionPending();
                }
            } catch (Throwable error) {
                uncertain = hasTicket;
                result.completeExceptionally(error instanceof IOException || error instanceof ApiException ? error : unavailable());
            } finally {
                boolean interrupted = Thread.interrupted();
                try {
                    synchronized (lock) {
                        try {
                            var rows = runs.unfinished();
                            if (rows.size() > 4 || rows.stream().anyMatch(row -> row.projectId().equals(project))) uncertain = true;
                            else if (!uncertain) jobs.remove(project, this);
                        } catch (RuntimeException unreadable) { uncertain = true; }
                        lock.notifyAll();
                    }
                    if (uncertain) { try { requestStop(); } finally { failed.run(); } }
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
    }
}
