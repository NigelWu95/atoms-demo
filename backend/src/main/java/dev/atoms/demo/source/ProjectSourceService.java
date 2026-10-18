package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.Project;
import dev.atoms.demo.project.ProjectService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Source publication boundary. No database transaction may include Git or filesystem work. */
@Service
public class ProjectSourceService {
    private final ProjectService projects;
    private final SourceRepository repository;
    private final TransactionTemplate transaction;
    private final ProjectSourceEnvironment environment;

    ProjectSourceService(ProjectService projects, SourceRepository repository, PlatformTransactionManager transactions,
            ProjectSourceEnvironment environment) {
        this.projects = projects; this.repository = repository; this.transaction = new TransactionTemplate(transactions); this.environment = environment;
    }

    SourceOperation admit(UUID owner, UUID projectId, SourceRequest request, String idempotencyKey) {
        requireEnabled();
        String key = SourceRequest.key(idempotencyKey), digest = request.digest();
        return transaction.execute(status -> {
            Project project = projects.lock(owner, projectId.toString());
            writable(project);
            SourceOperation existing = repository.findByKey(projectId, key);
            if (existing != null) {
                if (!existing.requestSha256().equals(digest)) throw conflict("SOURCE_IDEMPOTENCY_CONFLICT");
                return existing;
            }
            repository.ensureSource(projectId);
            var source = repository.state(projectId);
            requireBase(source, request.expectedVersionId(), request.expectedRevision());
            if (request.targetVersionId() != null && repository.version(projectId, request.targetVersionId()) == null)
                throw conflict("SOURCE_VERSION_MISSING");
            repository.reserveQueueSlot();
            return repository.insertIntent(owner, projectId, request, key);
        });
    }

    public SourceOperation operation(UUID owner, UUID projectId, UUID operationId) {
        requireEnabled();
        projects.get(owner, projectId.toString());
        var value = repository.operation(projectId, operationId);
        if (value == null) throw new ApiException(404, "SOURCE_OPERATION_MISSING", "源码操作不存在。");
        return value;
    }

    void authorize(UUID owner, UUID project) {
        requireEnabled();
        projects.get(owner, project.toString());
    }

    void writableBase(UUID owner, UUID project, UUID version, long revision) {
        requireEnabled();
        transaction.executeWithoutResult(status -> {
            writable(projects.lock(owner, project.toString()));
            var state = repository.state(project);
            if (state == null || version == null || revision < 1) throw conflict("SOURCE_BASE_CONFLICT");
            requireBase(state, version, revision);
        });
    }

    void storageUnavailable(UUID owner, UUID project) {
        transaction.executeWithoutResult(status -> {
            projects.lock(owner, project.toString());
            repository.fence(project);
        });
    }

    SourceOperation prepare(UUID project, UUID id) {
        return transaction.execute(status -> {
            var operation = lockedOperation(project, id);
            if (operation.terminal()) return operation;
            writable(projects.lock(operation.ownerId(), project.toString()));
            requireBase(repository.state(project), operation.expectedVersionId(), operation.expectedRevision());
            if (!operation.status().equals("PREPARING") || operation.executionId() != null) throw conflict("SOURCE_NEEDS_RECONCILIATION");
            return operation.cancelRequested() ? repository.finish(operation, "CANCELLED", "SOURCE_CANCELLED") : operation;
        });
    }

    SourceOperation ready(UUID project, UUID id) {
        return transaction.execute(status -> {
            var operation = lockedOperation(project, id);
            if (operation.terminal()) return operation;
            writable(projects.lock(operation.ownerId(), project.toString()));
            requireBase(repository.state(project), operation.expectedVersionId(), operation.expectedRevision());
            if (operation.cancelRequested()) return repository.finish(operation, "CANCELLED", "SOURCE_CANCELLED");
            return repository.ready(operation);
        });
    }

    SourceOperation beginExecution(UUID project, UUID id) {
        return transaction.execute(status -> {
            var operation = lockedOperation(project, id);
            if (operation.terminal()) return operation;
            writable(projects.lock(operation.ownerId(), project.toString()));
            requireBase(repository.state(project), operation.expectedVersionId(), operation.expectedRevision());
            if (operation.cancelRequested()) return repository.finish(operation, "CANCELLED", "SOURCE_CANCELLED");
            if (operation.executionId() != null || !operation.status().equals("PREPARING")) throw conflict("SOURCE_NEEDS_RECONCILIATION");
            return repository.beginExecution(operation, UUID.randomUUID());
        });
    }

    void executionSettled(UUID project, UUID id, UUID execution) {
        transaction.executeWithoutResult(status -> {
            var operation = lockedOperation(project, id);
            if (execution == null || !execution.equals(operation.executionId())) throw conflict("SOURCE_NEEDS_RECONCILIATION");
            repository.executionSettled(operation, execution);
        });
    }

    SourceOperation publish(UUID project, UUID id, SourceGitStore.Stored stored, SourceTree tree) {
        return transaction.execute(status -> {
            var operation = lockedOperation(project, id);
            if (operation.terminal()) return operation;
            writable(projects.lock(operation.ownerId(), project.toString()));
            requireBase(repository.state(project), operation.expectedVersionId(), operation.expectedRevision());
            if (operation.cancelRequested()) return repository.finish(operation, "CANCELLED", "SOURCE_CANCELLED");
            if (!operation.status().equals("CANDIDATE_READY") || !operation.candidateVersionId().equals(stored.versionId())
                    || !operation.candidateSha256().equals(stored.treeSha256()) || !tree.sha256().equals(stored.treeSha256()))
                throw conflict("SOURCE_INPUT_CHANGED");
            repository.insertVersion(operation, stored, tree);
            repository.publishPointer(operation);
            return repository.committed(operation);
        });
    }

    SourceOperation fail(UUID project, UUID id, String code, boolean uncertain) {
        return transaction.execute(status -> {
            var operation = lockedOperation(project, id);
            if (operation.terminal()) return operation;
            return repository.finish(operation, uncertain || operation.executionPending() ? "NEEDS_RECONCILIATION" : "FAILED", code);
        });
    }

    SourceOperation recovered(SourceOperation observed) {
        return transaction.execute(status -> {
            var current = lockedOperation(observed.projectId(), observed.id());
            if (current.terminal()) return current;
            if (current.executionPending() || !current.status().equals(observed.status()) || repository.version(current.projectId(), current.candidateVersionId()) != null)
                throw conflict("SOURCE_NEEDS_RECONCILIATION");
            var result = repository.finish(current, current.cancelRequested() ? "CANCELLED" : "FAILED",
                    current.cancelRequested() ? "SOURCE_CANCELLED" : "SOURCE_INTERRUPTED");
            repository.clearReconciledFence(current.projectId());
            return result;
        });
    }

    SourceOperation uncertain(SourceOperation observed) {
        return transaction.execute(status -> {
            var current = lockedOperation(observed.projectId(), observed.id());
            repository.fence(current.projectId());
            return current.terminal() ? current : repository.finish(current, "NEEDS_RECONCILIATION", "SOURCE_STORAGE_UNAVAILABLE");
        });
    }

    private SourceOperation lockedOperation(UUID project, UUID id) {
        repository.lockEvidence(project);
        var operation = repository.operation(project, id);
        if (operation == null) throw new ApiException(404, "SOURCE_OPERATION_MISSING", "源码操作不存在。");
        return operation;
    }

    private void requireEnabled() {
        if (!environment.enabled()) throw new ApiException(503, "SOURCE_DISABLED", "完整工程源码管理尚未启用。");
    }
    static void writable(Project project) {
        if (project.archived()) throw conflict("SOURCE_PROJECT_ARCHIVED");
    }
    static void requireBase(SourceRepository.State source, UUID version, long revision) {
        if (source.status().equals("NEEDS_RECONCILIATION")) throw conflict("SOURCE_NEEDS_RECONCILIATION");
        if (source.revision() != revision || !java.util.Objects.equals(source.versionId(), version)) throw conflict("SOURCE_BASE_CONFLICT");
    }
    static ApiException conflict(String code) { return new ApiException(409, code, "源码操作未执行，请刷新版本或检查操作状态。"); }
}
