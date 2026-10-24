package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Settles only never-claimed work. Claiming and stopping actual workers belong to their executor. */
final class ExecutionBuildQueue {
    private final ExecutionSettings settings;
    private final ExecutionBuildRepository builds;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ExecutionCoordinatorLease lease;

    ExecutionBuildQueue(ExecutionSettings settings, ExecutionBuildRepository builds, JdbcTemplate jdbc,
                        PlatformTransactionManager transactions, ExecutionCoordinatorLease lease) {
        this.settings = settings; this.builds = builds; this.jdbc = jdbc; this.lease = lease;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }

    List<ExecutionBuild> waiting() throws IOException {
        try {
            requireIndependent(); requireLease();
            var result = List.copyOf(builds.waiting());
            requireLease();
            return result;
        } catch (RuntimeException error) { throw unavailable(); }
    }

    /** A QUEUED result is only a candidate; the worker still has to claim it atomically. */
    ExecutionBuild beforeDispatch(ExecutionBuild candidate) throws IOException { return settle(candidate, false); }

    /** Shutdown may cancel queued work after configuration is disabled, while still owning coordination. */
    ExecutionBuild cancelWaiting(ExecutionBuild candidate) throws IOException { return settle(candidate, true); }

    private ExecutionBuild settle(ExecutionBuild candidate, boolean stopping) throws IOException {
        try {
            requireIndependent(); requireLease();
            if (!stopping) settings.load();
            return transaction.execute(tx -> {
                // Lock the project even after an owner/archive change; no filesystem or process work here.
                jdbc.execute("SET LOCAL lock_timeout = '3s'");
                var project = jdbc.queryForMap("SELECT owner_id,archived FROM projects WHERE id=? FOR UPDATE", candidate.projectId());
                var current = builds.lock(candidate.projectId(), candidate.id());
                requireSameIdentity(candidate, current);
                var config = stopping ? null : settings.load();
                ExecutionBuild result = current;
                if (current.status().equals("QUEUED")) {
                    if (stopping || current.cancelRequested()) result = builds.requestCancel(current);
                    else if (Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT created_at + queue_timeout_seconds * interval '1 second' <= clock_timestamp()
                            FROM execution_builds WHERE id=?
                            """, Boolean.class, current.id())))
                        result = finish(current, "TIMED_OUT", "EXECUTION_QUEUE_TIMEOUT");
                    else if (!current.ownerId().equals(project.get("owner_id")))
                        result = finish(current, "FAILED", "EXECUTION_BUILD_OWNER_CHANGED");
                    else if (Boolean.TRUE.equals(project.get("archived")))
                        result = finish(current, "FAILED", "EXECUTION_PROJECT_ARCHIVED");
                    else if (!config.maintainerIds().contains(current.ownerId()))
                        result = finish(current, "FAILED", "EXECUTION_FORBIDDEN");
                }
                // A lost observation must roll back state, capacity and the trigger's event together.
                requireLease();
                return result;
            });
        } catch (RuntimeException error) { throw unavailable(); }
    }

    private ExecutionBuild finish(ExecutionBuild current, String status, String code) {
        if (jdbc.update("""
                UPDATE execution_builds SET status=?,error_code=?,queue_slot=null,finished_at=clock_timestamp()
                WHERE id=? AND status='QUEUED'
                """, status, code, current.id()) != 1) throw new IllegalStateException();
        return builds.get(current.projectId(), current.id());
    }

    private static void requireSameIdentity(ExecutionBuild candidate, ExecutionBuild current) {
        if (!current.ownerId().equals(candidate.ownerId()) || !current.sourceVersionId().equals(candidate.sourceVersionId())
                || !current.treeSha256().equals(candidate.treeSha256()) || !current.keySha256().equals(candidate.keySha256())
                || !current.requestSha256().equals(candidate.requestSha256()) || !current.createdAt().equals(candidate.createdAt()))
            throw new IllegalStateException();
    }

    private void requireLease() { if (!lease.healthy()) throw new IllegalStateException(); }
    private static void requireIndependent() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException();
    }
    private static IOException unavailable() { return new IOException("EXECUTION_STATE_UNAVAILABLE"); }
}
