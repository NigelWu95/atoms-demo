package dev.atoms.demo.engineering;

import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.artifact.ArtifactValidator;
import dev.atoms.demo.common.ApiException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class EngineeringExportService {
    private final EngineeringSnapshotService snapshots;
    private final Semaphore slots = new Semaphore(2);

    @Autowired
    public EngineeringExportService(EngineeringSnapshotService snapshots) { this.snapshots = snapshots; }
    public EngineeringExportService(ArtifactService artifacts, ArtifactValidator validator) {
        this(new EngineeringSnapshotService(artifacts, validator));
    }
    EngineeringExportService(ArtifactService artifacts, ArtifactValidator validator, Supplier<TemplateSnapshot> templates) {
        this(new EngineeringSnapshotService(artifacts, validator, templates));
    }
    public record Download(String filename, byte[] bytes) {}

    public Download download(UUID owner, String projectId, String versionId) {
        // Keep authorization/database failures outside the packaging error boundary.
        var version = snapshots.authorize(owner, projectId, versionId);
        if (!slots.tryAcquire()) throw new ApiException(503, "EXPORT_BUSY", "工程下载繁忙，请稍后重试。");
        try {
            byte[] bytes = EngineeringZip.write(snapshots.assemble(version, null).bundle());
            return new Download("atoms-" + version.projectId() + "-v" + version.versionNo() + "-engineering.zip", bytes);
        } catch (ApiException error) {
            throw EngineeringBundle.rejected();
        } catch (IOException | RuntimeException error) {
            // Never attach the cause: it may contain filesystem paths or source data.
            throw new ApiException(500, "EXPORT_FAILED", "工程打包未完成，请稍后重试。");
        } finally { slots.release(); }
    }
}
