package dev.atoms.demo.engineering;

import dev.atoms.demo.artifact.Artifact;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A frozen source supplied only after the caller has authorized access to this version. */
public record BundleSource(UUID versionId, String contentHash, Artifact artifact) {
    public BundleSource {
        Objects.requireNonNull(versionId, "Source version is required");
        if (contentHash == null || !contentHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Source content hash must be a SHA-256 digest");
        artifact = copy(artifact);
    }
    @Override public Artifact artifact() { return copy(artifact); }

    private static Artifact copy(Artifact artifact) {
        var manifest = artifact.manifest();
        return new Artifact(artifact.formatVersion(), new Artifact.Manifest(manifest.title(), manifest.summary(),
                manifest.runtime(), manifest.stateSchemaVersion(), manifest.initialState().deepCopy()), Map.copyOf(artifact.files()));
    }
}
