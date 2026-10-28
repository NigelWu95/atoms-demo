package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.ProjectSourceEditGateway;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal task boundary; no model invocation, directory I/O or HTTP route is admitted here. */
@Service
public class ProjectAgentService {
    private final AgentSettings settings;
    private final AgentTaskRepository repository;
    private final ProjectService projects;
    private final ProjectSourceEditGateway source;
    private final TransactionTemplate transaction;
    private final ConcurrentHashMap<UUID, ProjectSourceEditGateway.EditHandle> handles = new ConcurrentHashMap<>();

    ProjectAgentService(AgentSettings settings, AgentTaskRepository repository, ProjectService projects,
                        ProjectSourceEditGateway source, PlatformTransactionManager transactions) {
        this.settings = settings; this.repository = repository; this.projects = projects; this.source = source;
        this.transaction = new TransactionTemplate(transactions);
    }
    public record Input(String prompt, String baseSourceVersionId, long expectedSourceRevision) {
        @Override public String toString() { return "AgentInput[redacted]"; }
    }
    public record TaskView(UUID id, UUID projectId, String status, boolean cancelRequested, UUID baseSourceVersionId,
                           long baseSourceRevision, UUID sourceOperationId, UUID resultVersionId, boolean liveReserved,
                           String errorCode, Instant createdAt, Instant startedAt, Instant finishedAt) {}

    public TaskView submit(UUID owner, UUID project, Input input, String key) {
        var config = authorizeMaintainer(owner);
        if (input == null) throw AgentTask.invalid();
        var request = AgentTask.request(input.prompt(), input.baseSourceVersionId(), input.expectedSourceRevision(), key, config);
        return transaction.execute(status -> {
            var current = projects.lock(owner, project.toString());
            if (current.archived()) throw AgentTask.conflict("AGENT_PROJECT_ARCHIVED");
            var previous = repository.findKey(project, request.key());
            if (previous != null) {
                if (!previous.requestSha256().equals(request.digest())) throw AgentTask.conflict("AGENT_IDEMPOTENCY_CONFLICT");
                return view(previous);
            }
            repository.reserveQueueSlot();
            UUID id = UUID.randomUUID();
            var edit = source.claimEdit(owner, project, request.base(), request.revision(), id);
            var task = repository.insert(id, owner, project, edit.leaseId(), request);
            // The queue cannot observe a task capability until its database transaction commits.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    // A dispatcher may settle the row between commit and this callback.
                    handles.compute(id, (ignored, previousHandle) -> repository.get(id).terminal() ? null : edit);
                }
            });
            return view(task);
        });
    }

    public TaskView get(UUID owner, UUID project, UUID taskId) {
        authorizeMaintainer(owner); projects.get(owner, project.toString());
        var task = repository.get(taskId);
        if (task == null || !task.projectId().equals(project)) throw new ApiException(404, "AGENT_TASK_MISSING", "任务不存在或当前账号无权访问。");
        return view(task);
    }

    public TaskView cancel(UUID owner, UUID project, UUID taskId) {
        authorizeMaintainer(owner);
        return transaction.execute(status -> {
            projects.lock(owner, project.toString());
            var task = repository.get(taskId);
            if (task == null || !task.projectId().equals(project))
                throw new ApiException(404, "AGENT_TASK_MISSING", "任务不存在或当前账号无权访问。");
            task = repository.lock(taskId);
            if (task.terminal()) return view(task);
            var edit = handles.get(taskId);
            if (edit == null) throw AgentTask.conflict("AGENT_EDIT_UNAVAILABLE");
            task = repository.requestCancel(task);
            var cancelled = source.cancelEdit(owner, edit);
            if (cancelled.operation() != null && cancelled.operation().status().equals("COMMITTED")) {
                task = repository.bindSource(task, cancelled.operation().id());
                return view(repository.sourceSaved(task, cancelled.operation().resultVersionId()));
            }
            // Only QUEUED is known to have no active preparation. A running owner must confirm its own exit.
            if (task.status().equals("QUEUED") && !cancelled.executionPending()) {
                source.releaseEdit(edit); forgetAfterCommit(taskId);
                return view(repository.finish(task, "CANCELLED", "AGENT_CANCELLED"));
            }
            return view(task);
        });
    }
    AgentSettings.Configuration authorizeMaintainer(UUID owner) {
        var config = settings.load();
        if (owner == null || !config.allows(owner))
            throw new ApiException(403, "AGENT_NOT_ALLOWED", "当前账号未获准使用完整工程 Agent。");
        return config;
    }
    ProjectSourceEditGateway.EditHandle handle(UUID task) { return handles.get(task); }
    void discardSettled(UUID id) {
        handles.computeIfPresent(id, (ignored, handle) -> repository.get(id).terminal() ? null : handle);
    }
    void forgetAfterCommit(UUID task) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Task release requires a transaction");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { handles.remove(task); }
        });
    }
    static TaskView view(AgentTask task) {
        return new TaskView(task.id(), task.projectId(), task.status(), task.cancelRequested(), task.baseVersionId(), task.baseRevision(),
                task.sourceOperationId(), task.resultVersionId(), task.liveReserved(), task.errorCode(), task.createdAt(), task.startedAt(), task.finishedAt());
    }
}
