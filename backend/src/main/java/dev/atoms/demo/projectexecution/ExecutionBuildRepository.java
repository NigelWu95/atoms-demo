package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
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

    void requireIdle(UUID project) {
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM execution_builds WHERE project_id=? AND finished_at IS NULL)", Boolean.class, project)))
            throw ExecutionBuild.conflict("EXECUTION_PROJECT_BUSY");
    }

    /** Caller locks the project first. No source/tool/filesystem work inside this transaction. */
    ExecutionBuild insert(UUID owner, UUID project, ExecutionBuild.Request request, String treeHash, ExecutionSettings.Configuration config) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Execution admission requires a transaction");
        requireIdle(project);
        lockCapacity();
        var slots = jdbc.queryForList("""
                SELECT slot FROM generate_series(1,?) AS slot
                WHERE (SELECT count(*) FROM execution_builds WHERE queue_slot IS NOT NULL) < ?
                    AND NOT EXISTS(SELECT 1 FROM execution_builds WHERE queue_slot=slot)
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
