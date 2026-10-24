package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.ProjectSourceEditGateway;
import dev.atoms.demo.source.SourceOperation;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Metadata for one claimed preparation. Tools, candidate validation and Git I/O stay outside its transactions. */
final class DependencyPreparationLifecycle {
    private final ExecutionSettings settings;
    private final ProjectService projects;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final DependencyPreparationRepository repository;
    private final DependencyPreparationService service;
    private final ProjectSourceEditGateway edits;
    private final DependencyPreparationRepository.Task task;
    private final ProjectSourceEditGateway.EditHandle edit;

    DependencyPreparationLifecycle(ExecutionSettings settings, ProjectService projects, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, DependencyPreparationRepository repository, DependencyPreparationService service,
            ProjectSourceEditGateway edits, DependencyPreparationRepository.Task task, ProjectSourceEditGateway.EditHandle edit) {
        if (task == null || edit == null || !task.id().equals(edit.taskId()) || !task.projectId().equals(edit.projectId())
                || !task.editLeaseId().equals(edit.leaseId())) throw new IllegalArgumentException("Invalid preparation capability");
        this.settings = settings; this.projects = projects; this.jdbc = jdbc; this.repository = repository;
        this.service = service; this.edits = edits; this.task = task; this.edit = edit;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }

    void begin(UUID execution) throws IOException {
        transact(() -> {
            authorize(); var row = locked();
            if (execution == null || !"PREPARING".equals(row.get("status")) || pending(row) || cancelled(row)) reject();
            edits.markExecutionStarted(edit, execution);
            jdbc.update("UPDATE dependency_preparations SET status='RESOLVING',execution_pending=true WHERE id=?", task.id());
            return null;
        });
    }

    /** Actual stop evidence is recordable even after timeout, revocation or an earlier missing-receipt report. */
    void stopped(OwnedProcess.Stopped evidence) throws IOException {
        transact(() -> {
            evidenceLock(); var row = locked();
            if (!Set.of("RESOLVING", "NEEDS_RECONCILIATION").contains(row.get("status")) || !pending(row)) reject();
            if (evidence == null) uncertain("EXECUTION_PROCESS_UNSETTLED");
            else {
                edits.confirmExecutionStopped(edit, evidence);
                jdbc.update("UPDATE dependency_preparations SET execution_pending=false WHERE id=?", task.id());
            }
            return null;
        });
    }

    void accepting() throws IOException {
        transact(() -> {
            authorize(); var row = locked();
            if (!"RESOLVING".equals(row.get("status")) || pending(row) || cancelled(row)) reject();
            jdbc.update("UPDATE dependency_preparations SET status='ACCEPTING' WHERE id=?", task.id());
            return null;
        });
    }

    void bindSource() throws IOException {
        transact(() -> {
            evidenceLock(); var row = locked();
            if (!"ACCEPTING".equals(row.get("status")) || pending(row)) reject();
            var operation = edits.operationFact(edit);
            if (operation == null) reject();
            bind(row, operation); return null;
        });
    }

    /** Source truth takes precedence over a late cancellation/error. Uncertain execution never frees capacity. */
    DependencyPreparationRepository.Task settle(String failure) throws IOException {
        if ("EXECUTION_STATE_UNAVAILABLE".equals(failure)
                || failure != null && !failure.equals(failureCode(new IOException(failure)))) throw unavailable();
        return transact(() -> {
            evidenceLock(); var row = locked();
            if (row.get("finished_at") != null) return current();
            var operation = edits.operationFact(edit);
            if (operation != null) bind(row, operation);
            if (pending(row)) return uncertain("EXECUTION_PROCESS_UNSETTLED");
            if (operation != null && (!operation.terminal() || operation.executionPending()))
                return uncertain("EXECUTION_SOURCE_UNSETTLED");
            if (operation != null && "COMMITTED".equals(operation.status()))
                return finish("SOURCE_SAVED", null, operation.resultVersionId());
            if ("EXECUTION_PROCESS_UNSETTLED".equals(failure) || "EXECUTION_SOURCE_UNSETTLED".equals(failure))
                return uncertain(failure);
            // Do not turn a previous unknown result into failure simply because pending is now false.
            if ("NEEDS_RECONCILIATION".equals(row.get("status"))) return current();
            String code = cancelled(row) ? "EXECUTION_CANCELLED" : failure;
            if (operation != null) code = cancelled(row) || "CANCELLED".equals(operation.status())
                    ? "EXECUTION_CANCELLED" : failureCode(new IOException(operation.errorCode()));
            if (code == null && "ACCEPTING".equals(row.get("status")) && edits.noChangesFact(edit))
                return finish("NO_CHANGES", null, null);
            if (code == null) reject();
            String terminal = code.equals("EXECUTION_CANCELLED") ? "CANCELLED"
                    : Set.of("EXECUTION_TIMEOUT", "SOURCE_OPERATION_TIMEOUT").contains(code) ? "TIMED_OUT" : "FAILED";
            return finish(terminal, code, null);
        });
    }

    private DependencyPreparationRepository.Task finish(String status, String code, UUID version) {
        edits.releaseSettledEdit(edit);
        jdbc.update("UPDATE dependency_preparations SET status=?,error_code=?,result_version_id=?,queue_slot=null,worker_slot=null,finished_at=clock_timestamp() WHERE id=?",
                status, code, version, task.id());
        service.forgetAfterCommit(task.id(), edit);
        return current();
    }
    private DependencyPreparationRepository.Task uncertain(String code) {
        jdbc.update("UPDATE dependency_preparations SET status='NEEDS_RECONCILIATION',error_code=? WHERE id=?", code, task.id());
        return current();
    }
    private void bind(Map<String, Object> row, SourceOperation operation) {
        if (!task.projectId().equals(operation.projectId()) || !task.editLeaseId().equals(operation.editLeaseId())
                || !task.ownerId().equals(operation.ownerId()) || !task.baseVersionId().equals(operation.expectedVersionId())
                || task.baseRevision() != operation.expectedRevision()
                || row.get("source_operation_id") != null && !operation.id().equals(row.get("source_operation_id"))) reject();
        if (row.get("source_operation_id") == null)
            jdbc.update("UPDATE dependency_preparations SET source_operation_id=? WHERE id=?", operation.id(), task.id());
    }
    private void authorize() {
        if (projects.lock(task.ownerId(), task.projectId().toString()).archived()
                || !settings.load().maintainerIds().contains(task.ownerId())) reject();
    }
    private void evidenceLock() {
        jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, task.projectId());
    }
    private Map<String, Object> locked() {
        var row = jdbc.queryForMap("SELECT * FROM dependency_preparations WHERE project_id=? AND id=? FOR UPDATE", task.projectId(), task.id());
        if (!task.ownerId().equals(row.get("owner_id")) || !task.editLeaseId().equals(row.get("edit_lease_id"))
                || !task.baseVersionId().equals(row.get("base_version_id")) || task.baseRevision() != ((Number) row.get("base_revision")).longValue()
                || !task.treeSha256().equals(row.get("tree_sha256")) || !task.policySha256().equals(row.get("policy_sha256"))
                || !task.requestSha256().equals(row.get("request_sha256"))) reject();
        return row;
    }
    private DependencyPreparationRepository.Task current() { return repository.get(task.projectId(), task.id()); }
    private static boolean pending(Map<String, Object> row) { return Boolean.TRUE.equals(row.get("execution_pending")); }
    private static boolean cancelled(Map<String, Object> row) { return Boolean.TRUE.equals(row.get("cancel_requested")); }
    static String failureCode(Exception error) {
        String code = error instanceof ApiException api ? api.code() : error.getMessage();
        return switch (Objects.toString(code, "")) {
            case "EXECUTION_NPM_POLICY_REJECTED", "EXECUTION_NPM_APPROVAL_REQUIRED", "EXECUTION_LOCK_CHANGES_REJECTED",
                 "EXECUTION_DEPENDENCY_POLICY_INVALID", "EXECUTION_SOURCE_UNSETTLED", "SOURCE_BASE_CONFLICT",
                 "SOURCE_NEEDS_RECONCILIATION", "SOURCE_STORAGE_UNAVAILABLE", "SOURCE_COORDINATOR_UNAVAILABLE",
                 "SOURCE_OPERATION_TIMEOUT" -> code;
            case "SOURCE_EDIT_CANCELLED" -> "EXECUTION_CANCELLED";
            default -> ExecutionFrontend.failureCode(new IOException(code));
        };
    }
    private <T> T transact(Supplier<T> action) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        try {
            return transaction.execute(tx -> { jdbc.execute("SET LOCAL lock_timeout = '3s'"); return action.get(); });
        } catch (RuntimeException error) { throw unavailable(); }
    }
    private static void reject() { throw new IllegalStateException(); }
    private static IOException unavailable() { return new IOException("EXECUTION_STATE_UNAVAILABLE"); }
}
