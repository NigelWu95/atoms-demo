package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.source.ProjectSourceEditGateway;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** The dispatcher owns scheduling, not a shared Claude conversation. Every job owns its own execution. */
@Component
@ConditionalOnProperty(name="atoms.agent.enabled", havingValue="true")
final class AgentCoordinator implements SmartLifecycle, AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentCoordinator.class);
    private final AgentSettings settings;
    private final AgentTaskRepository tasks;
    private final ProjectAgentService service;
    private final AgentWorker worker;
    private final AgentRecovery recovery;
    private final ProjectSourceEditGateway source;
    private final DataSource database;
    private final AtomicBoolean running = new AtomicBoolean(), closed = new AtomicBoolean();
    private final java.util.Set<UUID> active = ConcurrentHashMap.newKeySet();
    private int concurrency;
    private boolean isolateUnresolvedProjects;
    private AgentCoordinatorLease lease;
    private ThreadPoolExecutor executor;
    private ScheduledExecutorService monitor;

    AgentCoordinator(AgentSettings settings, AgentTaskRepository tasks, ProjectAgentService service, AgentWorker worker,
                     AgentRecovery recovery, ProjectSourceEditGateway source, DataSource database) {
        this.settings = settings; this.tasks = tasks; this.service = service; this.worker = worker;
        this.recovery = recovery; this.source = source; this.database = database;
    }

    @Override public synchronized void start() {
        if (running.get() || closed.get() || !settings.enabled()) return;
        try {
            var config = settings.load();
            concurrency = config.maxConcurrentTasks();
            isolateUnresolvedProjects = config.ownerAccess();
            if (!source.readyForAgent()) throw unavailable();
            lease = new AgentCoordinatorLease(database);
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            UUID cursor = null;
            while (true) {
                var page = tasks.recoveryCandidates(cursor);
                if (page.isEmpty()) break;
                for (var id : page) {
                    if (!lease.healthy() || System.nanoTime()>=deadline) throw unavailable();
                    reconcile(id); cursor = id;
                }
            }
            if (tasks.hasUnresolved() && !isolateUnresolvedProjects) throw unavailable();
            executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(concurrency),
                    task -> new Thread(task, "atoms-agent-worker"), new ThreadPoolExecutor.AbortPolicy());
            monitor = Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, "atoms-agent-dispatcher"));
            running.set(true);
            monitor.scheduleWithFixedDelay(this::poll, 0, 250, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            closed.set(true); running.set(false); closeLease();
            log("agent_dispatcher_unavailable", "AGENT_COORDINATOR_UNAVAILABLE");
        }
    }

    private void poll() {
        if (closed.get()) return;
        if (!running.get()) { closeSafely(); return; }
        try {
            if (!settings.enabled() || !source.readyForAgent() || !lease.healthy()
                    || tasks.hasUnresolved() && !isolateUnresolvedProjects) throw unavailable();
            for (var task : tasks.queued()) {
                if (!running.get()) return;
                if (active.contains(task.id())) continue;
                if (tasks.queueExpired(task.id())) {
                    reconcile(task.id()); continue;
                }
                // The afterCommit capability callback can lag the committed task row by one poll.
                if (service.handle(task.id()) != null) dispatch(task.id());
            }
        } catch (RuntimeException error) {
            log("agent_dispatcher_stopping", "AGENT_COORDINATOR_UNAVAILABLE");
            closeSafely();
        }
    }

    /** One poller admits work; shutdown shares this short lock, never the task's execution lock. */
    private synchronized void dispatch(UUID id) {
        // Unknown executions retain their lease/work directory AND a global capacity slot across restarts.
        // Never release or retry them merely because their owning worker/thread has returned.
        int held = isolateUnresolvedProjects ? tasks.quarantinedExecutions() : 0;
        if (!running.get() || active.size() + held >= concurrency || !active.add(id)) return;
        try { executor.execute(() -> execute(id)); }
        catch (RuntimeException error) { active.remove(id); throw error; }
    }

    private void closeSafely() {
        try { close(); } catch (RuntimeException unresolved) { log("agent_dispatcher_retained", "AGENT_EXECUTION_UNCERTAIN"); }
    }

    private void execute(UUID id) {
        try {
            if (worker.execute(id, () -> !running.get()).status().equals("NEEDS_RECONCILIATION")) {
                if (isolateUnresolvedProjects) log("agent_project_quarantined", "AGENT_EXECUTION_UNCERTAIN");
                else running.set(false);
            }
        }
        catch (Throwable error) {
            boolean interrupted = Thread.interrupted();
            try { reconcile(id); }
            catch (Throwable unresolved) { running.set(false); log("agent_dispatch_failed", "AGENT_EXECUTION_UNCERTAIN"); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        } finally { active.remove(id); }
    }

    private void reconcile(UUID id) { recovery.reconcile(id); service.discardSettled(id); }
    @Override public boolean isRunning() { return running.get(); }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public void stop() { close(); }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        running.set(false);
        if (monitor != null) monitor.shutdown();
        if (executor == null) { closeLease(); return; }
        executor.shutdownNow();
        boolean interrupted = Thread.interrupted();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) throw unavailable();
            // No owned worker remains; settle only never-started queued work, not unknown executions.
            // A lost lease does not authorize modifying another dispatcher's newly admitted queue.
            if (lease != null && lease.healthy()) for (var task : tasks.queued()) reconcile(task.id());
            if ((tasks.hasUnresolved() || tasks.hasUnsettledExecutions()) && !isolateUnresolvedProjects) throw unavailable();
            closeLease();
        } catch (InterruptedException error) {
            interrupted = true; throw unavailable();
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private void closeLease() {
        if (lease == null) return;
        try { lease.close(); }
        catch (java.sql.SQLException error) { log("agent_dispatcher_lease_close_failed", "AGENT_COORDINATOR_UNAVAILABLE"); }
        finally { lease = null; }
    }
    private static ApiException unavailable() { return new ApiException(503, "AGENT_COORDINATOR_UNAVAILABLE", "工程任务调度不可用，请检查待核对任务和维护者配置。"); }
    private static void log(String event, String code) {
        LOG.warn("{\"event\":\"{}\",\"entryPoint\":\"agent_dispatcher\",\"requestId\":\"{}\",\"errorCode\":\"{}\"}", event, UUID.randomUUID(), code);
    }
}
