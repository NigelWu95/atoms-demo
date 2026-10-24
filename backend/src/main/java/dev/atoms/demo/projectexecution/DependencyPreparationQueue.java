package dev.atoms.demo.projectexecution;

import dev.atoms.demo.source.ProjectSourceEditGateway;
import java.io.IOException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Never-claimed preparation policy under exclusive coordination; no tools, workspace reads or source publication. */
final class DependencyPreparationQueue {
    private final ExecutionSettings settings;
    private final DependencyPreparationRepository repository;
    private final DependencyPreparationService service;
    private final ProjectSourceEditGateway source;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ExecutionCoordinatorLease lease;

    DependencyPreparationQueue(ExecutionSettings settings, DependencyPreparationRepository repository,
            DependencyPreparationService service, ProjectSourceEditGateway source, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, ExecutionCoordinatorLease lease) {
        this.settings = settings; this.repository = repository; this.service = service; this.source = source;
        this.jdbc = jdbc; this.lease = lease;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }

    List<DependencyPreparationRepository.Task> waiting() throws IOException {
        try {
            independent(); authority();
            var tasks = List.copyOf(repository.waiting()); authority(); return tasks;
        } catch (RuntimeException error) { throw unavailable(); }
    }

    /** The worker must still authorize and claim; this only avoids dispatch during admission's afterCommit handoff. */
    boolean dispatchable(DependencyPreparationRepository.Task task) {
        var edit = service.handle(task.id());
        return task.status().equals("QUEUED") && !task.cancelRequested() && edit != null
                && task.id().equals(edit.taskId()) && task.projectId().equals(edit.projectId()) && task.editLeaseId().equals(edit.leaseId());
    }

    DependencyPreparationRepository.Task beforeDispatch(DependencyPreparationRepository.Task task) throws IOException { return settle(task, false); }
    DependencyPreparationRepository.Task cancelWaiting(DependencyPreparationRepository.Task task) throws IOException { return settle(task, true); }

    private DependencyPreparationRepository.Task settle(DependencyPreparationRepository.Task candidate, boolean stopping) throws IOException {
        try {
            independent(); authority();
            if (!stopping) settings.load();
            return transaction.execute(tx -> {
                jdbc.execute("SET LOCAL lock_timeout = '3s'");
                var project = jdbc.queryForMap("SELECT owner_id,archived FROM projects WHERE id=? FOR UPDATE", candidate.projectId());
                var current = repository.lock(candidate.projectId(), candidate.id());
                DependencyPreparationRepository.requireSameTask(candidate, current);
                var config = stopping ? null : settings.load();
                if (current.status().equals("QUEUED")) {
                    String status = null, code = null;
                    if (stopping || current.cancelRequested()) { status = "CANCELLED"; code = "EXECUTION_CANCELLED"; }
                    else if (Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT created_at + queue_timeout_seconds * interval '1 second' <= clock_timestamp()
                            FROM dependency_preparations WHERE id=?
                            """, Boolean.class, current.id()))) { status = "TIMED_OUT"; code = "EXECUTION_QUEUE_TIMEOUT"; }
                    else if (!current.ownerId().equals(project.get("owner_id"))) { status = "FAILED"; code = "EXECUTION_PREPARATION_OWNER_CHANGED"; }
                    else if (Boolean.TRUE.equals(project.get("archived"))) { status = "FAILED"; code = "EXECUTION_PROJECT_ARCHIVED"; }
                    else if (!config.maintainerIds().contains(current.ownerId())) { status = "FAILED"; code = "EXECUTION_FORBIDDEN"; }
                    if (status != null) current = finish(current, status, code);
                }
                authority(); return current;
            });
        } catch (RuntimeException error) { throw unavailable(); }
    }

    private DependencyPreparationRepository.Task finish(DependencyPreparationRepository.Task task, String status, String code) {
        var edit = service.handle(task.id());
        // No original capability: retain both slot and source edit for startup recovery; cancellation is only intent.
        if (edit == null) return status.equals("CANCELLED") ? repository.requestCancel(task) : task;
        if (!task.id().equals(edit.taskId()) || !task.projectId().equals(edit.projectId()) || !task.editLeaseId().equals(edit.leaseId()))
            throw new IllegalStateException("Preparation capability mismatch");
        // Never claimed, so releasing requires no new owner permission, even after archive/transfer/disable.
        source.releaseSettledEdit(edit);
        var result = repository.finishQueued(task, status, code);
        service.forgetAfterCommit(task.id(), edit);
        return result;
    }
    private static void independent() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException();
    }
    private void authority() { if (!lease.healthy()) throw new IllegalStateException(); }
    private static IOException unavailable() { return new IOException("EXECUTION_QUEUE_UNAVAILABLE"); }
}
