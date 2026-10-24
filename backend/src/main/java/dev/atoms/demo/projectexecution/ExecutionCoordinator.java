package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.source.ProjectSourceReadGateway;
import java.io.IOException;
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
        @Override void close() throws IOException;
    }
    /** Trusted resource assembly only; never supplied by a request or generated source. */
    @FunctionalInterface interface Factory { WorkerResources open() throws IOException; }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ExecutionCoordinator.class);
    private final ExecutionSettings settings;
    private final ExecutionBuildRepository builds;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final DataSource database;
    private final BooleanSupplier sourceReady;
    private final Factory factory;
    private final Object admission = new Object(), finishing = new Object();
    private final AtomicBoolean running = new AtomicBoolean(), stopping = new AtomicBoolean();
    private final Map<UUID, Job> active = new ConcurrentHashMap<>();
    private volatile ThreadPoolExecutor executor;
    private volatile ScheduledExecutorService monitor;
    private volatile ExecutionCoordinatorLease lease;
    private volatile WorkerResources resources;
    private ExecutionBuildQueue queue;
    private int concurrency;
    private boolean activated, released;

    @Autowired
    ExecutionCoordinator(ExecutionSettings settings, ExecutionBuildRepository builds, JdbcTemplate jdbc,
                         PlatformTransactionManager transactions, DataSource database, ProjectSourceReadGateway source,
                         ExecutionEnvironment environment) {
        this(settings, builds, jdbc, transactions, database, source::readyForExecution, environment);
    }

    ExecutionCoordinator(ExecutionSettings settings, ExecutionBuildRepository builds, JdbcTemplate jdbc,
                         PlatformTransactionManager transactions, DataSource database, BooleanSupplier sourceReady,
                         Factory factory) {
        this.settings = settings; this.builds = builds; this.jdbc = jdbc; this.transactions = transactions;
        this.database = database; this.sourceReady = sourceReady; this.factory = factory;
    }

    @Override public void start() {
        outsideTransaction();
        synchronized (admission) {
            if (running.get() || stopping.get()) return;
            try {
                if (!settings.enabled()) return;
                concurrency = settings.load().maxConcurrentBuilds();
                if (!sourceReady.getAsBoolean()) throw unavailable();
                lease = new ExecutionCoordinatorLease(database);
                // EX13 will reconcile prior work. Until then, never replay an old claimed/unknown execution.
                if (!builds.inFlight().isEmpty()) throw unavailable();
                resources = Objects.requireNonNull(factory.open());
                resources.check(); requireLease();
                if (!sourceReady.getAsBoolean()) throw unavailable();
                queue = new ExecutionBuildQueue(settings, builds, jdbc, transactions, lease);
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
            // Snapshot ownership BEFORE the query: a worker may finish and remove itself during the read.
            var owned = Set.copyOf(active.keySet());
            for (var build : builds.inFlight())
                if (build.status().equals("NEEDS_RECONCILIATION") || !owned.contains(build.id())) throw unavailable();
            boolean canDispatch = true;
            for (var candidate : queue.waiting()) {
                if (stopping.get()) return;
                if (active.containsKey(candidate.id())) continue;
                var current = queue.beforeDispatch(candidate);
                if (!current.status().equals("QUEUED") || !canDispatch) continue;
                int limit = Math.min(concurrency, Math.min(settings.load().maxConcurrentBuilds(), builds.workerLimit(current)));
                // If a slot frees mid-scan, retry the oldest waiting task on the next poll.
                // Keep settling expiration/cancellation for the remainder without letting it overtake.
                canDispatch = dispatch(current, limit);
            }
        } catch (IOException | RuntimeException error) {
            log("execution_dispatcher_stopping", null); requestStop();
        }
    }

    private boolean dispatch(ExecutionBuild build, int limit) {
        synchronized (admission) {
            if (stopping.get() || !running.get() || active.size() >= limit || active.containsKey(build.id())) return false;
            var job = new Job(build); active.put(build.id(), job);
            try { executor.execute(job); }
            catch (RuntimeException error) { active.remove(build.id(), job); throw error; }
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

    /** Stop admission first. When still authorized, commit cancellation before signalling owned workers. */
    private void requestStop() {
        if (!stopping.compareAndSet(false, true)) return;
        boolean interrupted = Thread.interrupted();
        try {
            List<Job> jobs;
            synchronized (admission) { jobs = new ArrayList<>(active.values()); }
            if (!jobs.isEmpty() && lease != null && lease.healthy()) {
                jobs.sort(Comparator.comparing(job -> job.build.projectId()));
                var transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
                transaction.executeWithoutResult(tx -> {
                    jdbc.execute("SET LOCAL lock_timeout = '3s'");
                    for (var job : jobs) {
                        jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, job.build.projectId());
                        builds.requestCancel(builds.lock(job.build.projectId(), job.build.id()));
                    }
                    requireLease();
                });
            }
        } catch (RuntimeException error) {
            // Loss of the database cannot justify continuing our own process or claiming its result was saved.
            log("execution_shutdown_intent_unavailable", null);
        } finally {
            running.set(false);
            var workers = executor;
            if (workers != null) for (var queued : workers.shutdownNow())
                if (queued instanceof Job job) active.remove(job.build.id(), job);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** The monitor never waits for itself or for a worker that may still need its lease. */
    private void finishWhenTerminated() {
        var workers = executor;
        if (workers != null && !workers.isTerminated()) return;
        var dispatcher = monitor;
        if (dispatcher != null) dispatcher.shutdown();
        try { finish(); }
        catch (RuntimeException error) { log("execution_dispatcher_retained", null); }
    }

    private void finish() {
        synchronized (finishing) {
            if (released) return;
            if (executor != null && !executor.isTerminated()) throw unavailable();
            try {
                if (activated) {
                    // Lost authority never grants permission to cancel another dispatcher's new queue.
                    if (lease != null && lease.healthy()) for (var build : queue.waiting()) queue.cancelWaiting(build);
                    if (!builds.inFlight().isEmpty()) throw unavailable();
                }
                if (resources != null) { resources.close(); resources = null; }
                if (lease != null) { lease.close(); lease = null; }
                released = true;
            } catch (IOException | java.sql.SQLException | RuntimeException error) { throw unavailable(); }
        }
    }

    @Override public void close() {
        outsideTransaction(); requestStop();
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try {
            var workers = executor;
            if (workers != null && !workers.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) throw unavailable();
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

    private final class Job implements Runnable {
        final ExecutionBuild build;
        Job(ExecutionBuild build) { this.build = build; }
        @Override public void run() {
            try {
                checkAuthority();
                var result = resources.run(build, () -> !running.get(), ExecutionCoordinator.this::checkAuthority);
                if (!result.id().equals(build.id()) || result.finishedAt() == null) throw unavailable();
            } catch (Throwable error) {
                // A raced queued cancellation can already be terminal. Any other uncertain result stops dispatch.
                boolean settled = false;
                try { settled = builds.get(build.projectId(), build.id()).finishedAt() != null; }
                catch (RuntimeException unreadable) { /* Keep the original task and its capacity. */ }
                if (!settled) { log("execution_worker_unsettled", build.id()); requestStop(); }
            } finally { active.remove(build.id(), this); }
        }
    }
}
