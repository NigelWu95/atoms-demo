package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.*;

/** Internal admission, query and cancellation. No dependency tools or source publication occur here. */
@Service
public final class DependencyPreparationService {
    public record Input(UUID baseSourceVersionId, long expectedSourceRevision) {}
    public record TaskView(UUID id, UUID projectId, UUID editLeaseId, UUID baseVersionId, long baseRevision,
                           String treeSha256, String manifestSha256, String baseLockSha256, String policySha256,
                           String status, boolean cancelRequested, boolean executionPending, UUID sourceOperationId,
                           UUID resultVersionId, String errorCode, Instant createdAt, Instant finishedAt) {}
    private final ExecutionSettings settings;
    private final DependencyPreparationRepository repository;
    private final ProjectService projects;
    private final ProjectSourceReadGateway read;
    private final ProjectSourceEditGateway edits;
    private final ProjectSourceEnvironment sourceEnvironment;
    private final Environment environment;
    private final TransactionTemplate transaction;
    private final Map<UUID, ProjectSourceEditGateway.EditHandle> handles = new ConcurrentHashMap<>();

    DependencyPreparationService(ExecutionSettings settings, DependencyPreparationRepository repository, ProjectService projects,
            ProjectSourceReadGateway read, ProjectSourceEditGateway edits, ProjectSourceEnvironment sourceEnvironment,
            Environment environment, PlatformTransactionManager transactions) {
        this.settings = settings; this.repository = repository; this.projects = projects; this.read = read; this.edits = edits;
        this.sourceEnvironment = sourceEnvironment; this.environment = environment;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }

    public TaskView submit(UUID owner, UUID project, Input input, String key) {
        var config = authorize(owner);
        if (project == null) throw new ApiException(422, "EXECUTION_INVALID_INPUT", "项目标识无效。");
        var request = DependencyPreparationRepository.Request.from(input, key);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Preparation must read source outside transactions");
        try {
            var previous = transaction.execute(status -> existing(owner, project, request));
            if (previous != null) return view(previous);
            var budget = new ExecutionProcess.Deadline(Duration.ofSeconds(30));
            var snapshot = read.read(owner, project, request.version(), budget::remaining);
            var paths = sourceEnvironment.settings().paths();
            var excluded = new ArrayList<>(List.of(paths.root(), paths.workRoot(), config.root()));
            String agentRoot = environment.getProperty("atoms.agent.state-root", "");
            if (!agentRoot.isBlank()) excluded.add(Path.of(agentRoot));
            else if (environment.getProperty("atoms.agent.enabled", Boolean.class, false)) throw ExecutionSettings.unavailable();
            var policy = ExecutionApprovedPolicy.capture(config.policy(), excluded, budget);
            var approved = policy.npm().checkPreparation(snapshot.tree());
            policy.verify(budget);
            return transaction.execute(status -> {
                var replay = existing(owner, project, request);
                if (replay != null) return view(replay);
                if (!config.equals(authorize(owner))) throw ExecutionSettings.unavailable();
                UUID id = UUID.randomUUID();
                var edit = edits.claimEdit(owner, project, request.version(), request.revision(), id);
                var task = repository.insert(id, owner, project, edit.leaseId(), request, snapshot.version().treeSha256(), approved, config);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        handles.compute(id, (ignored, old) -> repository.get(project, id).finishedAt() == null ? edit : null);
                    }
                });
                return view(task);
            });
        } catch (DataAccessException error) {
            throw new ApiException(503, "EXECUTION_STATE_UNAVAILABLE", "依赖准备状态暂不可用，请重试或查询已有任务。");
        } catch (IOException error) {
            String code = error.getMessage();
            if (!Set.of("EXECUTION_TIMEOUT", "EXECUTION_CANCELLED", "EXECUTION_DEPENDENCY_POLICY_INVALID",
                    "EXECUTION_NPM_APPROVAL_REQUIRED", "EXECUTION_NPM_POLICY_REJECTED").contains(Objects.toString(code, "")))
                code = "EXECUTION_DEPENDENCY_POLICY_INVALID";
            throw new ApiException(422, code, "依赖准备输入或维护者批准无效。");
        }
    }

    public TaskView get(UUID owner, UUID project, UUID task) {
        authorize(owner);
        if (project == null || task == null) throw new ApiException(422, "EXECUTION_INVALID_INPUT", "项目或准备任务标识无效。");
        return transaction.execute(status -> {
            // Archived projects remain queryable while their existing work is being stopped.
            projects.lock(owner, project.toString()); authorize(owner);
            return view(repository.get(project, task));
        });
    }

    /** Commit intent before returning; only a never-claimed task with its original capability can settle here. */
    public TaskView cancel(UUID owner, UUID project, UUID task) {
        authorize(owner);
        if (project == null || task == null) throw new ApiException(422, "EXECUTION_INVALID_INPUT", "项目或准备任务标识无效。");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Cancellation must commit independently before worker action");
        try {
            return transaction.execute(status -> {
                projects.lock(owner, project.toString());
                var current = repository.lock(project, task);
                authorize(owner);
                if (!owner.equals(current.ownerId())) throw ExecutionBuild.conflict("EXECUTION_PREPARATION_OWNER_CHANGED");
                if (current.finishedAt() != null) return view(current);
                var edit = handle(task);
                if (edit != null) {
                    if (!project.equals(edit.projectId()) || !current.editLeaseId().equals(edit.leaseId()) || !task.equals(edit.taskId()))
                        throw ExecutionBuild.conflict("EXECUTION_PREPARATION_EDIT_UNAVAILABLE");
                    var source = edits.cancelEdit(owner, edit);
                    // A durable publication is already successful; its worker/recovery will settle that exact operation.
                    if (source.operation() != null && source.operation().status().equals("COMMITTED")) return view(current);
                    if (current.status().equals("QUEUED")) {
                        edits.releaseSettledEdit(edit);
                        var result = repository.cancelQueued(current);
                        forgetAfterCommit(task, edit);
                        return view(result);
                    }
                }
                // No capability after restart: record intent without reconstructing authority or releasing the lease.
                return view(repository.requestCancel(current));
            });
        } catch (DataAccessException | TransactionException error) {
            throw new ApiException(503, "EXECUTION_STATE_UNAVAILABLE", "依赖准备状态暂不可用，请查询任务状态。");
        }
    }
    private DependencyPreparationRepository.Task existing(UUID owner, UUID project, DependencyPreparationRepository.Request request) {
        authorize(owner); lock(owner, project); authorize(owner);
        var task = repository.findKey(project, request.keyHash());
        if (task != null) {
            if (!task.requestSha256().equals(request.bodyHash())) throw ExecutionBuild.conflict("EXECUTION_IDEMPOTENCY_CONFLICT");
            return task;
        }
        repository.requireIdle(project);
        return null;
    }
    private ExecutionSettings.Configuration authorize(UUID owner) {
        var config = settings.load();
        if (owner == null || !config.allows(owner))
            throw new ApiException(403, "EXECUTION_FORBIDDEN", "当前身份没有完整工程执行权限。");
        return config;
    }
    private void lock(UUID owner, UUID project) {
        if (project == null) throw new ApiException(422, "EXECUTION_INVALID_INPUT", "项目标识无效。");
        if (projects.lock(owner, project.toString()).archived()) throw ExecutionBuild.conflict("EXECUTION_PROJECT_ARCHIVED");
    }
    ProjectSourceEditGateway.EditHandle handle(UUID task) { return handles.get(task); }
    void forgetAfterCommit(UUID task, ProjectSourceEditGateway.EditHandle expected) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Preparation capability release requires a transaction");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { handles.remove(task, expected); }
        });
    }
    static TaskView view(DependencyPreparationRepository.Task task) {
        return new TaskView(task.id(), task.projectId(), task.editLeaseId(), task.baseVersionId(), task.baseRevision(),
                task.treeSha256(), task.manifestSha256(), task.baseLockSha256(), task.policySha256(), task.status(),
                task.cancelRequested(), task.executionPending(), task.sourceOperationId(), task.resultVersionId(), task.errorCode(),
                task.createdAt(), task.finishedAt());
    }
}
