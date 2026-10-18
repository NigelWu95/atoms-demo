package dev.atoms.demo.agent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Persistence only. All external reads must pass the service's current-owner authorization. */
@Repository
class AgentTaskRepository {
    private final JdbcTemplate jdbc;
    AgentTaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final RowMapper<AgentTask> ROW = (r,n) -> new AgentTask(uuid(r,"id"),uuid(r,"project_id"),uuid(r,"owner_id"),
            uuid(r,"edit_lease_id"),uuid(r,"base_version_id"),r.getLong("base_revision"),r.getString("idempotency_sha256"),
            r.getString("request_sha256"),r.getString("prompt"),r.getString("status"),r.getBoolean("cancel_requested"),
            uuid(r,"execution_id"),uuid(r,"source_operation_id"),uuid(r,"result_version_id"),r.getBoolean("live_reserved"),r.getString("error_code"),
            r.getInt("max_tokens"),r.getInt("max_turns"),r.getInt("task_timeout_seconds"),r.getInt("queue_timeout_seconds"),
            instant(r,"created_at"),instant(r,"started_at"),instant(r,"finished_at"),r.getString("entry_point"));
    AgentTask get(UUID id) {
        var rows = jdbc.query("SELECT * FROM agent_tasks WHERE id=?", ROW, id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    AgentTask findKey(UUID project, String key) {
        var rows = jdbc.query("SELECT * FROM agent_tasks WHERE project_id=? AND idempotency_sha256=?", ROW, project, key);
        return rows.isEmpty() ? null : rows.get(0);
    }
    void reserveQueueSlot() {
        requireTransaction();
        jdbc.execute("SELECT pg_catalog.pg_advisory_xact_lock(718395201)");
        if (jdbc.queryForObject("SELECT count(*) FROM agent_tasks WHERE status='QUEUED'", Long.class) >= 8)
            throw AgentTask.conflict("AGENT_QUEUE_FULL");
    }
    AgentTask insert(UUID id, UUID owner, UUID project, UUID edit, AgentTask.Request request) {
        requireTransaction();
        return jdbc.queryForObject("""
                INSERT INTO agent_tasks(id,project_id,owner_id,edit_lease_id,base_version_id,base_revision,
                    idempotency_sha256,request_sha256,prompt,max_tokens,max_turns,task_timeout_seconds,queue_timeout_seconds)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *
                """, ROW, id, project, owner, edit, request.base(), request.revision(), request.key(), request.digest(),
                request.prompt(), request.tokens(), request.turns(), request.taskSeconds(), request.queueSeconds());
    }
    private static UUID uuid(ResultSet row, String column) throws SQLException { return row.getObject(column, UUID.class); }
    private static Instant instant(ResultSet row, String column) throws SQLException {
        var time = row.getTimestamp(column); return time == null ? null : time.toInstant();
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Agent admission requires a transaction");
    }
}
