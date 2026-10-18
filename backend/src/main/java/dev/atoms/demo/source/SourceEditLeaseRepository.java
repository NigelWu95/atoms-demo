package dev.atoms.demo.source;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Source-owned durable exclusion. Mutators require the caller's short transaction; no Agent dependency. */
@Repository
class SourceEditLeaseRepository {
    private final JdbcTemplate jdbc;
    private final SourceRepository sources;
    SourceEditLeaseRepository(JdbcTemplate jdbc, SourceRepository sources) { this.jdbc = jdbc; this.sources = sources; }

    record Lease(UUID id, UUID projectId, UUID ownerId, UUID taskId, UUID baseVersionId, long baseRevision,
                 String status, UUID executionId, boolean executionPending, String stoppedSha256, Instant createdAt) {}
    private static final RowMapper<Lease> ROW = (r, n) -> new Lease(r.getObject("id", UUID.class),
            r.getObject("project_id", UUID.class), r.getObject("owner_id", UUID.class), r.getObject("task_id", UUID.class),
            r.getObject("base_version_id", UUID.class), r.getLong("base_revision"), r.getString("status"),
            r.getObject("execution_id", UUID.class), r.getBoolean("execution_pending"), r.getString("stopped_sha256"),
            r.getTimestamp("created_at").toInstant());

    Lease active(UUID project) {
        var rows = jdbc.query("SELECT * FROM source_edit_leases WHERE project_id=? AND status<>'RELEASED'", ROW, project);
        return rows.isEmpty() ? null : rows.get(0);
    }
    Lease get(UUID project, UUID id) {
        var rows = jdbc.query("SELECT * FROM source_edit_leases WHERE project_id=? AND id=?", ROW, project, id);
        if (rows.isEmpty()) throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
        return rows.get(0);
    }

    Lease claim(UUID owner, UUID project, UUID base, long revision, UUID task) {
        locked(project);
        Boolean allowed = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM projects WHERE id=? AND owner_id=? AND NOT archived)",
                Boolean.class, project, owner);
        if (!Boolean.TRUE.equals(allowed)) throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
        var state = sources.state(project);
        if (state == null || base == null || revision < 1) throw ProjectSourceService.conflict("SOURCE_BASE_CONFLICT");
        ProjectSourceService.requireBase(state, base, revision);
        if (active(project) != null || Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM source_operations WHERE project_id=?
                    AND (status NOT IN ('COMMITTED','FAILED','CANCELLED') OR execution_pending))
                """, Boolean.class, project))) throw ProjectSourceService.conflict("SOURCE_EDIT_BUSY");
        return jdbc.queryForObject("""
                INSERT INTO source_edit_leases(id,project_id,owner_id,task_id,base_version_id,base_revision)
                VALUES (?,?,?,?,?,?) RETURNING *
                """, ROW, UUID.randomUUID(), project, owner, task, base, revision);
    }

    Lease beginExecution(Lease lease, UUID execution) {
        locked(lease.projectId());
        if (execution == null || jdbc.update("""
                UPDATE source_edit_leases SET execution_id=?,execution_pending=true
                WHERE id=? AND status='ACTIVE' AND execution_id IS NULL
                """, execution, lease.id()) != 1) throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
        return get(lease.projectId(), lease.id());
    }

    void confirmStopped(Lease lease, UUID execution, String digest) {
        locked(lease.projectId());
        if (execution == null || digest == null || !digest.matches("[a-f0-9]{64}"))
            throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
        Lease current = get(lease.projectId(), lease.id());
        if (execution.equals(current.executionId()) && digest.equals(current.stoppedSha256()) && !current.executionPending()) return;
        if (jdbc.update("""
                UPDATE source_edit_leases SET execution_pending=false,stopped_sha256=?
                WHERE id=? AND execution_id=? AND execution_pending AND status<>'RELEASED'
                """, digest, lease.id(), execution) != 1) throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
    }

    void release(Lease lease) {
        locked(lease.projectId());
        Lease current = get(lease.projectId(), lease.id());
        if (current.status().equals("RELEASED")) return;
        if (current.executionPending() || Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM source_operations WHERE edit_lease_id=?
                    AND (status NOT IN ('COMMITTED','FAILED','CANCELLED') OR execution_pending))
                """, Boolean.class, lease.id()))) throw ProjectSourceService.conflict("SOURCE_EDIT_BUSY");
        jdbc.update("UPDATE source_edit_leases SET status='RELEASED',released_at=now() WHERE id=?", lease.id());
    }

    private void locked(UUID project) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Source edit requires a transaction");
        sources.lockEvidence(project);
    }
}
