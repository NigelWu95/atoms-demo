package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.ProjectSourceReadGateway;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal opt-in admission, query and cancellation. No HTTP endpoint or process launch. */
@Service
public final class ProjectExecutionService {
    private final ExecutionSettings settings;
    private final ExecutionBuildRepository builds;
    private final ProjectService projects;
    private final ProjectSourceReadGateway source;
    private final TransactionTemplate transaction;

    ProjectExecutionService(ExecutionSettings settings, ExecutionBuildRepository builds, ProjectService projects,
                            ProjectSourceReadGateway source, PlatformTransactionManager transactions) {
        this.settings = settings; this.builds = builds; this.projects = projects; this.source = source;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(5);
    }

    /**
     * Pin exactly the requested immutable version. Same project/key/body replays the original task.
     * No paths, ports, commands or database credentials are accepted. Must be called outside a DB transaction.
     * Worker admission must separately recheck owner/archive, storage, tools, policy and coordinator readiness.
     */
    public BuildView submit(UUID owner, UUID project, UUID sourceVersion, String key) {
        authorize(owner);
        if (project == null) throw new ApiException(422, "EXECUTION_INVALID_INPUT", "项目标识无效。");
        var request = ExecutionBuild.request(sourceVersion, key);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Source snapshot reading must not hold a database transaction");
        var previous = transaction.execute(tx -> existing(owner, project, request));
        if (previous != null) return view(previous);
        // Git and storage verification are outside locks. Never substitute the current/latest version.
        var snapshot = source.read(owner, project, request.version());
        return transaction.execute(tx -> {
            var config = authorize(owner);
            var replay = existing(owner, project, request);
            return view(replay != null ? replay : builds.insert(owner, project, request, snapshot.version().treeSha256(), config));
        });
    }

    public BuildView get(UUID owner, UUID project, UUID build) {
        authorize(owner);
        if (project == null || build == null) throw new ApiException(422, "EXECUTION_INVALID_INPUT", "项目或构建标识无效。");
        return transaction.execute(tx -> { lockProject(owner, project); return view(builds.get(project, build)); });
    }

    /** Commit the intent before returning. Only a never-claimed queued task can settle here. */
    public BuildView cancel(UUID owner, UUID project, UUID build) {
        authorize(owner);
        if (project == null || build == null) throw new ApiException(422, "EXECUTION_INVALID_INPUT", "项目或构建标识无效。");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Cancellation must commit independently before worker action");
        try {
            return transaction.execute(tx -> {
                // An archive prevents new work, but must not prevent stopping the owner's existing task.
                projects.lock(owner, project.toString());
                var current = builds.lock(project, build);
                authorize(owner); // Configuration may change while either row lock is awaited.
                if (!current.ownerId().equals(owner)) throw ExecutionBuild.conflict("EXECUTION_BUILD_OWNER_CHANGED");
                return view(builds.requestCancel(current));
            });
        } catch (DataAccessException | TransactionException error) {
            // Unknown commit outcomes are queried explicitly, never retried or exposed with driver details.
            throw new ApiException(503, "EXECUTION_STATE_UNAVAILABLE", "构建状态暂不可用，请查询任务状态。");
        }
    }

    private ExecutionBuild existing(UUID owner, UUID project, ExecutionBuild.Request request) {
        lockProject(owner, project);
        var existing = builds.findKey(project, request.keyHash());
        if (existing != null && !existing.requestSha256().equals(request.bodyHash()))
            throw ExecutionBuild.conflict("EXECUTION_IDEMPOTENCY_CONFLICT");
        if (existing == null) builds.requireIdle(project);
        return existing;
    }

    private void lockProject(UUID owner, UUID project) {
        if (projects.lock(owner, project.toString()).archived()) throw ExecutionBuild.conflict("EXECUTION_PROJECT_ARCHIVED");
    }

    private ExecutionSettings.Configuration authorize(UUID owner) {
        var config = settings.load();
        if (owner == null || !config.maintainerIds().contains(owner))
            throw new ApiException(403, "EXECUTION_FORBIDDEN", "当前身份没有完整工程执行权限。");
        return config;
    }

    public record BuildView(UUID id, UUID projectId, UUID sourceVersionId, String treeSha256, String status,
                            boolean cancelRequested, String errorCode, Instant createdAt, Instant finishedAt, int lastEventSeq) {}
    private static BuildView view(ExecutionBuild build) {
        return new BuildView(build.id(), build.projectId(), build.sourceVersionId(), build.treeSha256(), build.status(),
                build.cancelRequested(), build.errorCode(), build.createdAt(), build.finishedAt(), build.lastEventSeq());
    }
}
