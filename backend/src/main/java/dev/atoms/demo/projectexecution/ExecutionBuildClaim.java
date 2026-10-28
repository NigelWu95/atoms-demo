package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal, one-shot queue claim. No scheduling, external execution, source I/O or success settlement. */
final class ExecutionBuildClaim {
    private final ExecutionSettings settings;
    private final ProjectService projects;
    private final ExecutionBuildRepository builds;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    ExecutionBuildClaim(ExecutionSettings settings, ProjectService projects, ExecutionBuildRepository builds,
                        JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.settings = settings; this.projects = projects; this.builds = builds; this.jdbc = jdbc;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }

    /** Only the caller that commits QUEUED -> PREPARING may proceed; replay never grants another worker. */
    ExecutionBuild claim(UUID owner, UUID project, UUID build) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Execution claims must commit before worker I/O");
        if (project == null || build == null)
            throw new ApiException(422, "EXECUTION_INVALID_INPUT", "项目或构建标识无效。");
        try {
            return transaction.execute(tx -> {
                var config = settings.load();
                if (owner == null || !config.allows(owner))
                    throw new ApiException(403, "EXECUTION_FORBIDDEN", "当前身份没有完整工程执行权限。");
                if (projects.lock(owner, project.toString()).archived())
                    throw ExecutionBuild.conflict("EXECUTION_PROJECT_ARCHIVED");
                var rows = jdbc.queryForList("SELECT * FROM execution_builds WHERE project_id=? AND id=? FOR UPDATE", project, build);
                if (rows.isEmpty()) throw new ApiException(404, "EXECUTION_BUILD_NOT_FOUND", "构建不存在或无权访问。");
                var row = rows.get(0);
                if (!owner.equals(row.get("owner_id"))) throw ExecutionBuild.conflict("EXECUTION_BUILD_OWNER_CHANGED");
                if (!"QUEUED".equals(row.get("status")) || Boolean.TRUE.equals(row.get("execution_pending")))
                    throw ExecutionBuild.conflict("EXECUTION_BUILD_NOT_QUEUED");
                if (Boolean.TRUE.equals(row.get("cancel_requested"))) throw ExecutionBuild.conflict("EXECUTION_CANCELLED");
                builds.lockCapacity();
                int capacity = Math.min(config.maxConcurrentBuilds(), ((Number) row.get("worker_capacity")).intValue());
                var slots = jdbc.queryForList("""
                        SELECT slot FROM generate_series(1,?) AS slot
                        WHERE (SELECT count(*) FROM execution_task_slots WHERE worker_slot IS NOT NULL) < ?
                            AND NOT EXISTS(SELECT 1 FROM execution_task_slots WHERE worker_slot=slot)
                        ORDER BY slot LIMIT 1
                        """, Integer.class, capacity, capacity);
                if (slots.isEmpty()) throw new ApiException(429, "EXECUTION_WORKER_FULL", "构建执行容量已满，任务仍在排队。");
                // clock_timestamp advances while locks are awaited; now() is fixed at transaction start.
                // https://www.postgresql.org/docs/17/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT
                int changed = jdbc.update("""
                        UPDATE execution_builds SET status='PREPARING',queue_slot=null,worker_slot=?
                        WHERE id=? AND created_at + queue_timeout_seconds * interval '1 second' > clock_timestamp()
                        """, slots.get(0), build);
                if (changed != 1) throw ExecutionBuild.conflict("EXECUTION_QUEUE_TIMEOUT");
                // Fresh read includes the committed-in-this-transaction stage event's counters.
                return Objects.requireNonNull(builds.get(project, build));
            });
        } catch (DataAccessException error) {
            // The transaction rolls back its slot and event; no raw SQL or driver message escapes.
            throw new ApiException(503, "EXECUTION_STATE_UNAVAILABLE", "构建状态暂不可用，请检查任务状态。");
        }
    }
}
