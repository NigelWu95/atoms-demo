package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.source.ProjectSourceReadGateway;
import dev.atoms.demo.source.ProjectSourceEditGateway;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One opt-in dispatcher with independent workers; source lifecycle starts first and stops last. */
@Component
@ConditionalOnProperty(name = "atoms.project-execution.enabled", havingValue = "true")
final class ExecutionCoordinator implements SmartLifecycle, AutoCloseable {
    interface WorkerResources extends AutoCloseable {
        void check() throws IOException;
        ExecutionBuild run(ExecutionBuild build, BooleanSupplier cancelled, OwnedProcess.Preflight coordination) throws IOException;
        default DependencyPreparationRepository.Task runPreparation(DependencyPreparationRepository.Task task,
                BooleanSupplier cancelled, OwnedProcess.Preflight coordination) throws IOException {
            throw new IOException("EXECUTION_PREPARATION_UNAVAILABLE");
        }
        /** Optional runtime resources; attachment alone never starts a task or claims an old run. */
        default ExecutionRuntimeResources runtime(OwnedProcess.Preflight coordination) throws IOException { return null; }
        @Override void close() throws IOException;
    }
    /** Trusted resource assembly only; never supplied by a request or generated source. */
    @FunctionalInterface interface Factory { WorkerResources open() throws IOException; }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ExecutionCoordinator.class);
    private final ExecutionSettings settings;
    private final ExecutionBuildRepository builds;
    private final ExecutionRunRepository runs;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final DataSource database;
    private final BooleanSupplier sourceReady;
    private final Factory factory;
    private final DependencyPreparationRepository preparations;
    private final DependencyPreparationService preparationService;
    private final ProjectSourceEditGateway edits;
    private final Object admission = new Object(), finishing = new Object();
    private final AtomicBoolean running = new AtomicBoolean(), stopping = new AtomicBoolean();
    private final Map<UUID, Job> active = new ConcurrentHashMap<>();
    private volatile ThreadPoolExecutor executor;
    private volatile ScheduledExecutorService monitor;
    private volatile ExecutionCoordinatorLease lease;
    private volatile WorkerResources resources;
    private volatile ExecutionRuntimeDispatcher runtime;
    private ExecutionBuildQueue queue;
    private DependencyPreparationQueue preparationQueue;
    private int concurrency;
    private boolean activated, released;

    @Autowired
    ExecutionCoordinator(ExecutionSettings settings, ExecutionBuildRepository builds, JdbcTemplate jdbc,
                         PlatformTransactionManager transactions, DataSource database, ProjectSourceReadGateway source,
                         ExecutionEnvironment environment, DependencyPreparationService preparations, ProjectSourceEditGateway edits) {
        this(settings, builds, jdbc, transactions, database, source::readyForExecution, environment, preparations, edits);
    }

    ExecutionCoordinator(ExecutionSettings settings, ExecutionBuildRepository builds, JdbcTemplate jdbc,
                         PlatformTransactionManager transactions, DataSource database, BooleanSupplier sourceReady,
                         Factory factory) {
        // Existing build-only test adapters must reject any preparation backlog rather than silently ignore it.
        this(settings, builds, jdbc, transactions, database, sourceReady, factory, null, null);
    }

    ExecutionCoordinator(ExecutionSettings settings, ExecutionBuildRepository builds, JdbcTemplate jdbc,
                         PlatformTransactionManager transactions, DataSource database, BooleanSupplier sourceReady,
                         Factory factory, DependencyPreparationService preparationService, ProjectSourceEditGateway edits) {
        if ((preparationService == null) != (edits == null)) throw new IllegalArgumentException("Incomplete preparation wiring");
        this.settings = settings; this.builds = builds; this.runs = new ExecutionRunRepository(jdbc); this.jdbc = jdbc; this.transactions = transactions;
        this.database = database; this.sourceReady = sourceReady; this.factory = factory;
        this.preparations = new DependencyPreparationRepository(jdbc); this.preparationService = preparationService; this.edits = edits;
    }

    @Override public void start() {
        outsideTransaction();
        synchronized (admission) {
            if (running.get() || stopping.get()) return;
            try {
                if (!settings.enabled()) return;
                var initial = settings.load();
                concurrency = initial.maxConcurrentBuilds();
                if (!sourceReady.getAsBoolean()) throw unavailable();
                lease = new ExecutionCoordinatorLease(database);
                resources = Objects.requireNonNull(factory.open());
                resources.check(); requireLease();
                if (!sourceReady.getAsBoolean() || !initial.equals(settings.load())) throw unavailable();
                var runRecovery = new ExecutionRunRecovery(settings, runs, transactions, lease, sourceReady);
                var abandonedRuns = runs.unfinished();
                if (abandonedRuns.size() > 4) throw unavailable();
                for (var abandoned : abandonedRuns) {
                    resources.check(); runRecovery.reconcile(abandoned);
                }
                var recovery = new ExecutionRecovery(settings, builds, jdbc, transactions, lease, sourceReady);
                for (var abandoned : builds.inFlight()) {
                    // Filesystem checks remain outside the recovery transaction; no worker exists yet.
                    resources.check();
                    recovery.reconcile(abandoned);
                }
                if (preparationService != null) {
                    var preparationRecovery = new DependencyPreparationRecovery(settings, preparations, preparationService,
                            edits, jdbc, transactions, lease, sourceReady);
                    for (var abandoned : preparations.unfinished()) {
                        resources.check(); preparationRecovery.reconcile(abandoned);
                    }
                    preparationQueue = new DependencyPreparationQueue(settings, preparations, preparationService, edits, jdbc, transactions, lease);
                } else if (!preparations.unfinished().isEmpty()) throw unavailable();
                resources.check(); requireLease();
                if (!sourceReady.getAsBoolean() || !initial.equals(settings.load()) || !builds.inFlight().isEmpty()
                        || unresolvedPreparations() || !runs.unfinished().isEmpty())
                    throw unavailable();
                queue = new ExecutionBuildQueue(settings, builds, jdbc, transactions, lease);
                var residentResources = resources.runtime(this::checkAuthority);
                if (residentResources != null) runtime = new ExecutionRuntimeDispatcher(settings, residentResources, runs,
                        this::checkAuthority, this::requestStop);
                // Bound both live workers and handoffs. The durable queue remains the source of waiting work.
                // https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html
                executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(concurrency), task -> new Thread(task, "atoms-execution-worker"),
                        new ThreadPoolExecutor.AbortPolicy());
                monitor = Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, "atoms-execution-dispatcher"));
                activated = true; running.set(true);
                monitor.scheduleWithFixedDelay(this::poll, 0, 250, TimeUnit.MILLISECONDS);
            } catch (IOException | java.sql.SQLException | RuntimeException error) {
                log("execution_dispatcher_unavailable", null);
                requestStop(); finishWhenTerminated();
            }
        }
    }

    private void poll() {
        if (stopping.get()) { finishWhenTerminated(); return; }
        try {
            checkAuthority();
            if (runtime != null) runtime.checkOwnedRuns();
            else if (!runs.unfinished().isEmpty()) throw unavailable();
            // Snapshot ownership BEFORE the query: a worker may finish and remove itself during the read.
            var owned = Set.copyOf(active.keySet());
            for (var build : builds.inFlight())
                if (build.status().equals("NEEDS_RECONCILIATION") || !owned.contains(build.id())) throw unavailable();
            for (var task : preparations.unfinished())
                if (preparationQueue == null || !task.status().equals("QUEUED")
                        && (task.status().equals("NEEDS_RECONCILIATION") || !owned.contains(task.id()))) throw unavailable();
            var waiting = new ArrayList<Job>();
            for (var build : queue.waiting()) waiting.add(new BuildJob(build));
            if (preparationQueue != null) for (var task : preparationQueue.waiting()) waiting.add(new PreparationJob(task));
            // Match PostgreSQL UUID ordering when creation timestamps tie, across both task kinds.
            waiting.sort(Comparator.comparing((Job job) -> job.created).thenComparing(job -> job.id.toString()));
            boolean canDispatch = true;
            for (var candidate : waiting) {
                if (stopping.get()) return;
                if (active.containsKey(candidate.id)) continue;
                var current = candidate.refresh();
                if (current == null || !canDispatch) continue;
                if (!current.dispatchable()) { canDispatch = false; continue; }
                int limit = Math.min(concurrency, Math.min(settings.load().maxConcurrentBuilds(), current.workerLimit()));
                // If a slot frees mid-scan, retry the oldest waiting task on the next poll.
                // Keep settling expiration/cancellation for the remainder without letting it overtake.
                canDispatch = dispatch(current, limit);
            }
        } catch (IOException | RuntimeException error) {
            log("execution_dispatcher_stopping", null); requestStop();
        }
    }

    private boolean dispatch(Job job, int limit) {
        synchronized (admission) {
            if (stopping.get() || !running.get() || active.size() >= limit || active.containsKey(job.id)) return false;
            active.put(job.id, job);
            try { executor.execute(job); }
            catch (RuntimeException error) { active.remove(job.id, job); throw error; }
            return true;
        }
    }

    private void checkAuthority() throws IOException {
        if (!running.get() || stopping.get() || !settings.enabled() || !sourceReady.getAsBoolean()) throw unavailable();
        requireLease(); resources.check();
    }
    private void requireLease() {
        if (lease == null || !lease.healthy()) throw unavailable();
    }

    CompletionStage<ExecutionRun> startApplication(UUID owner, UUID project, UUID build, UUID binding, UUID review, String key) throws IOException {
        return runtime().submit(owner, project, ExecutionRun.request(build, binding, review, key));
    }
    CompletionStage<ExecutionRun> replaceApplication(UUID owner, UUID project, UUID predecessor, UUID build, UUID binding, UUID review, String key) throws IOException {
        return runtime().submit(owner, project, ExecutionRun.replacement(build, binding, review, predecessor, key));
    }
    ExecutionRun stopApplication(UUID owner, UUID project, UUID run) throws IOException {
        return runtime().stop(owner, project, run);
    }
    private ExecutionRuntimeDispatcher runtime() {
        outsideTransaction();
        var resident = runtime;
        if (!isRunning() || resident == null) throw unavailable();
        return resident;
    }

    /** Stop admission first. When still authorized, commit cancellation before signalling owned workers. */
    private void requestStop() {
        if (!stopping.compareAndSet(false, true)) return;
        boolean interrupted = Thread.interrupted();
        try {
            List<Job> jobs;
            synchronized (admission) { jobs = new ArrayList<>(active.values()); }
            if (!jobs.isEmpty() && lease != null && lease.healthy()) {
                jobs.sort(Comparator.comparing(job -> job.project));
                var transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
                transaction.executeWithoutResult(tx -> {
                    jdbc.execute("SET LOCAL lock_timeout = '3s'");
                    for (var job : jobs) {
                        jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, job.project);
                        job.requestCancel();
                    }
                    requireLease();
                });
            }
        } catch (RuntimeException error) {
            // Loss of the database cannot justify continuing our own process or claiming its result was saved.
            log("execution_shutdown_intent_unavailable", null);
        } finally {
            running.set(false);
            try { if (runtime != null) runtime.requestStop(); }
            catch (RuntimeException error) { log("execution_runtime_stop_unavailable", null); }
            finally {
                var workers = executor;
                if (workers != null) for (var queued : workers.shutdownNow())
                    if (queued instanceof Job job) active.remove(job.id, job);
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** The monitor never waits for itself or for a worker that may still need its lease. */
    private void finishWhenTerminated() {
        var workers = executor;
        if (workers != null && !workers.isTerminated()) return;
        if (runtime != null && !runtime.isTerminated()) return;
        var dispatcher = monitor;
        if (dispatcher != null) dispatcher.shutdown();
        try { finish(); }
        catch (RuntimeException error) { log("execution_dispatcher_retained", null); }
    }

    private void finish() {
        synchronized (finishing) {
            if (released) return;
            if (executor != null && !executor.isTerminated()) throw unavailable();
            if (runtime != null && !runtime.isTerminated()) throw unavailable();
            try {
                if (activated) {
                    // Lost authority never grants permission to cancel another dispatcher's new queue.
                    if (lease != null && lease.healthy()) {
                        for (var build : queue.waiting()) queue.cancelWaiting(build);
                        if (preparationQueue != null)
                            for (var task : preparationQueue.waiting()) preparationQueue.cancelWaiting(task);
                    }
                    if (!builds.inFlight().isEmpty() || unresolvedPreparations() || !runs.unfinished().isEmpty()) throw unavailable();
                }
                if (resources != null) { resources.close(); resources = null; }
                if (lease != null) { lease.close(); lease = null; }
                released = true;
            } catch (IOException | java.sql.SQLException | RuntimeException error) { throw unavailable(); }
        }
    }

    @Override public void close() {
        outsideTransaction();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        requestStop();
        boolean interrupted = Thread.interrupted();
        try {
            var workers = executor;
            if (workers != null && !workers.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) throw unavailable();
            var resident = runtime;
            if (resident != null && !resident.awaitTermination(Duration.ofNanos(remaining(deadline)))) throw unavailable();
            var dispatcher = monitor;
            if (dispatcher != null) {
                dispatcher.shutdown();
                if (!dispatcher.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) throw unavailable();
            }
            finish();
        } catch (InterruptedException error) { interrupted = true; throw unavailable(); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    @Override public boolean isRunning() { return running.get() && !stopping.get(); }
    @Override public void stop() { close(); }
    // Source starts at MAX_VALUE - 100; equal-phase Agent execution is an independent sibling.
    // https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/context/SmartLifecycle.html
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    private static long remaining(long deadline) { return Math.max(0, deadline - System.nanoTime()); }
    private boolean unresolvedPreparations() {
        return preparations.unfinished().stream().anyMatch(task -> !task.status().equals("QUEUED"));
    }
    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    }
    private static ApiException unavailable() {
        return new ApiException(503, "EXECUTION_COORDINATOR_UNAVAILABLE", "构建调度不可用，请检查配置或待核对任务。");
    }
    private static void log(String event, UUID task) {
        LOG.warn("{\"event\":\"{}\",\"entryPoint\":\"execution_dispatcher\",\"requestId\":\"{}\",\"errorCode\":\"EXECUTION_COORDINATOR_UNAVAILABLE\"}",
                event, task == null ? UUID.randomUUID() : task);
    }

    private abstract class Job implements Runnable {
        final UUID id, project;
        final Instant created;
        Job(UUID id, UUID project, Instant created) { this.id = id; this.project = project; this.created = created; }
        abstract Job refresh() throws IOException;
        abstract int workerLimit();
        boolean dispatchable() { return true; }
        abstract boolean perform() throws IOException;
        abstract boolean terminal();
        abstract void requestCancel();
        @Override public void run() {
            try {
                checkAuthority();
                if (!perform()) throw unavailable();
            } catch (Throwable error) {
                // A raced queued cancellation can already be terminal. Any other uncertain result stops dispatch.
                boolean settled = false;
                try { settled = terminal(); }
                catch (RuntimeException unreadable) { /* Keep the original task and its capacity. */ }
                if (!settled) { log("execution_worker_unsettled", id); requestStop(); }
            } finally { active.remove(id, this); }
        }
    }

    private final class BuildJob extends Job {
        private final ExecutionBuild build;
        BuildJob(ExecutionBuild build) { super(build.id(), build.projectId(), build.createdAt()); this.build = build; }
        @Override Job refresh() throws IOException {
            var current = queue.beforeDispatch(build);
            return current.status().equals("QUEUED") ? new BuildJob(current) : null;
        }
        @Override int workerLimit() { return builds.workerLimit(build); }
        @Override boolean perform() throws IOException {
            var result = resources.run(build, () -> !running.get(), ExecutionCoordinator.this::checkAuthority);
            return id.equals(result.id()) && result.finishedAt() != null;
        }
        @Override boolean terminal() { return builds.get(project, id).finishedAt() != null; }
        @Override void requestCancel() { builds.requestCancel(builds.lock(project, id)); }
    }

    private final class PreparationJob extends Job {
        private final DependencyPreparationRepository.Task task;
        PreparationJob(DependencyPreparationRepository.Task task) { super(task.id(), task.projectId(), task.createdAt()); this.task = task; }
        @Override Job refresh() throws IOException {
            var current = preparationQueue.beforeDispatch(task);
            return current.status().equals("QUEUED") ? new PreparationJob(current) : null;
        }
        @Override boolean dispatchable() { return preparationQueue.dispatchable(task); }
        @Override int workerLimit() { return preparations.workerLimit(task); }
        @Override boolean perform() throws IOException {
            var result = resources.runPreparation(task, () -> !running.get(), ExecutionCoordinator.this::checkAuthority);
            return id.equals(result.id()) && result.finishedAt() != null;
        }
        @Override boolean terminal() { return preparations.get(project, id).finishedAt() != null; }
        @Override void requestCancel() { preparations.requestCancel(preparations.lock(project, id)); }
    }
}
