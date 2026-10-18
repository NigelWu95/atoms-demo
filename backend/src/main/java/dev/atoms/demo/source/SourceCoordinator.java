package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** One owner/worker. Startup reconciles persisted work; it never guesses/replays lost in-memory input. */
@Component
@ConditionalOnProperty(name="atoms.source.enabled", havingValue="true")
final class SourceCoordinator implements SmartLifecycle, AutoCloseable {
    record Submission(SourceOperation operation, CompletableFuture<SourceOperation> completion) {}
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SourceCoordinator.class);
    private final ProjectSourceService service;
    private final SourceRepository repository;
    private final ProjectSourceEnvironment environment;
    private final DataSource database;
    private final java.util.function.Consumer<SourceWorker.Stage> observer;
    private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(), closed = new AtomicBoolean();
    private ThreadPoolExecutor executor;
    private volatile SourceCoordinatorLease lease;

    @org.springframework.beans.factory.annotation.Autowired
    SourceCoordinator(ProjectSourceService service, SourceRepository repository, ProjectSourceEnvironment environment, DataSource database) {
        this(service, repository, environment, database, stage -> {});
    }
    SourceCoordinator(ProjectSourceService service, SourceRepository repository, ProjectSourceEnvironment environment, DataSource database,
            java.util.function.Consumer<SourceWorker.Stage> observer) {
        this.service = service; this.repository = repository; this.environment = environment; this.database = database;
        this.observer = observer;
    }

    @Override public synchronized void start() {
        if (running.get() || closed.get() || !environment.enabled()) return;
        try {
            lease = new SourceCoordinatorLease(database);
            environment.settings();
            var recovery = new SourceRecovery(service, repository, environment);
            for (var operation : repository.pending()) {
                if (!lease.healthy()) throw unavailable();
                var result = recovery.reconcile(operation.projectId(), operation.id());
                LOG.info("{\"event\":\"source_operation_reconciled\",\"entryPoint\":\"source_startup\",\"requestId\":\"{}\",\"projectId\":\"{}\",\"status\":\"{}\"}",
                        operation.id(), operation.projectId(), result.status());
            }
            executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
                    task -> new Thread(task, "atoms-source-worker"), new ThreadPoolExecutor.AbortPolicy());
            running.set(true);
        } catch (Exception error) {
            closed.set(true); running.set(false); closeLease();
            LOG.warn("{\"event\":\"source_coordinator_unavailable\",\"entryPoint\":\"source_startup\",\"requestId\":\"{}\",\"errorCode\":\"SOURCE_COORDINATOR_UNAVAILABLE\"}", UUID.randomUUID());
        }
    }

    synchronized Submission initialize(UUID owner, UUID project, SourceTree input, String key) {
        requireRunning();
        var operation = new SourceInitializer(service).request(owner, project, input, key);
        return enqueue(operation, input);
    }

    synchronized Submission submit(UUID owner, UUID project, UUID operationId, SourceTree input) {
        requireRunning();
        return enqueue(service.operation(owner, project, operationId), input);
    }

    private Submission enqueue(SourceOperation operation, SourceTree input) {
        var previous = jobs.get(operation.id());
        if (previous != null) return new Submission(operation, previous.completion);
        if (!operation.status().equals("PREPARING") || operation.executionId() != null || input == null)
            return new Submission(operation, CompletableFuture.completedFuture(operation));
        if (!operation.candidateSha256().equals(input.sha256())) throw ProjectSourceService.conflict("SOURCE_INPUT_CHANGED");
        var job = new Job(operation, input);
        jobs.put(operation.id(), job);
        try { executor.execute(job); }
        catch (java.util.concurrent.RejectedExecutionException error) {
            jobs.remove(operation.id(), job);
            job.finishQueued("SOURCE_CAPACITY_EXCEEDED");
        }
        return new Submission(operation, job.completion);
    }

    private void requireRunning() {
        var ownership = lease;
        if (!running.get() || closed.get() || ownership == null || !ownership.healthy()) {
            running.set(false);
            throw unavailable();
        }
        environment.settings();
    }
    @Override public boolean isRunning() { return running.get(); }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public void stop() { close(); }
    @Override public void close() {
        ThreadPoolExecutor worker;
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            running.set(false); worker = executor;
        }
        if (worker == null) { closeLease(); return; }
        for (var queued : worker.shutdownNow()) if (queued instanceof Job job) job.finishQueued("SOURCE_INTERRUPTED");
        boolean settled = false;
        try { settled = worker.awaitTermination(30, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        if (settled) closeLease();
        else {
            LOG.error("{\"event\":\"source_shutdown_unsettled\",\"entryPoint\":\"source_shutdown\",\"requestId\":\"{}\",\"errorCode\":\"SOURCE_EXECUTION_UNCERTAIN\"}", UUID.randomUUID());
            throw unavailable();
        }
    }

    private synchronized void closeLease() {
        if (lease == null) return;
        try { lease.close(); }
        catch (java.sql.SQLException error) {
            LOG.warn("{\"event\":\"source_lease_unavailable\",\"entryPoint\":\"source_shutdown\",\"requestId\":\"{}\",\"errorCode\":\"SOURCE_COORDINATOR_UNAVAILABLE\"}", UUID.randomUUID());
        } finally { lease = null; }
    }
    private static ApiException unavailable() { return new ApiException(503, "SOURCE_COORDINATOR_UNAVAILABLE", "源码任务调度不可用，请检查维护者配置或待核对操作。"); }

    private final class Job implements Runnable {
        final SourceOperation operation;
        final SourceTree tree;
        final CompletableFuture<SourceOperation> completion = new CompletableFuture<>();
        Job(SourceOperation operation, SourceTree tree) { this.operation = operation; this.tree = tree; }
        @Override public void run() {
            long started = System.nanoTime();
            try {
                requireRunning();
                var result = new SourceWorker(service, repository, environment, stage -> { requireRunning(); observer.accept(stage); }).execute(operation, tree);
                completion.complete(result);
                LOG.info("{\"event\":\"source_operation_finished\",\"entryPoint\":\"source_coordinator\",\"requestId\":\"{}\",\"projectId\":\"{}\",\"status\":\"{}\",\"errorCode\":\"{}\",\"durationMs\":{}}",
                        operation.id(), operation.projectId(), result.status(), result.errorCode() == null ? "NONE" : result.errorCode(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            } catch (Throwable error) {
                running.set(false);
                completion.completeExceptionally(unavailable());
                LOG.error("{\"event\":\"source_operation_uncertain\",\"entryPoint\":\"source_coordinator\",\"requestId\":\"{}\",\"projectId\":\"{}\",\"errorCode\":\"SOURCE_EXECUTION_UNCERTAIN\"}", operation.id(), operation.projectId());
            } finally {
                jobs.remove(operation.id(), this);
                if (closed.get()) closeLease();
            }
        }
        void finishQueued(String code) {
            try { completion.complete(service.fail(operation.projectId(), operation.id(), code, false)); }
            catch (RuntimeException error) { completion.completeExceptionally(unavailable()); }
            finally { jobs.remove(operation.id(), this); }
        }
    }
}
