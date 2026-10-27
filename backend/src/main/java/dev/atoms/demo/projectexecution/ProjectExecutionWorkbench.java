package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** UI commands stay on the original coordinator. No paths, credentials, process adoption or new workers. */
@Service
public final class ProjectExecutionWorkbench {
    private final ExecutionQuery authorization;
    private final ExecutionMigrationReviewRepository identities;
    private final ExecutionBindingRepository bindings;
    private final TransactionTemplate transaction;
    private final Supplier<ExecutionCoordinator> coordinator;
    private final Semaphore inspections = new Semaphore(2);

    @Autowired
    ProjectExecutionWorkbench(ExecutionQuery authorization, JdbcTemplate jdbc, PlatformTransactionManager transactions,
            ObjectProvider<ExecutionCoordinator> coordinator) {
        this(authorization, jdbc, transactions, (Supplier<ExecutionCoordinator>) coordinator::getIfAvailable);
    }
    ProjectExecutionWorkbench(ExecutionQuery authorization, JdbcTemplate jdbc, PlatformTransactionManager transactions,
            Supplier<ExecutionCoordinator> coordinator) {
        this.authorization = authorization; identities = new ExecutionMigrationReviewRepository(jdbc);
        bindings = new ExecutionBindingRepository(jdbc); this.coordinator = coordinator;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }
    public record Availability(boolean coordinatorReady, boolean databaseBound) {}
    public record MigrationFile(String path, String sha256, boolean applied) {}
    public record Migration(UUID projectId, UUID buildId, UUID sourceVersionId, UUID bindingId, String historySha256,
                            String targetSha256, String decision, List<MigrationFile> files) {}
    public record Approval(UUID approvalId) {}
    public record Start(UUID buildId, UUID bindingId, UUID approvalId, UUID predecessorId) {}

    public Availability availability(UUID owner, UUID project) {
        return guarded(() -> {
            authorization.authorizeProject(owner, project);
            boolean bound = bindings.get(project) != null;
            var current = coordinator.get(); authorization.authorizeProject(owner, project);
            return new Availability(current != null && current.isRunning(), bound);
        });
    }
    public Migration migration(UUID owner, UUID project, UUID build) {
        return inspect(owner, project, build, (scope, identity, artifact, budget) -> {
            var inspection = scope.migrationReview().inspectForWorkbench(owner, project, build, artifact, budget);
            var view = inspection.view(); var files = new ArrayList<MigrationFile>();
            for (int i = 0; i < inspection.migrations().size(); i++) {
                var file = inspection.migrations().get(i);
                files.add(new MigrationFile(ExecutionMigrations.SOURCE + file.script(), file.sha256(), i < inspection.appliedCount()));
            }
            return new Migration(project, build, identity.build().sourceVersionId(), view.bindingId(), view.historySha256(),
                    view.targetSha256(), view.decision().name(), List.copyOf(files));
        });
    }
    public Approval approve(UUID owner, UUID project, UUID build, String history, String target) {
        if (history == null || target == null || !history.matches("[a-f0-9]{64}") || !target.matches("[a-f0-9]{64}"))
            throw ExecutionRun.error(422, "RUN_INVALID_INPUT");
        // The provider's durable identity (owner/build/artifact/history/target) makes exact approval replay idempotent.
        return inspect(owner, project, build, (scope, identity, artifact, budget) -> new Approval(
                scope.migrationReview().approve(owner, project, build, history, target, artifact, budget)));
    }
    public CompletionStage<ExecutionQuery.TaskView> start(UUID owner, UUID project, Start input, String key) {
        return guarded(() -> {
            authorization.authorizeProject(owner, project);
            if (input == null) throw ExecutionRun.error(422, "RUN_INVALID_INPUT");
            var current = current();
            var pending = input.predecessorId() == null
                    ? current.startApplication(owner, project, input.buildId(), input.bindingId(), input.approvalId(), key)
                    : current.replaceApplication(owner, project, input.predecessorId(), input.buildId(), input.bindingId(), input.approvalId(), key);
            return pending.handle((run, failure) -> guarded(() -> {
                if (failure != null) throw safe(failure);
                authorization.authorizeProject(owner, project); return view(run);
            }));
        });
    }
    public ExecutionQuery.TaskView stop(UUID owner, UUID project, UUID run) {
        return guarded(() -> {
            authorization.authorizeProject(owner, project); var result = current().stopApplication(owner, project, run);
            authorization.authorizeProject(owner, project); return view(result);
        });
    }
    private <T> T inspect(UUID owner, UUID project, UUID build, Inspection<T> action) {
        return guarded(() -> {
            authorization.authorizeProject(owner, project);
            if (!inspections.tryAcquire()) throw ExecutionRun.error(429, "RUN_INSPECTION_BUSY");
            try {
                var scope = current().accessScope(); var budget = budget();
                var identity = transaction.execute(tx -> identities.identity(owner, project, build));
                var artifact = scope.artifact(identity.build(), identity.jarSha256(), identity.manifestSha256(), budget);
                var result = action.apply(scope, identity, artifact, budget);
                scope.check(); authorization.authorizeProject(owner, project); return result;
            } finally { inspections.release(); }
        });
    }
    private ExecutionCoordinator current() {
        var current = coordinator.get();
        if (current == null || !current.isRunning()) throw ExecutionRun.error(503, "RUN_COORDINATOR_UNAVAILABLE");
        return current;
    }
    private static ExecutionQuery.TaskView view(ExecutionRun run) {
        return new ExecutionQuery.TaskView(run.id(), run.projectId(), ExecutionQuery.Kind.RUN, run.sourceVersionId(), run.buildId(), null,
                ExecutionDiagnostics.status(run.status()), false, run.stopRequested(), run.executionPending(),
                ExecutionDiagnostics.errorCode(run.errorCode()), run.createdAt(), run.finishedAt());
    }
    private static OwnedProcess.Budget budget() {
        long started = System.nanoTime();
        return () -> {
            long left = Duration.ofSeconds(30).toNanos() - (System.nanoTime() - started);
            if (left <= 0 || Thread.currentThread().isInterrupted()) throw new IOException("EXECUTION_TIMEOUT");
            return Duration.ofNanos(left);
        };
    }
    @FunctionalInterface private interface Inspection<T> {
        T apply(ExecutionCoordinator.AccessScope scope, ExecutionMigrationReviewRepository.Identity identity,
                ExecutionArtifact artifact, OwnedProcess.Budget budget) throws IOException;
    }
    @FunctionalInterface private interface Work<T> { T run() throws IOException; }
    private static <T> T guarded(Work<T> work) {
        try { return work.run(); } catch (IOException | RuntimeException failure) { throw safe(failure); }
    }
    private static ApiException safe(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        String code = failure instanceof ApiException api ? api.code() : failure instanceof IOException ? failure.getMessage() : "";
        if (Set.of("PROJECT_NOT_FOUND", "EXECUTION_DISABLED", "EXECUTION_FORBIDDEN", "EXECUTION_QUERY_UNAVAILABLE", "EXECUTION_INVALID_INPUT",
                "RUN_INVALID_INPUT", "RUN_COORDINATOR_UNAVAILABLE", "RUN_INSPECTION_BUSY", "RUN_DB_BINDING_NOT_FOUND", "RUN_DB_BINDING_CONFLICT",
                "RUN_BUILD_NOT_READY", "EXECUTION_BUILD_NOT_FOUND", "RUN_PROJECT_ARCHIVED", "RUN_NOT_FOUND", "RUN_CAPACITY_FULL",
                "RUN_IDEMPOTENCY_CONFLICT", "RUN_PROJECT_BUSY", "RUN_MIGRATION_REVIEW_REQUIRED", "RUN_MIGRATION_REVIEW_STALE",
                "RUN_MIGRATION_REVIEW_NOT_REQUIRED", "RUN_MIGRATION_INCOMPATIBLE", "RUN_MIGRATION_INPUT_CHANGED", "RUN_STATE_UNAVAILABLE",
                "RUN_MIGRATION_TARGET_INVALID", "RUN_MIGRATION_HISTORY_INVALID", "RUN_MIGRATION_DB_UNAVAILABLE", "EXECUTION_TIMEOUT").contains(Objects.toString(code, "")))
            return ExecutionRun.error(failure instanceof ApiException api ? api.status() : 503, code);
        return ExecutionRun.error(503, "RUN_WORKBENCH_UNAVAILABLE");
    }
}
