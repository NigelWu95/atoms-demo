package dev.atoms.demo.projectexecution;

import dev.atoms.demo.source.ProjectSourceEditGateway;
import dev.atoms.demo.source.SourceOperation;
import java.io.IOException;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Startup-only reconciliation. Source facts decide the outcome; no tools, workspaces or writable handles are recreated. */
final class DependencyPreparationRecovery {
    private final ExecutionSettings settings;
    private final ExecutionSettings.Configuration configuration;
    private final DependencyPreparationRepository repository;
    private final DependencyPreparationService service;
    private final ProjectSourceEditGateway source;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ExecutionCoordinatorLease lease;
    private final BooleanSupplier sourceReady;

    DependencyPreparationRecovery(ExecutionSettings settings, DependencyPreparationRepository repository,
            DependencyPreparationService service, ProjectSourceEditGateway source, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, ExecutionCoordinatorLease lease, BooleanSupplier sourceReady) {
        this.settings = settings; configuration = settings.load(); this.repository = repository; this.service = service;
        this.source = source; this.jdbc = jdbc; this.lease = lease; this.sourceReady = sourceReady;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }

    /** Caller verifies the execution-root lease outside this metadata transaction and has no live workers. */
    DependencyPreparationRepository.Task reconcile(DependencyPreparationRepository.Task candidate) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        try {
            authority();
            return transaction.execute(tx -> {
                jdbc.execute("SET LOCAL lock_timeout = '3s'");
                jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, candidate.projectId());
                var current = repository.lock(candidate.projectId(), candidate.id());
                DependencyPreparationRepository.requireSameTask(candidate, current);
                authority();
                if (current.finishedAt() == null) reconcileLocked(current);
                authority();
                return repository.get(current.projectId(), current.id());
            });
        } catch (RuntimeException error) { throw unavailable(); }
    }

    private void reconcileLocked(DependencyPreparationRepository.Task task) {
        // Fresh admission in this JVM still owns a never-claimed edit; normal queue policy handles it.
        if (task.status().equals("QUEUED") && service.handle(task.id()) != null) return;
        var fact = source.inspectAbandonedEdit(task.projectId(), task.editLeaseId(), task.id());
        var operation = fact.operation();
        if (operation != null) bind(task, operation);
        boolean committed = operation != null && operation.status().equals("COMMITTED");
        if (task.executionPending() || fact.uncertain() || task.status().equals("NEEDS_RECONCILIATION") && !committed) {
            // Inspect before cleanup: a cleared pending bit alone cannot settle an earlier unknown outcome.
            // Retain source ownership unchanged: an operation already in publication may still commit its result.
            if (!task.status().equals("NEEDS_RECONCILIATION"))
                jdbc.update("UPDATE dependency_preparations SET status='NEEDS_RECONCILIATION',error_code='EXECUTION_RECOVERY_UNCERTAIN' WHERE id=?", task.id());
            return;
        }
        var released = source.reconcileAbandonedEdit(task.projectId(), task.editLeaseId(), task.id());
        if (released.uncertain()) throw new IllegalStateException("Source facts changed during recovery");
        String status, code;
        UUID version = null;
        if (committed) {
            status = "SOURCE_SAVED"; code = null; version = operation.resultVersionId();
        } else if (task.cancelRequested() || operation != null && operation.status().equals("CANCELLED")) {
            status = "CANCELLED"; code = "EXECUTION_CANCELLED";
        } else if (task.status().equals("QUEUED") && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT created_at + queue_timeout_seconds * interval '1 second' <= clock_timestamp()
                FROM dependency_preparations WHERE id=?
                """, Boolean.class, task.id()))) {
            status = "TIMED_OUT"; code = "EXECUTION_QUEUE_TIMEOUT";
        } else {
            // In-memory NO_CHANGES cannot be reconstructed from a stop receipt or directory after restart.
            status = "FAILED"; code = "EXECUTION_INTERRUPTED";
        }
        jdbc.update("""
                UPDATE dependency_preparations SET status=?,error_code=?,result_version_id=?,queue_slot=null,worker_slot=null,
                    finished_at=clock_timestamp() WHERE id=?
                """, status, code, version, task.id());
        var handle = service.handle(task.id());
        if (handle != null) service.forgetAfterCommit(task.id(), handle);
    }

    private void bind(DependencyPreparationRepository.Task task, SourceOperation operation) {
        if (!task.projectId().equals(operation.projectId()) || !task.editLeaseId().equals(operation.editLeaseId())
                || !task.ownerId().equals(operation.ownerId()) || !task.baseVersionId().equals(operation.expectedVersionId())
                || task.baseRevision() != operation.expectedRevision()
                || task.sourceOperationId() != null && !task.sourceOperationId().equals(operation.id()))
            throw new IllegalStateException("Source operation does not match preparation");
        if (task.sourceOperationId() == null)
            jdbc.update("UPDATE dependency_preparations SET source_operation_id=? WHERE id=?", operation.id(), task.id());
    }
    private void authority() {
        if (!lease.healthy() || !sourceReady.getAsBoolean() || !configuration.equals(settings.load())) throw new IllegalStateException();
    }
    private static IOException unavailable() { return new IOException("EXECUTION_RECOVERY_UNAVAILABLE"); }
}
