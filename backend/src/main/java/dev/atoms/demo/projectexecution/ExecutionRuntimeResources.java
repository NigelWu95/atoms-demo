package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.source.ProjectSourceReadGateway;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** A single issuer/worker pair sharing the coordinator's root. It owns no executor or separate directory lease. */
final class ExecutionRuntimeResources {
    private final ExecutionRunService service;
    private final ExecutionRunWorker worker;
    private final OwnedProcess.Preflight control;

    ExecutionRuntimeResources(ExecutionSettings settings, JdbcTemplate jdbc, PlatformTransactionManager transactions,
            ProjectSourceReadGateway source, ExecutionPaths paths, ExecutionDatabaseBinding databases,
            List<Path> protectedRoots, OwnedProcess.Preflight control) {
        this.control = Objects.requireNonNull(control);
        var runs = new ExecutionRunRepository(jdbc);
        var identities = new ExecutionMigrationReviewRepository(jdbc);
        var migrations = new ExecutionMigrationReview(settings, identities, transactions, source, databases);
        service = new ExecutionRunService(settings, runs, identities, transactions, paths, databases, migrations);
        worker = ExecutionRunWorker.nativeWorker(settings, service, runs, transactions, paths, protectedRoots, databases, control);
    }

    ExecutionRunService.Admission submit(UUID owner, UUID project, UUID build, UUID binding, UUID review, String key) throws IOException {
        control.check(); return service.submit(owner, project, build, binding, review, key);
    }
    ExecutionRunService.Replacement replace(UUID owner, UUID project, UUID predecessor, UUID build, UUID binding, UUID review, String key) throws IOException {
        control.check(); return service.replace(owner, project, predecessor, build, binding, review, key);
    }
    ExecutionRun stop(UUID owner, UUID project, UUID run) throws IOException {
        control.check(); return service.stop(owner, project, run);
    }
    ExecutionRun execute(ExecutionRunService.Ticket ticket) throws IOException {
        // The worker consumes the fresh ticket before preflight, so revocation can still settle a never-issued intent.
        return worker.run(ticket);
    }
    boolean hasHeldProcesses() { return worker.hasHeldProcesses(); }
    void requestStopOwned() { worker.requestStopOwned(); }
}
