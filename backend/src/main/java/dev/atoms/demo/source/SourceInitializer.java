package dev.atoms.demo.source;

import java.util.UUID;

/** Controlled template/legacy adapters call this; no public upload or arbitrary path interface. */
final class SourceInitializer {
    private final ProjectSourceService service;
    SourceInitializer(ProjectSourceService service) { this.service = service; }
    SourceOperation request(UUID owner, UUID project, SourceTree input, String key) {
        SourceTree tree = new ProjectSourcePolicy().validate(input.files());
        if (!tree.sha256().equals(input.sha256()) || tree.totalBytes() != input.totalBytes())
            throw new IllegalArgumentException("Invalid frozen source tree");
        return service.admit(owner, project, new SourceRequest("INITIALIZE", null, 0, null, null, null, null, tree.sha256()), key);
    }
}
