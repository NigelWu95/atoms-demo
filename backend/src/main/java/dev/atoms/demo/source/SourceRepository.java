package dev.atoms.demo.source;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Internal persistence only; callers must authorize before querying. Never contains source bytes. */
@Repository
class SourceRepository {
    private final JdbcTemplate jdbc;
    SourceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    static final RowMapper<SourceVersion> VERSION = (r, n) -> new SourceVersion(uuid(r, "id"), uuid(r, "project_id"),
            r.getLong("version_no"), r.getString("commit_id"), r.getString("tree_sha256"), r.getInt("file_count"), r.getLong("tree_bytes"),
            uuid(r, "based_on_id"), uuid(r, "restored_from_id"), uuid(r, "legacy_version_id"), r.getString("template_id"),
            r.getString("template_digest"), instant(r, "created_at"));
    static final RowMapper<SourceOperation> OPERATION = (r, n) -> new SourceOperation(uuid(r, "id"), uuid(r, "project_id"),
            uuid(r, "owner_id"), r.getString("kind"), r.getString("idempotency_sha256"), r.getString("request_sha256"),
            uuid(r, "expected_version_id"), r.getLong("expected_revision"), uuid(r, "candidate_version_id"), uuid(r, "target_version_id"),
            uuid(r, "legacy_version_id"), r.getString("template_id"), r.getString("template_digest"), r.getString("candidate_sha256"),
            r.getString("status"), r.getBoolean("cancel_requested"), uuid(r, "result_version_id"), r.getString("error_code"),
            instant(r, "created_at"), instant(r, "finished_at"));

    SourceVersion version(UUID project, UUID id) {
        return first(jdbc.query("SELECT * FROM source_versions WHERE project_id=? AND id=?", VERSION, project, id));
    }
    SourceOperation operation(UUID project, UUID id) {
        return first(jdbc.query("SELECT * FROM source_operations WHERE project_id=? AND id=?", OPERATION, project, id));
    }
    SourceOperation findByKey(UUID project, String key) {
        return first(jdbc.query("SELECT * FROM source_operations WHERE project_id=? AND idempotency_sha256=?", OPERATION, project, key));
    }
    record State(UUID projectId, UUID versionId, long revision, String status) {}
    State state(UUID project) {
        return first(jdbc.query("SELECT * FROM project_sources WHERE project_id=?", (r, n) -> new State(uuid(r, "project_id"),
                uuid(r, "current_source_version_id"), r.getLong("source_revision"), r.getString("status")), project));
    }
    void ensureSource(UUID project) {
        jdbc.update("INSERT INTO project_sources(project_id) VALUES (?) ON CONFLICT DO NOTHING", project);
    }
    void reserveQueueSlot() {
        // One fixed transaction-scoped namespace prevents cross-project admission from exceeding the global bound.
        jdbc.execute("SELECT pg_advisory_xact_lock(718395104)");
        Integer pending = jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE status IN ('PREPARING','CANDIDATE_READY','NEEDS_RECONCILIATION')", Integer.class);
        if (pending == null || pending >= 64) throw ProjectSourceService.conflict("SOURCE_CAPACITY_EXCEEDED");
    }
    SourceOperation insertIntent(UUID owner, UUID project, SourceRequest request, String key) {
        return jdbc.queryForObject("""
                INSERT INTO source_operations(id,project_id,owner_id,kind,idempotency_sha256,request_sha256,expected_version_id,
                    expected_revision,candidate_version_id,target_version_id,legacy_version_id,template_id,template_digest,candidate_sha256)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *
                """, OPERATION, UUID.randomUUID(), project, owner, request.kind(), key, request.digest(), request.expectedVersionId(),
                request.expectedRevision(), UUID.randomUUID(), request.targetVersionId(), request.legacyVersionId(), request.templateId(), request.templateDigest(), request.treeSha256());
    }
    private static UUID uuid(ResultSet result, String field) throws SQLException { return result.getObject(field, UUID.class); }
    private static Instant instant(ResultSet result, String field) throws SQLException {
        var value = result.getTimestamp(field); return value == null ? null : value.toInstant();
    }
    private static <T> T first(List<T> rows) { return rows.isEmpty() ? null : rows.get(0); }
}
