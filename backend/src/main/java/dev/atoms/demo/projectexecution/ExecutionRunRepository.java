package dev.atoms.demo.projectexecution;

import java.nio.file.Path;
import java.util.*;
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

    /** Durable intent only: even an unclaimed ticket is settled by its worker, never by a caller's stop request. */
    ExecutionRun stop(UUID owner, UUID project, UUID id) {
        if (project == null || id == null) throw ExecutionRun.error(422, "RUN_INVALID_INPUT");
        lockProject(owner, project, true, true);
        var row = get(project, id);
        if (!row.ownerId().equals(owner)) throw ExecutionRun.error(404, "RUN_NOT_FOUND");
        if (row.finishedAt() != null || row.stopRequested()) return row;
        jdbc.update("UPDATE execution_runs SET stop_requested=true,status=CASE WHEN status='READY' THEN 'STOPPING' ELSE status END WHERE id=?", id);
        return get(project, id);
    }

    ExecutionRun admit(UUID owner, UUID project, ExecutionRun.Request request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Run admission requires a transaction");
        if (!ExecutionRun.valid(request)) throw ExecutionRun.error(422, "RUN_INVALID_INPUT");
        try {
            var previous = find(owner, project, request);
            if (previous != null) return previous;
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT FROM execution_runs WHERE project_id=? AND finished_at IS NULL)", Boolean.class, project)))
                throw ExecutionRun.error(409, "RUN_PROJECT_BUSY");
            if (request.predecessor() != null) {
                var old = get(project, request.predecessor());
                if (!old.ownerId().equals(owner) || !old.bindingId().equals(request.binding())) throw ExecutionRun.error(404, "RUN_NOT_FOUND");
                if (old.finishedAt() == null || old.executionPending()) throw ExecutionRun.error(409, "RUN_STOP_PENDING");
            }
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

    /** Must be called under the same project lock as a subsequent fresh admission. */
    ExecutionRun find(UUID owner, UUID project, ExecutionRun.Request request) {
        if (!ExecutionRun.valid(request)) throw ExecutionRun.error(422, "RUN_INVALID_INPUT");
        lockProject(owner, project, true);
        var existing = jdbc.query("SELECT * FROM execution_runs WHERE project_id=? AND idempotency_key_sha256=?", ROW, project, request.keyHash());
        if (existing.isEmpty()) return null;
        var previous = existing.get(0);
        if (!previous.ownerId().equals(owner) || !previous.requestSha256().equals(request.bodyHash()))
            throw ExecutionRun.error(409, "RUN_IDEMPOTENCY_CONFLICT");
        return previous;
    }

    /** A persisted intent is not permission to recreate a launcher. An already claimed row always refuses. */
    ExecutionRun begin(ExecutionRun expected, UUID execution) {
        var row = locked(expected, true);
        if (execution == null || !row.status().equals("STARTING") || row.executionId() != null || row.stopRequested()) reject();
        var identity = new ExecutionMigrationReviewRepository(jdbc).identity(row.ownerId(), row.projectId(), row.buildId());
        if (!identity.build().sourceVersionId().equals(row.sourceVersionId()) || !identity.build().treeSha256().equals(row.treeSha256())
                || !identity.jarSha256().equals(row.jarSha256()) || !identity.manifestSha256().equals(row.manifestSha256())
                || !identity.binding().id().equals(row.bindingId()) || !identity.binding().hash().equals(row.bindingIdentitySha256())) reject();
        jdbc.update("UPDATE execution_runs SET execution_id=?,execution_pending=true WHERE id=?", execution, row.id());
        return get(row.projectId(), row.id());
    }

    ExecutionRun ready(ExecutionRun expected, UUID execution, ExecutionBindingRepository.Target target, ExecutionHealth.Confirmed proof) {
        var row = locked(expected, true);
        var binding = new ExecutionBindingRepository(jdbc).get(row.projectId());
        if (proof == null || target == null || binding == null || !binding.target().equals(target)
                || !target.id().equals(row.bindingId()) || !target.hash().equals(row.bindingIdentitySha256())
                || !Objects.equals(execution, row.executionId()) || !row.executionPending() || row.stopRequested()
                || !Set.of("STARTING", "READY").contains(row.status()) || !proof.matches(row.id(), execution, target)) reject();
        if (row.status().equals("READY")) return row;
        jdbc.update("UPDATE execution_runs SET status='READY',ready_at=clock_timestamp() WHERE id=?", row.id());
        // Refuse a process that exited while the metadata update was being prepared; caller rolls back.
        if (!proof.matches(row.id(), execution, target)) reject();
        return get(row.projectId(), row.id());
    }

    /** Only the worker holding a fresh admission may use this, before any execution intent was issued. */
    ExecutionRun notStarted(ExecutionRun expected, String code) {
        var row = locked(expected, false);
        requireFailure(code);
        if (!row.status().equals("STARTING") || row.executionId() != null || row.executionPending()) reject();
        String status = code.equals("RUN_CANCELLED") ? "CANCELLED" : code.equals("RUN_START_TIMEOUT") ? "TIMED_OUT" : "FAILED";
        jdbc.update("UPDATE execution_runs SET status=?,error_code=?,finished_at=clock_timestamp() WHERE id=?", status, code, row.id());
        return get(row.projectId(), row.id());
    }

    /** Cleanup uses held stop evidence; current permission loss must not strand an owned process. */
    ExecutionRun finish(ExecutionRun expected, UUID execution, Path directory, ExecutionRunProcess.Outcome outcome, String failure) {
        var row = locked(expected, false);
        if (execution == null || !execution.equals(row.executionId()) || directory == null || outcome == null || outcome.output() == null
                || outcome.output().stdoutBytes() < 0 || outcome.output().stderrBytes() < 0
                || outcome.code() == null || !OUTCOMES.contains(outcome.code())) reject();
        if (failure != null) requireFailure(failure);
        var stopped = outcome.stopped();
        if (stopped != null && !stopped.matches(row.id(), execution, directory)) reject();
        if (row.finishedAt() != null) {
            if (stopped == null || !stopped.digest().equals(row.stoppedSha256())) reject();
            return row;
        }
        // Replaying a completion cannot silently turn an uncertain execution/data state into a terminal state.
        if (row.status().equals("NEEDS_RECONCILIATION")) return row;
        if (!row.executionPending()) reject();
        if (stopped == null || Set.of("RUN_PROCESS_UNSETTLED", "RUN_CHILD_PROCESS_REMAINS").contains(outcome.code())) {
            jdbc.update("UPDATE execution_runs SET status='NEEDS_RECONCILIATION',error_code='RUN_PROCESS_UNSETTLED' WHERE id=?", row.id());
            return get(row.projectId(), row.id());
        }
        String code = failure != null ? failure : outcome.code().equals("RUN_EXITED") ? "RUN_PROCESS_EXITED" : outcome.code();
        if (row.readyAt() == null) {
            // A process may have partially migrated before health failed. Stopped is not proof of unchanged business data.
            jdbc.update("""
                    UPDATE execution_runs SET status='NEEDS_RECONCILIATION',execution_pending=false,stopped_sha256=?,error_code=? WHERE id=?
                    """, stopped.digest(), code, row.id());
        } else {
            // V028 requires stop evidence before the terminal update. Both statements commit in the same transaction.
            jdbc.update("UPDATE execution_runs SET status='STOPPING',execution_pending=false,stopped_sha256=? WHERE id=?", stopped.digest(), row.id());
            boolean requestedStop = row.stopRequested() && failure == null && outcome.code().equals("RUN_STOPPED");
            jdbc.update("UPDATE execution_runs SET status=?,error_code=?,finished_at=clock_timestamp() WHERE id=?",
                    requestedStop ? "STOPPED" : "FAILED", requestedStop ? null : code, row.id());
        }
        return get(row.projectId(), row.id());
    }

    private ExecutionRun locked(ExecutionRun expected, boolean authorize) {
        if (expected == null) { reject(); return null; }
        lockProject(expected.ownerId(), expected.projectId(), authorize);
        var rows = jdbc.query("SELECT * FROM execution_runs WHERE project_id=? AND id=? FOR UPDATE", ROW, expected.projectId(), expected.id());
        if (rows.isEmpty()) { reject(); return null; }
        var row = rows.get(0);
        if (!row.ownerId().equals(expected.ownerId()) || !row.buildId().equals(expected.buildId())
                || !row.sourceVersionId().equals(expected.sourceVersionId()) || !row.bindingId().equals(expected.bindingId())
                || !Objects.equals(row.migrationReviewId(), expected.migrationReviewId()) || !row.treeSha256().equals(expected.treeSha256())
                || !row.jarSha256().equals(expected.jarSha256()) || !row.manifestSha256().equals(expected.manifestSha256())
                || !row.bindingIdentitySha256().equals(expected.bindingIdentitySha256()) || !row.keySha256().equals(expected.keySha256())
                || !row.requestSha256().equals(expected.requestSha256()) || !row.createdAt().equals(expected.createdAt())) reject();
        return row;
    }

    private void lockProject(UUID owner, UUID project, boolean authorize) {
        lockProject(owner, project, authorize, false);
    }
    private void lockProject(UUID owner, UUID project, boolean authorize, boolean allowArchived) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Run persistence requires a transaction");
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        var projects = jdbc.queryForList("SELECT owner_id,archived FROM projects WHERE id=? FOR UPDATE", project);
        if (projects.isEmpty() || authorize && (owner == null || !owner.equals(projects.get(0).get("owner_id"))))
            throw ExecutionRun.error(404, "PROJECT_NOT_FOUND");
        if (authorize && !allowArchived && Boolean.TRUE.equals(projects.get(0).get("archived"))) throw ExecutionRun.error(409, "RUN_PROJECT_ARCHIVED");
    }
    private static final Set<String> OUTCOMES = Set.of("RUN_EXITED", "RUN_STOPPED", "RUN_START_TIMEOUT", "RUN_START_FAILED",
            "RUN_PROCESS_FAILED", "RUN_CHILD_PROCESS_REMAINS", "RUN_PROCESS_UNSETTLED", "RUN_PREFLIGHT_FAILED", "RUN_EXECUTION_FAILED");
    private static void requireFailure(String code) {
        if (code == null || !Set.of("RUN_START_FAILED", "RUN_PORT_IN_USE", "RUN_START_TIMEOUT", "RUN_CANCELLED", "RUN_HEALTH_INVALID",
                "RUN_PREFLIGHT_FAILED", "RUN_AUTHORIZATION_CHANGED", "RUN_CONFIGURATION_CHANGED", "RUN_COORDINATOR_UNAVAILABLE").contains(code)) reject();
    }
    private static void reject() { throw ExecutionRun.error(409, "RUN_STATE_CONFLICT"); }
}
