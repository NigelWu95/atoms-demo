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
            instant(r, "created_at"), instant(r, "finished_at"), uuid(r, "execution_id"), r.getBoolean("execution_pending"));

    SourceVersion version(UUID project, UUID id) {
        return first(jdbc.query("SELECT * FROM source_versions WHERE project_id=? AND id=?", VERSION, project, id));
    }
    List<SourceVersion> versions(UUID project, Long beforeVersion, int limit) {
        return List.copyOf(beforeVersion == null
                ? jdbc.query("SELECT * FROM source_versions WHERE project_id=? ORDER BY version_no DESC LIMIT ?", VERSION, project, limit)
                : jdbc.query("SELECT * FROM source_versions WHERE project_id=? AND version_no<? ORDER BY version_no DESC LIMIT ?",
                        VERSION, project, beforeVersion, limit));
    }
    SourceOperation operation(UUID project, UUID id) {
        return first(jdbc.query("SELECT * FROM source_operations WHERE project_id=? AND id=?", OPERATION, project, id));
    }
    SourceOperation findByKey(UUID project, String key) {
        return first(jdbc.query("SELECT * FROM source_operations WHERE project_id=? AND idempotency_sha256=?", OPERATION, project, key));
    }
    List<SourceOperation> pending() {
        var rows = jdbc.query("SELECT * FROM source_operations WHERE status IN ('PREPARING','CANDIDATE_READY','NEEDS_RECONCILIATION') ORDER BY created_at,id LIMIT 65", OPERATION);
        if (rows.size() > 64) throw ProjectSourceService.conflict("SOURCE_CAPACITY_EXCEEDED");
        return rows;
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
    void lockEvidence(UUID project) {
        jdbc.execute("SET LOCAL lock_timeout='3s'");
        jdbc.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, project);
    }
    SourceOperation beginExecution(SourceOperation operation, UUID execution) {
        int updated = jdbc.update("UPDATE source_operations SET execution_id=?,execution_pending=true WHERE id=? AND execution_id IS NULL AND status='PREPARING'",
                execution, operation.id());
        if (updated != 1) throw ProjectSourceService.conflict("SOURCE_NEEDS_RECONCILIATION");
        return operation(operation.projectId(), operation.id());
    }
    void executionSettled(SourceOperation operation, UUID execution) {
        jdbc.update("UPDATE source_operations SET execution_pending=false WHERE id=? AND execution_id=?", operation.id(), execution);
    }
    SourceOperation ready(SourceOperation operation) {
        jdbc.update("UPDATE source_operations SET status='CANDIDATE_READY' WHERE id=? AND status='PREPARING'", operation.id());
        return operation(operation.projectId(), operation.id());
    }
    SourceOperation requestCancel(SourceOperation operation) {
        jdbc.update("UPDATE source_operations SET cancel_requested=true WHERE id=?", operation.id());
        return operation(operation.projectId(), operation.id());
    }
    SourceOperation finish(SourceOperation operation, String status, String code) {
        boolean uncertain = status.equals("NEEDS_RECONCILIATION");
        if (uncertain) jdbc.update("UPDATE project_sources SET status='NEEDS_RECONCILIATION' WHERE project_id=?", operation.projectId());
        jdbc.update("UPDATE source_operations SET status=?,error_code=?,finished_at=CASE WHEN ? THEN NULL ELSE now() END WHERE id=?",
                status, code, uncertain, operation.id());
        return operation(operation.projectId(), operation.id());
    }
    void insertVersion(SourceOperation operation, SourceGitStore.Stored stored, SourceTree tree) {
        jdbc.update("""
                INSERT INTO source_versions(id,project_id,version_no,commit_id,tree_sha256,file_count,tree_bytes,based_on_id,
                    restored_from_id,legacy_version_id,template_id,template_digest)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, operation.candidateVersionId(), operation.projectId(), operation.expectedRevision() + 1,
                stored.commitId(), tree.sha256(), tree.files().size(), tree.totalBytes(), operation.expectedVersionId(),
                operation.targetVersionId(), operation.legacyVersionId(), operation.templateId(), operation.templateDigest());
    }
    void publishPointer(SourceOperation operation) {
        int updated = jdbc.update("""
                UPDATE project_sources SET current_source_version_id=?,source_revision=source_revision+1
                WHERE project_id=? AND source_revision=? AND current_source_version_id IS NOT DISTINCT FROM ? AND status='READY'
                """, operation.candidateVersionId(), operation.projectId(), operation.expectedRevision(), operation.expectedVersionId());
        if (updated != 1) throw ProjectSourceService.conflict("SOURCE_BASE_CONFLICT");
    }
    SourceOperation committed(SourceOperation operation) {
        jdbc.update("UPDATE source_operations SET status='COMMITTED',result_version_id=candidate_version_id,finished_at=now() WHERE id=?",
                operation.id());
        return operation(operation.projectId(), operation.id());
    }
    void fence(UUID project) { jdbc.update("UPDATE project_sources SET status='NEEDS_RECONCILIATION' WHERE project_id=?", project); }
    void clearReconciledFence(UUID project) {
        jdbc.update("""
                UPDATE project_sources SET status='READY' WHERE project_id=? AND NOT EXISTS
                    (SELECT 1 FROM source_operations WHERE project_id=? AND status='NEEDS_RECONCILIATION')
                """, project, project);
    }
    private static UUID uuid(ResultSet result, String field) throws SQLException { return result.getObject(field, UUID.class); }
    private static Instant instant(ResultSet result, String field) throws SQLException {
        var value = result.getTimestamp(field); return value == null ? null : value.toInstant();
    }
    private static <T> T first(List<T> rows) { return rows.isEmpty() ? null : rows.get(0); }
}
