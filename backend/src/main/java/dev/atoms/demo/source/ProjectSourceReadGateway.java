package dev.atoms.demo.source;

import java.util.UUID;
import org.springframework.stereotype.Service;

/** Read boundary for other modules; never accepts filesystem paths or arbitrary Git objects. */
@Service
public final class ProjectSourceReadGateway {
    private final SourceQuery query;

    ProjectSourceReadGateway(ProjectSourceService service, SourceRepository repository,
                             ProjectSourceEnvironment environment) {
        this.query = new SourceQuery(service, repository, environment);
    }

    /**
     * Returns the complete published version after ownership and storage-integrity checks.
     * Must run outside a database transaction. Reading neither initializes nor executes a project.
     * Consumers must authorize later actions again; the returned value is not an execution permit.
     */
    public Snapshot read(UUID owner, UUID project, UUID version) {
        var snapshot = query.snapshot(owner, project, version);
        return new Snapshot(snapshot.version(), snapshot.tree());
    }

    /** SourceTree defensively copies its file list and each file's byte content. */
    public record Snapshot(SourceVersion version, SourceTree tree) {}
}
