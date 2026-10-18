package dev.atoms.demo.source;

import dev.atoms.demo.engineering.EngineeringSnapshotService;
import dev.atoms.demo.engineering.TemplateSnapshot;
import java.util.UUID;

/** Explicit, authorized conversion only. The old artifact and its runtime data remain authoritative for the old flow. */
final class LegacySourceImporter {
    private final EngineeringSnapshotService snapshots;
    private final ProjectSourceService service;

    LegacySourceImporter(EngineeringSnapshotService snapshots, ProjectSourceService service) {
        this.snapshots = snapshots;
        this.service = service;
    }

    Prepared prepare(UUID owner, UUID project, UUID legacyVersion, String templateDigest, String key) {
        var version = snapshots.authorize(owner, project.toString(), legacyVersion.toString());
        if (templateDigest == null || !templateDigest.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("A frozen template digest is required");
        var snapshot = snapshots.assemble(version, templateDigest);
        var files = snapshot.bundle().files().stream()
                .filter(file -> !file.path().equals("bundle-manifest.json"))
                .map(file -> new SourceTree.File(file.path(), file.content(),
                        file.path().equals("backend/mvnw") ? 0100755 : 0100644)).toList();
        var tree = new ProjectSourcePolicy().validate(files);
        var operation = service.admit(owner, project, new SourceRequest("IMPORT", null, 0, null,
                legacyVersion, TemplateSnapshot.ID, snapshot.templateDigest(), tree.sha256()), key);
        return new Prepared(operation, tree);
    }

    record Prepared(SourceOperation operation, SourceTree tree) {}
}
