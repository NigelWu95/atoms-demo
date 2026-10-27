package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Narrow restoration boundary; publication, CAS, idempotency and recovery stay in the source module. */
@Service
public final class ProjectSourceRestorationGateway {
    private final ProjectSourceService service;
    private final SourceRepository repository;
    private final SourceRestoration restoration;
    private final Supplier<SourceCoordinator> coordinator;

    @Autowired
    ProjectSourceRestorationGateway(ProjectSourceService service, SourceRepository repository,
            ProjectSourceEnvironment environment, ObjectProvider<SourceCoordinator> coordinator) {
        this(service, repository, environment, (Supplier<SourceCoordinator>) coordinator::getIfAvailable);
    }
    ProjectSourceRestorationGateway(ProjectSourceService service, SourceRepository repository,
            ProjectSourceEnvironment environment, Supplier<SourceCoordinator> coordinator) {
        this.service = service; this.repository = repository; this.coordinator = coordinator;
        restoration = new SourceRestoration(service, repository, new SourceQuery(service, repository, environment));
    }

    public Head head(UUID owner, UUID project) {
        service.authorize(owner, project);
        var state = repository.state(project);
        service.authorize(owner, project);
        return state == null ? new Head(project, null, 0, "EMPTY")
                : new Head(project, state.versionId(), state.revision(), state.status());
    }

    public Operation restore(UUID owner, UUID project, UUID target, UUID expected, long revision, String key) {
        if (target == null || expected == null || revision < 1) throw new ApiException(422, "INVALID_INPUT", "请提供目标版本及当前源码版本和修订号。");
        SourceRequest.key(key);
        service.authorize(owner, project);
        var current = coordinator.get();
        if (current == null || !current.isRunning()) throw new ApiException(503, "SOURCE_COORDINATOR_UNAVAILABLE", "源码服务尚未就绪，请稍后重试。");
        var prepared = restoration.prepare(owner, project, target, expected, revision, key);
        // Replays of persisted terminal/running work do not schedule a second source operation.
        if (prepared.tree() == null) return view(prepared.operation());
        return view(current.submit(owner, project, prepared.operation().id(), prepared.tree()).operation());
    }

    public Operation operation(UUID owner, UUID project, UUID id) {
        var operation = service.operation(owner, project, id);
        if (!operation.kind().equals("RESTORE")) throw new ApiException(404, "SOURCE_OPERATION_MISSING", "源码恢复任务不存在。");
        return view(operation);
    }

    private static Operation view(SourceOperation operation) {
        return new Operation(operation.id(), operation.projectId(), operation.expectedVersionId(), operation.expectedRevision(),
                operation.targetVersionId(), operation.status(), operation.resultVersionId(), operation.errorCode(),
                operation.createdAt(), operation.finishedAt());
    }
    public record Head(UUID projectId, UUID sourceVersionId, long revision, String status) {}
    public record Operation(UUID operationId, UUID projectId, UUID expectedSourceVersionId, long expectedRevision,
            UUID targetSourceVersionId, String status, UUID resultSourceVersionId, String errorCode, Instant createdAt, Instant finishedAt) {}
}
