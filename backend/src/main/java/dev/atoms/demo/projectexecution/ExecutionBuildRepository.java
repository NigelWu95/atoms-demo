package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
class ExecutionBuildRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<ExecutionBuild> ROW = (rs, row) -> new ExecutionBuild(
            rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getObject("owner_id", UUID.class),
            rs.getObject("source_version_id", UUID.class), rs.getString("tree_sha256"),
            rs.getString("idempotency_key_sha256"), rs.getString("request_sha256"), rs.getString("status"),
            rs.getBoolean("cancel_requested"), rs.getString("error_code"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(), rs.getInt("last_event_seq"));

    ExecutionBuildRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    ExecutionBuild findKey(UUID project, String keyHash) {
        return jdbc.query("SELECT * FROM execution_builds WHERE project_id=? AND idempotency_key_sha256=?", ROW, project, keyHash)
                .stream().findFirst().orElse(null);
    }

    ExecutionBuild get(UUID project, UUID build) {
        return jdbc.query("SELECT * FROM execution_builds WHERE project_id=? AND id=?", ROW, project, build)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "EXECUTION_BUILD_NOT_FOUND", "构建不存在或无权访问。"));
    }

    /** Internal dispatcher only; the caller must hold its database coordinator lease. */
    List<ExecutionBuild> waiting() {
        return jdbc.query("SELECT * FROM execution_builds WHERE status='QUEUED' ORDER BY created_at,id LIMIT 8", ROW);
    }

    /** At most four worker slots plus eight retained queue slots can be unfinished outside QUEUED. */
    List<ExecutionBuild> inFlight() {
        return jdbc.query("SELECT * FROM execution_builds WHERE finished_at IS NULL AND status<>'QUEUED' ORDER BY id LIMIT 12", ROW);
    }

    int workerLimit(ExecutionBuild build) {
        return jdbc.queryForObject("SELECT worker_capacity FROM execution_builds WHERE project_id=? AND id=?",
                Integer.class, build.projectId(), build.id());
    }

    /** Caller first locks the project; keep the same ordering as claims and stage completion. */
    ExecutionBuild lock(UUID project, UUID build) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Execution row locking requires a transaction");
        return jdbc.query("SELECT * FROM execution_builds WHERE project_id=? AND id=? FOR UPDATE", ROW, project, build)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "EXECUTION_BUILD_NOT_FOUND", "构建不存在或无权访问。"));
    }

    /** Accept only the row locked by this transaction. A non-queued task belongs to its executing worker. */
    ExecutionBuild requestCancel(ExecutionBuild build) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Execution cancellation requires a transaction");
        if (build.finishedAt() != null) return build;
        if (build.status().equals("QUEUED")) {
            // QUEUED has no process intent by schema. Cancel plus capacity/event settlement is atomic.
            jdbc.update("""
                    UPDATE execution_builds SET cancel_requested=true,status='CANCELLED',error_code='EXECUTION_CANCELLED',
                        queue_slot=null,finished_at=clock_timestamp() WHERE id=?
                    """, build.id());
        } else if (!build.cancelRequested()) {
            jdbc.update("UPDATE execution_builds SET cancel_requested=true WHERE id=?", build.id());
        }
        return get(build.projectId(), build.id());
    }

    void requireIdle(UUID project) {
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM execution_task_slots WHERE project_id=?)", Boolean.class, project)))
            throw ExecutionBuild.conflict("EXECUTION_PROJECT_BUSY");
    }

    /** Caller locks the project first. No source/tool/filesystem work inside this transaction. */
    ExecutionBuild insert(UUID owner, UUID project, ExecutionBuild.Request request, String treeHash, ExecutionSettings.Configuration config) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Execution admission requires a transaction");
        requireIdle(project);
        lockCapacity();
        var slots = jdbc.queryForList("""
                SELECT slot FROM generate_series(1,?) AS slot
                WHERE (SELECT count(*) FROM execution_task_slots WHERE queue_slot IS NOT NULL) < ?
                    AND NOT EXISTS(SELECT 1 FROM execution_task_slots WHERE queue_slot=slot)
                ORDER BY slot LIMIT 1
                """, Integer.class, config.queueCapacity(), config.queueCapacity());
        if (slots.isEmpty()) throw new ApiException(429, "EXECUTION_QUEUE_FULL", "构建队列已满，请稍后重试。");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO execution_builds(id,project_id,owner_id,source_version_id,tree_sha256,idempotency_key_sha256,
                    request_sha256,queue_slot,queue_capacity,worker_capacity,task_timeout_seconds,queue_timeout_seconds)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, project, owner, request.version(), treeHash, request.keyHash(), request.bodyHash(),
                slots.get(0), config.queueCapacity(), config.maxConcurrentBuilds(), config.taskTimeout().toSeconds(), config.queueTimeout().toSeconds());
        // AFTER-trigger counters are visible on a fresh query, not the INSERT's RETURNING row.
        return get(project, id);
    }

    /** Project (and existing build) rows must be locked before capacity, never in the opposite order. */
    void lockCapacity() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Execution capacity requires a transaction");
        // Agent admission/coordinator use 718395201/718395202; session and transaction locks share a namespace.
        // https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        jdbc.execute("SELECT pg_catalog.pg_advisory_xact_lock(718395203)");
    }
}
