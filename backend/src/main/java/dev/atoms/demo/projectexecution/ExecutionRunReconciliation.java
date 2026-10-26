package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.source.ProjectSourceReadGateway;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Explicit maintenance only. No Spring auto-activation, process adoption, SQL rollback or automatic retry. */
final class ExecutionRunReconciliation {
    interface Owner {
        Scope open(ExecutionRun failed) throws IOException;
        /** Only removes a matching original ended job; a previously released/unowned job is never adopted. */
        void accepted(ExecutionRun failed, ExecutionRunReconciliationRepository.Audit audit) throws IOException;
    }
    interface Scope extends AutoCloseable {
        /** Short authority checks only; no filesystem scanning inside metadata transactions. */
        void checkAuthority() throws IOException;
        ExecutionArtifact artifact(ExecutionBuild build, String jar, String manifest, OwnedProcess.Budget budget) throws IOException;
        @Override void close() throws IOException;
    }
    @FunctionalInterface interface Inputs {
        String history(ExecutionRun run, Scope scope, OwnedProcess.Budget budget) throws IOException;
    }
    private final ExecutionSettings settings;
    private final ExecutionRunReconciliationRepository repository;
    private final TransactionTemplate transaction;
    private final Owner ownership;
    private final Inputs inputs;

    ExecutionRunReconciliation(ExecutionSettings settings, JdbcTemplate jdbc, PlatformTransactionManager transactions,
            ProjectSourceReadGateway source, ExecutionDatabaseBinding databases, Owner ownership) {
        this(settings, new ExecutionRunReconciliationRepository(jdbc), transactions, ownership, nativeInputs(jdbc, source, databases));
    }
    // Package-only test seam. Requests and configuration cannot supply an input verifier or an owner.
    ExecutionRunReconciliation(ExecutionSettings settings, ExecutionRunReconciliationRepository repository,
            PlatformTransactionManager transactions, Owner ownership, Inputs inputs) {
        this.settings = settings; this.repository = repository; this.ownership = ownership; this.inputs = inputs;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }

    ExecutionRunReconciliationRepository.View inspectStoppedFailure(UUID owner, UUID project, UUID run) throws IOException {
        outside();
        try {
            var config = authorize(owner); var budget = budget();
            var original = tx(owner, config, null, budget, () -> repository.read(owner, project, run));
            try (var scope = ownership.open(original)) {
                scope.checkAuthority();
                String history = inputs.history(original, scope, budget);
                var result = tx(owner, config, scope, budget, () -> repository.inspect(owner, project, run, history));
                if (!result.run().equals(original)) throw ExecutionRun.error(409, "RUN_RECONCILIATION_CONFLICT");
                return result;
            }
        } catch (ApiException error) { throw new ApiException(error.status(), error.code(), error.getMessage()); }
        catch (IOException | RuntimeException error) { throw sanitized(error); }
    }

    ExecutionRunReconciliationRepository.Audit acknowledgeStoppedFailure(UUID owner,
            ExecutionRunReconciliationRepository.View expected, String key) throws IOException {
        outside();
        try {
            var config = authorize(owner); var budget = budget();
            var previous = tx(owner, config, null, budget, () -> repository.confirmed(owner, expected, key));
            if (previous != null) {
                // An explicit replay may finish the original resource release after a lost response. Never repeat the SQL acceptance.
                ownership.accepted(expected.run(), previous); return previous;
            }
            var original = tx(owner, config, null, budget, () -> repository.read(owner, expected.run().projectId(), expected.run().id()));
            if (!original.equals(expected.run())) throw ExecutionRun.error(409, "RUN_RECONCILIATION_CONFLICT");
            try (var scope = ownership.open(original)) {
                scope.checkAuthority();
                String history = inputs.history(original, scope, budget);
                var audit = tx(owner, config, scope, budget, () -> repository.acknowledge(owner, expected, history, key));
                ownership.accepted(original, audit); // Only after a positively acknowledged commit.
                return audit;
            }
        } catch (ApiException error) { throw new ApiException(error.status(), error.code(), error.getMessage()); }
        catch (IOException | RuntimeException error) { throw sanitized(error); }
    }

    private static Inputs nativeInputs(JdbcTemplate jdbc, ProjectSourceReadGateway source, ExecutionDatabaseBinding databases) {
        return (run, scope, budget) -> {
            var build = new ExecutionBuildRepository(jdbc).get(run.projectId(), run.buildId());
            if (!build.status().equals("SUCCEEDED") || !build.ownerId().equals(run.ownerId())
                    || !build.sourceVersionId().equals(run.sourceVersionId()) || !build.treeSha256().equals(run.treeSha256()))
                throw new IOException("RUN_RECONCILIATION_CONFLICT");
            var snapshot = source.read(run.ownerId(), run.projectId(), run.sourceVersionId(), budget::remaining);
            if (!snapshot.version().id().equals(run.sourceVersionId()) || !snapshot.version().projectId().equals(run.projectId())
                    || !snapshot.tree().sha256().equals(run.treeSha256())) throw new IOException("RUN_RECONCILIATION_CONFLICT");
            var artifact = scope.artifact(build, run.jarSha256(), run.manifestSha256(), budget);
            ExecutionMigrations.inspect(snapshot.tree(), artifact, build, budget);
            var current = databases.history(run.projectId(), budget);
            if (!current.binding().id().equals(run.bindingId()) || !current.binding().hash().equals(run.bindingIdentitySha256()))
                throw new IOException("RUN_RECONCILIATION_CONFLICT");
            artifact.verify(budget); scope.checkAuthority();
            return current.state().sha256();
        };
    }
    private <T> T tx(UUID owner, ExecutionSettings.Configuration config, Scope scope, OwnedProcess.Budget budget, Supplier<T> work) {
        return transaction.execute(status -> {
            check(owner, config, scope, budget); var result = work.get(); check(owner, config, scope, budget); return result;
        });
    }
    private void check(UUID owner, ExecutionSettings.Configuration config, Scope scope, OwnedProcess.Budget budget) {
        try {
            if (!config.equals(authorize(owner))) throw new IOException("RUN_RECONCILIATION_UNAVAILABLE");
            if (scope != null) scope.checkAuthority();
            var remaining = budget.remaining();
            if (Thread.currentThread().isInterrupted() || remaining == null || remaining.isZero() || remaining.isNegative())
                throw new IOException("EXECUTION_TIMEOUT");
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    private ExecutionSettings.Configuration authorize(UUID owner) {
        var config = settings.load();
        if (owner == null || !config.maintainerIds().contains(owner))
            throw new ApiException(403, "EXECUTION_FORBIDDEN", "当前身份没有完整工程执行权限。");
        return config;
    }
    private static ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
    private static void outside() throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
    }
    private static IOException sanitized(Exception error) {
        String code = error instanceof UncheckedIOException wrapped ? wrapped.getCause().getMessage() : error.getMessage();
        return new IOException(Set.of("EXECUTION_TIMEOUT", "EXECUTION_CANCELLED", "RUN_RECONCILIATION_CONFLICT").contains(Objects.toString(code, ""))
                ? code : "RUN_RECONCILIATION_UNAVAILABLE");
    }
}
