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

    /** Trusted package-only composition seam for actual worker/PG lifecycle tests; never accepted from an API. */
    ExecutionRuntimeResources(ExecutionRunService service, ExecutionRunWorker worker, OwnedProcess.Preflight control) {
        this.service = Objects.requireNonNull(service); this.worker = Objects.requireNonNull(worker);
        this.control = Objects.requireNonNull(control);
    }

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
    ExecutionRun existing(UUID owner, UUID project, ExecutionRun.Request request) throws IOException {
        control.check(); return service.existing(owner, project, request);
    }
    ExecutionRunService.Replacement prepareReplacement(UUID owner, UUID project, ExecutionRun.Request request) throws IOException {
        control.check(); return service.prepareReplacement(owner, project, request);
    }
    ExecutionRunService.Admission submit(UUID owner, UUID project, ExecutionRun.Request request) throws IOException {
        control.check(); return service.submit(owner, project, request);
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
    ExecutionRunProcess ownedProcess(UUID run) { return worker.ownedProcess(run); }
    void requestStopOwned() { worker.requestStopOwned(); }
}
