package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.ProjectSourceEditGateway;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One-shot metadata claim. The original source capability is required; no process or workspace is opened. */
final class DependencyPreparationClaim {
    private final ExecutionSettings settings;
    private final ProjectService projects;
    private final DependencyPreparationRepository preparations;
    private final ExecutionBuildRepository capacity;
    private final ProjectSourceEditGateway edits;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    DependencyPreparationClaim(ExecutionSettings settings, ProjectService projects, DependencyPreparationRepository preparations,
            ExecutionBuildRepository capacity, ProjectSourceEditGateway edits, JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.settings = settings; this.projects = projects; this.preparations = preparations;
        this.capacity = capacity; this.edits = edits; this.jdbc = jdbc;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }

    DependencyPreparationRepository.Task claim(UUID owner, UUID project, UUID id, ProjectSourceEditGateway.EditHandle edit) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Preparation claims must commit before worker I/O");
        if (project == null || id == null) throw new ApiException(422, "EXECUTION_INVALID_INPUT", "项目或准备任务标识无效。");
        if (edit == null || !id.equals(edit.taskId()) || !project.equals(edit.projectId()))
            throw ExecutionBuild.conflict("EXECUTION_PREPARATION_EDIT_UNAVAILABLE");
        try {
            return transaction.execute(tx -> {
                jdbc.execute("SET LOCAL lock_timeout = '3s'");
                var config = settings.load();
                if (owner == null || !config.maintainerIds().contains(owner))
                    throw new ApiException(403, "EXECUTION_FORBIDDEN", "当前身份没有完整工程执行权限。");
                if (projects.lock(owner, project.toString()).archived()) throw ExecutionBuild.conflict("EXECUTION_PROJECT_ARCHIVED");
                var rows = jdbc.queryForList("SELECT * FROM dependency_preparations WHERE project_id=? AND id=? FOR UPDATE", project, id);
                if (rows.isEmpty()) throw new ApiException(404, "EXECUTION_PREPARATION_NOT_FOUND", "依赖准备任务不存在或无权访问。");
                var row = rows.get(0);
                if (!owner.equals(row.get("owner_id"))) throw ExecutionBuild.conflict("EXECUTION_PREPARATION_OWNER_CHANGED");
                if (!edit.leaseId().equals(row.get("edit_lease_id"))) throw ExecutionBuild.conflict("EXECUTION_PREPARATION_EDIT_UNAVAILABLE");
                if (!"QUEUED".equals(row.get("status")) || Boolean.TRUE.equals(row.get("execution_pending")))
                    throw ExecutionBuild.conflict("EXECUTION_PREPARATION_NOT_QUEUED");
                if (Boolean.TRUE.equals(row.get("cancel_requested"))) throw ExecutionBuild.conflict("EXECUTION_CANCELLED");
                edits.requireUnstartedEdit(edit);
                capacity.lockCapacity();
                int limit = Math.min(config.maxConcurrentBuilds(), ((Number) row.get("worker_capacity")).intValue());
                var slots = jdbc.queryForList("""
                        SELECT slot FROM generate_series(1,?) slot
                        WHERE (SELECT count(*) FROM execution_task_slots WHERE worker_slot IS NOT NULL) < ?
                            AND NOT EXISTS(SELECT 1 FROM execution_task_slots WHERE worker_slot=slot)
                        ORDER BY slot LIMIT 1
                        """, Integer.class, limit, limit);
                if (slots.isEmpty()) throw new ApiException(429, "EXECUTION_WORKER_FULL", "执行容量已满，准备任务仍在排队。");
                if (!config.equals(settings.load())) throw ExecutionSettings.unavailable();
                // Admission's immutable timeout and the database wall clock also cover time spent waiting for locks.
                int changed = jdbc.update("""
                        UPDATE dependency_preparations SET status='PREPARING',queue_slot=null,worker_slot=?
                        WHERE id=? AND created_at + queue_timeout_seconds * interval '1 second' > clock_timestamp()
                        """, slots.get(0), id);
                if (changed != 1) throw ExecutionBuild.conflict("EXECUTION_QUEUE_TIMEOUT");
                return preparations.get(project, id);
            });
        } catch (DataAccessException error) {
            throw new ApiException(503, "EXECUTION_STATE_UNAVAILABLE", "依赖准备状态暂不可用，请查询已有任务。");
        }
    }
}
