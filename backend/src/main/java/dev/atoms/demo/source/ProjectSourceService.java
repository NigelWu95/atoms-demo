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
