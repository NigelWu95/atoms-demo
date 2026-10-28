package dev.atoms.demo.source;

import java.util.UUID;

/** Controlled template/legacy adapters call this; no public upload or arbitrary path interface. */
final class SourceInitializer {
    private final ProjectSourceService service;
    SourceInitializer(ProjectSourceService service) { this.service = service; }
    SourceOperation request(UUID owner, UUID project, SourceTree input, String key) {
        return request(owner, project, input, null, null, key);
    }
    SourceOperation request(UUID owner, UUID project, SourceTree input, String templateId, String templateDigest, String key) {
        return request(owner, project, input, null, templateId, templateDigest, key);
    }
    SourceOperation request(UUID owner, UUID project, SourceTree input, UUID legacyVersion, String templateId, String templateDigest, String key) {
        SourceTree tree = new ProjectSourcePolicy().validate(input.files());
        if (!tree.sha256().equals(input.sha256()) || tree.totalBytes() != input.totalBytes())
            throw new IllegalArgumentException("Invalid frozen source tree");
        if ((templateId == null) != (templateDigest == null) || templateId != null && !tree.sha256().equals(templateDigest))
            throw new IllegalArgumentException("Invalid controlled template origin");
        return service.admit(owner, project, new SourceRequest(legacyVersion == null ? "INITIALIZE" : "IMPORT",
                null, 0, null, legacyVersion, templateId, templateDigest, tree.sha256()), key);
    }
}
