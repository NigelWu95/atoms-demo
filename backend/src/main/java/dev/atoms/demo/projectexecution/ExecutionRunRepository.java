package dev.atoms.demo.projectexecution;

import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal admission persistence. Caller must verify maintainer settings, files, DB state and migration policy before use. */
final class ExecutionRunRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<ExecutionRun> ROW = (rs, n) -> new ExecutionRun(
            rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getObject("owner_id", UUID.class),
            rs.getObject("build_id", UUID.class), rs.getObject("source_version_id", UUID.class), rs.getObject("binding_id", UUID.class),
            rs.getObject("migration_review_id", UUID.class), rs.getString("tree_sha256"), rs.getString("jar_sha256"), rs.getString("manifest_sha256"),
            rs.getString("binding_identity_sha256"), rs.getString("idempotency_key_sha256"), rs.getString("request_sha256"), rs.getString("status"),
            rs.getBoolean("stop_requested"), rs.getObject("execution_id", UUID.class), rs.getBoolean("execution_pending"), rs.getString("stopped_sha256"),
            rs.getString("error_code"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("ready_at") == null ? null : rs.getTimestamp("ready_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
    ExecutionRunRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    ExecutionRun get(UUID project, UUID id) {
        return jdbc.query("SELECT * FROM execution_runs WHERE project_id=? AND id=?", ROW, project, id).stream().findFirst()
                .orElseThrow(() -> ExecutionRun.error(404, "RUN_NOT_FOUND"));
    }

    ExecutionRun admit(UUID owner, UUID project, ExecutionRun.Request request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Run admission requires a transaction");
        if (!ExecutionRun.valid(request)) throw ExecutionRun.error(422, "RUN_INVALID_INPUT");
        try {
            jdbc.execute("SET LOCAL lock_timeout = '3s'");
            var projects = jdbc.queryForList("SELECT owner_id,archived FROM projects WHERE id=? FOR UPDATE", project);
            if (projects.isEmpty() || owner == null || !owner.equals(projects.get(0).get("owner_id"))) throw ExecutionRun.error(404, "PROJECT_NOT_FOUND");
            if (Boolean.TRUE.equals(projects.get(0).get("archived"))) throw ExecutionRun.error(409, "RUN_PROJECT_ARCHIVED");
            var existing = jdbc.query("SELECT * FROM execution_runs WHERE project_id=? AND idempotency_key_sha256=?", ROW, project, request.keyHash());
            if (!existing.isEmpty()) {
                var previous = existing.get(0);
                if (!previous.ownerId().equals(owner) || !previous.requestSha256().equals(request.bodyHash())) throw ExecutionRun.error(409, "RUN_IDEMPOTENCY_CONFLICT");
                return previous;
            }
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT FROM execution_runs WHERE project_id=? AND finished_at IS NULL)", Boolean.class, project)))
                throw ExecutionRun.error(409, "RUN_PROJECT_BUSY");
            var builds = jdbc.queryForList("SELECT * FROM execution_builds WHERE project_id=? AND id=? AND status='SUCCEEDED'", project, request.build());
            if (builds.isEmpty()) throw ExecutionRun.error(409, "RUN_BUILD_NOT_READY");
            var build = builds.get(0); var binding = new ExecutionBindingRepository(jdbc).get(project);
            if (binding == null || !binding.target().id().equals(request.binding())) throw ExecutionRun.error(409, "RUN_DB_BINDING_CONFLICT");
            if (request.review() != null && !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT FROM execution_migration_reviews WHERE id=? AND project_id=? AND build_id=? AND binding_id=? AND jar_sha256=?)
                    """, Boolean.class, request.review(), project, request.build(), request.binding(), build.get("jar_sha256"))))
                throw ExecutionRun.error(409, "RUN_MIGRATION_REVIEW_REQUIRED");
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO execution_runs(id,project_id,owner_id,build_id,source_version_id,binding_id,migration_review_id,
                        tree_sha256,jar_sha256,manifest_sha256,binding_identity_sha256,idempotency_key_sha256,request_sha256)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, project, owner, request.build(), build.get("source_version_id"), request.binding(), request.review(),
                    build.get("tree_sha256"), build.get("jar_sha256"), build.get("manifest_sha256"), binding.target().hash(), request.keyHash(), request.bodyHash());
            return get(project, id);
        } catch (DataAccessException error) { throw ExecutionRun.error(503, "RUN_STATE_UNAVAILABLE"); }
    }
}
