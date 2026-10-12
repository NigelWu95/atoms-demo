package dev.atoms.demo.engineering;

import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.artifact.ArtifactValidator;
import dev.atoms.demo.common.ApiException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Shared authorization/assembly boundary for exports and trusted platform builds. */
@Service
public class EngineeringSnapshotService {
    private final ArtifactService artifacts;
    private final EngineeringBundleAssembler assembler;
    private final Supplier<TemplateSnapshot> templates;

    @Autowired
    public EngineeringSnapshotService(ArtifactService artifacts, ArtifactValidator validator) {
        this(artifacts, validator, new TemplateCatalog()::load);
    }
    EngineeringSnapshotService(ArtifactService artifacts, ArtifactValidator validator, Supplier<TemplateSnapshot> templates) {
        this.artifacts = artifacts;
        this.assembler = new EngineeringBundleAssembler(validator);
        this.templates = templates;
    }

    public ArtifactService.Version authorize(UUID owner, String project, String version) {
        return artifacts.get(owner, project, version);
    }

    public String templateDigest() { return templates.get().digest(); }

    // Callers must obtain the immutable version via authorize before this internal method.
    public Snapshot assemble(ArtifactService.Version version, String expectedTemplateDigest) {
        var template = templates.get();
        String digest = template.digest();
        if (expectedTemplateDigest != null && !expectedTemplateDigest.equals(digest))
            throw new ApiException(409, "BUILD_INPUT_CHANGED", "工程模板已变化，请重新提交构建。");
        return new Snapshot(digest, assembler.assemble(
                new BundleSource(version.id(), version.contentHash(), version.artifact()), template));
    }

    public record Snapshot(String templateDigest, EngineeringBundle bundle) {}
}
