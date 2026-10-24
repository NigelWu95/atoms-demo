package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
class DependencyPreparationRepository {
    record Task(UUID id, UUID projectId, UUID ownerId, UUID editLeaseId, UUID baseVersionId, long baseRevision,
                String treeSha256, String manifestSha256, String baseLockSha256, String policySha256,
                String requestSha256, String status, boolean cancelRequested, boolean executionPending,
                UUID sourceOperationId, UUID resultVersionId, String errorCode, Instant createdAt, Instant finishedAt) {}
    record Request(UUID version, long revision, String keyHash, String bodyHash) {
        static Request from(DependencyPreparationService.Input input, String key) {
            if (input == null || input.expectedSourceRevision() < 1 || input.expectedSourceRevision() > 9007199254740991L)
                throw new ApiException(422, "EXECUTION_INVALID_INPUT", "源码基线或修订号无效。");
            var validated = ExecutionBuild.request(input.baseSourceVersionId(), key);
            try {
                byte[] bytes = ("dependency-preparation-v1\n" + validated.version() + "\n" + input.expectedSourceRevision()).getBytes(StandardCharsets.UTF_8);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                return new Request(validated.version(), input.expectedSourceRevision(), validated.keyHash(), hash);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
    private static final RowMapper<Task> ROW = (rs, row) -> new Task(rs.getObject("id", UUID.class),
            rs.getObject("project_id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getObject("edit_lease_id", UUID.class),
            rs.getObject("base_version_id", UUID.class), rs.getLong("base_revision"), rs.getString("tree_sha256"),
            rs.getString("manifest_sha256"), rs.getString("base_lock_sha256"), rs.getString("policy_sha256"),
            rs.getString("request_sha256"), rs.getString("status"), rs.getBoolean("cancel_requested"), rs.getBoolean("execution_pending"),
            rs.getObject("source_operation_id", UUID.class), rs.getObject("result_version_id", UUID.class), rs.getString("error_code"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
    private final JdbcTemplate jdbc;
    private final ExecutionBuildRepository capacity;
    DependencyPreparationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; capacity = new ExecutionBuildRepository(jdbc); }

    Task findKey(UUID project, String keyHash) {
        return jdbc.query("SELECT * FROM dependency_preparations WHERE project_id=? AND idempotency_key_sha256=?", ROW, project, keyHash)
                .stream().findFirst().orElse(null);
    }
    Task get(UUID project, UUID task) {
        return jdbc.query("SELECT * FROM dependency_preparations WHERE project_id=? AND id=?", ROW, project, task)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "EXECUTION_PREPARATION_NOT_FOUND", "依赖准备任务不存在或无权访问。"));
    }
    void requireIdle(UUID project) { capacity.requireIdle(project); }

    /** Exclusive coordinator only. Includes queued tasks whose original in-memory edit was lost on restart. */
    List<Task> unfinished() {
        return jdbc.query("SELECT * FROM dependency_preparations WHERE finished_at IS NULL ORDER BY created_at,id LIMIT 12", ROW);
    }

    List<Task> waiting() {
        return jdbc.query("SELECT * FROM dependency_preparations WHERE status='QUEUED' ORDER BY created_at,id LIMIT 8", ROW);
    }

    int workerLimit(Task task) {
        return jdbc.queryForObject("SELECT worker_capacity FROM dependency_preparations WHERE project_id=? AND id=?",
                Integer.class, task.projectId(), task.id());
    }

    static void requireSameTask(Task expected, Task actual) {
        if (!expected.id().equals(actual.id()) || !expected.projectId().equals(actual.projectId())
                || !expected.ownerId().equals(actual.ownerId()) || !expected.editLeaseId().equals(actual.editLeaseId())
                || !expected.baseVersionId().equals(actual.baseVersionId()) || expected.baseRevision() != actual.baseRevision()
                || !expected.treeSha256().equals(actual.treeSha256()) || !expected.manifestSha256().equals(actual.manifestSha256())
                || !Objects.equals(expected.baseLockSha256(), actual.baseLockSha256()) || !expected.policySha256().equals(actual.policySha256())
                || !expected.requestSha256().equals(actual.requestSha256()) || !expected.createdAt().equals(actual.createdAt()))
            throw new IllegalStateException("Preparation identity changed");
    }

    Task lock(UUID project, UUID task) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Preparation lock requires a transaction");
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        return jdbc.query("SELECT * FROM dependency_preparations WHERE project_id=? AND id=? FOR UPDATE", ROW, project, task)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "EXECUTION_PREPARATION_NOT_FOUND", "依赖准备任务不存在或无权访问。"));
    }

    /** Caller holds the project and task locks. A cancellation intent is not evidence of stopped execution. */
    Task requestCancel(Task task) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Cancellation requires a transaction");
        if (task.finishedAt() == null && !task.cancelRequested())
            jdbc.update("UPDATE dependency_preparations SET cancel_requested=true WHERE id=?", task.id());
        return get(task.projectId(), task.id());
    }

    /** The caller must release the never-claimed source edit in this same transaction. */
    Task cancelQueued(Task task) {
        return finishQueued(task, "CANCELLED", "EXECUTION_CANCELLED");
    }

    Task finishQueued(Task task, String status, String code) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Queue settlement requires a transaction");
        if (!Set.of("CANCELLED", "FAILED", "TIMED_OUT").contains(status)) throw new IllegalArgumentException("Invalid queue outcome");
        if (jdbc.update("""
                UPDATE dependency_preparations SET status=?,cancel_requested=cancel_requested OR ?,error_code=?,
                    queue_slot=null,finished_at=clock_timestamp() WHERE id=? AND status='QUEUED'
                """, status, status.equals("CANCELLED"), code, task.id()) != 1) throw new IllegalStateException("Preparation is no longer queued");
        return get(task.projectId(), task.id());
    }

    Task insert(UUID id, UUID owner, UUID project, UUID edit, Request request, String tree,
                ExecutionNpmPolicy.Preparation approved, ExecutionSettings.Configuration config) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Preparation admission requires a transaction");
        requireIdle(project);
        capacity.lockCapacity();
        var slots = jdbc.queryForList("""
                SELECT slot FROM generate_series(1,?) slot
                WHERE (SELECT count(*) FROM execution_task_slots WHERE queue_slot IS NOT NULL) < ?
                    AND NOT EXISTS(SELECT 1 FROM execution_task_slots WHERE queue_slot=slot)
                ORDER BY slot LIMIT 1
                """, Integer.class, config.queueCapacity(), config.queueCapacity());
        if (slots.isEmpty()) throw new ApiException(429, "EXECUTION_QUEUE_FULL", "执行队列已满，请稍后重试。");
        jdbc.update("""
                INSERT INTO dependency_preparations(id,project_id,owner_id,edit_lease_id,base_version_id,base_revision,
                    tree_sha256,manifest_sha256,base_lock_sha256,policy_sha256,idempotency_key_sha256,request_sha256,
                    queue_slot,queue_capacity,worker_capacity,task_timeout_seconds,queue_timeout_seconds)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, project, owner, edit, request.version(), request.revision(), tree, approved.manifestSha256(),
                approved.baseLockSha256(), approved.policySha256(), request.keyHash(), request.bodyHash(), slots.get(0),
                config.queueCapacity(), config.maxConcurrentBuilds(), config.taskTimeout().toSeconds(), config.queueTimeout().toSeconds());
        return get(project, id);
    }
}
