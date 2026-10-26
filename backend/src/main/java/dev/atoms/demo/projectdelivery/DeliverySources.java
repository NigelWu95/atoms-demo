package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.projectexecution.ProjectExecutionAccess;
import dev.atoms.demo.source.SourceExecutionBudget;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Reads the chosen immutable source; packaging never consults a latest version or a template. */
@Service
final class DeliverySources {
    private final DeliveryAccess access;
    private final ProjectExecutionAccess execution;

    DeliverySources(DeliveryAccess access, ProjectExecutionAccess execution) {
        this.access = access; this.execution = execution;
    }

    void authorize(UUID owner, UUID project, String session, SourceExecutionBudget budget) {
        access.management(owner, session, budget);
        execution.authorizeProject(owner, project, budget);
    }

    BuildView inspect(UUID owner, UUID project, UUID build, String session, SourceExecutionBudget budget) {
        var configuration = access.management(owner, session, budget);
        var handle = execution.build(owner, project, build, budget);
        var identity = execution.identity(owner, project, handle, budget);
        var snapshot = execution.source(owner, project, handle, budget);
        if (!identity.equals(execution.identity(owner, project, handle, budget))) throw changed();
        unchanged(owner, session, configuration, budget);
        return new BuildView(identity, snapshot.version().fileCount(), snapshot.version().treeBytes());
    }

    Archive build(UUID owner, UUID project, UUID build, String session, SourceExecutionBudget budget) {
        var configuration = access.management(owner, session, budget);
        var handle = execution.build(owner, project, build, budget);
        var identity = execution.identity(owner, project, handle, budget);
        var snapshot = execution.source(owner, project, handle, budget);
        byte[] bytes = DeliveryZip.create(snapshot, identity, null, budget);
        if (!identity.equals(execution.identity(owner, project, handle, budget))) throw changed();
        unchanged(owner, session, configuration, budget);
        return new Archive("source-" + identity.buildId() + ".zip", bytes);
    }

    Archive preview(UUID owner, UUID project, UUID preview, String session, SourceExecutionBudget budget) {
        var grant = access.owned(owner, project, preview, session, budget);
        var snapshot = execution.source(owner, project, grant.handle(), budget);
        byte[] bytes = DeliveryZip.create(snapshot, grant.identity().build(), grant.identity(), budget);
        access.validate(grant, budget);
        return new Archive("preview-" + grant.id() + ".zip", bytes);
    }

    private void unchanged(UUID owner, String session, DeliverySettings.Configuration original, SourceExecutionBudget budget) {
        if (!original.equals(access.management(owner, session, budget))) throw changed();
    }
    private static ApiException changed() {
        return new ApiException(409, "DELIVERY_SOURCE_CHANGED", "交付身份或配置已变化，请重新选择构建后下载。");
    }
    record BuildView(ProjectExecutionAccess.BuildIdentity identity, int fileCount, long sourceBytes) {}
    record Archive(String filename, byte[] bytes) {}
}
