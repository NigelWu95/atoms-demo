package dev.atoms.demo.source;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Read boundary for other modules; never accepts filesystem paths or arbitrary Git objects. */
@Service
public final class ProjectSourceReadGateway {
    private final SourceQuery query;
    private final ProjectSourceEnvironment environment;
    private final Supplier<SourceCoordinator> coordinator;

    ProjectSourceReadGateway(ProjectSourceService service, SourceRepository repository,
                             ProjectSourceEnvironment environment) {
        this(service, repository, environment, (Supplier<SourceCoordinator>) () -> null);
    }

    @Autowired
    ProjectSourceReadGateway(ProjectSourceService service, SourceRepository repository,
                             ProjectSourceEnvironment environment, ObjectProvider<SourceCoordinator> coordinator) {
        this(service, repository, environment, (Supplier<SourceCoordinator>) coordinator::getIfAvailable);
    }

    ProjectSourceReadGateway(ProjectSourceService service, SourceRepository repository,
                             ProjectSourceEnvironment environment, Supplier<SourceCoordinator> coordinator) {
        this.query = new SourceQuery(service, repository, environment);
        this.environment = environment;
        this.coordinator = coordinator;
    }

    /** Lifecycle signal only; does not authorize execution, read storage or acquire a coordinator lease. */
    public boolean readyForExecution() {
        try {
            if (!environment.enabled()) return false;
            var current = coordinator.get();
            return current != null && current.isRunning();
        } catch (RuntimeException unavailable) { return false; }
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

    /** Shares the caller's remaining time; never expands the source module's own operation limit. */
    public Snapshot read(UUID owner, UUID project, UUID version, SourceExecutionBudget budget) {
        java.util.Objects.requireNonNull(budget);
        var deadline = new SourceDeadline(environment.operationTimeout(), System::nanoTime, budget);
        var snapshot = query.snapshot(owner, project, version, deadline);
        return new Snapshot(snapshot.version(), snapshot.tree());
    }

    /** SourceTree defensively copies its file list and each file's byte content. */
    public record Snapshot(SourceVersion version, SourceTree tree) {}
}
