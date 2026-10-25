package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Internal admission only. The coordinator/worker must own execution resources before consuming a fresh ticket. */
final class ExecutionRunService {
    private final ExecutionSettings settings;
    private final ExecutionRunRepository runs;
    private final ExecutionMigrationReviewRepository identities;
    private final TransactionTemplate transaction;
    private final ExecutionPaths paths;
    private final ExecutionDatabaseBinding databases;
    private final ExecutionMigrationReview migrations;

    ExecutionRunService(ExecutionSettings settings, ExecutionRunRepository runs, ExecutionMigrationReviewRepository identities,
            PlatformTransactionManager transactions, ExecutionPaths paths, ExecutionDatabaseBinding databases, ExecutionMigrationReview migrations) {
        this.settings = settings; this.runs = runs; this.identities = identities; this.paths = paths;
        this.databases = databases; this.migrations = migrations;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }

    record Admission(ExecutionRun run, Ticket ticket) {}
    private record Initial(ExecutionRun previous, ExecutionMigrationReviewRepository.Identity identity) {}

    ExecutionRun stop(UUID owner, UUID project, UUID id) throws IOException {
        outsideTransaction();
        var configuration = authorize(owner);
        return tx(() -> {
            sameConfiguration(owner, configuration);
            var row = runs.stop(owner, project, id);
            sameConfiguration(owner, configuration); return row;
        });
    }
    /** Created only for the request that inserted a row. No reconstruction from a persisted run ID. */
    static final class Ticket {
        private final Claim claim;
        private final AtomicBoolean consumed = new AtomicBoolean();
        private Ticket(Claim claim) { this.claim = claim; }
    }
    static final class Claim {
        private final ExecutionRunService issuer;
        final ExecutionRun run;
        final ExecutionSettings.Configuration configuration;
        final ExecutionMigrationReviewRepository.Identity identity;
        final ExecutionArtifact artifact;
        final OwnedProcess.Budget budget;
        private Claim(ExecutionRunService issuer, ExecutionRun run, ExecutionSettings.Configuration configuration,
                ExecutionMigrationReviewRepository.Identity identity, ExecutionArtifact artifact, OwnedProcess.Budget budget) {
            this.issuer = issuer; this.run = run; this.configuration = configuration;
            this.identity = identity; this.artifact = artifact; this.budget = budget;
        }
    }

    Admission submit(UUID owner, UUID project, UUID build, UUID binding, UUID review, String key) throws IOException {
        outsideTransaction();
        var configuration = authorize(owner);
        if (project == null) throw ExecutionRun.error(422, "RUN_INVALID_INPUT");
        var request = ExecutionRun.request(build, binding, review, key);
        var budget = new ExecutionProcess.Deadline(configuration.startTimeout());
        var initial = tx(() -> {
            sameConfiguration(owner, configuration); remaining(budget);
            var previous = runs.find(owner, project, request);
            var identity = previous == null ? identities.identity(owner, project, build) : null;
            sameConfiguration(owner, configuration); remaining(budget);
            return new Initial(previous, identity);
        });
        if (initial.previous() != null) return new Admission(initial.previous(), null);
        var identity = initial.identity();
        if (!identity.binding().id().equals(binding)) throw new IOException("RUN_DB_BINDING_CONFLICT");
        requireRoot(configuration);
        var artifact = ExecutionArtifact.openSuccessful(paths, identity.build(), identity.jarSha256(), identity.manifestSha256(), budget);
        checkInputs(owner, project, build, review, configuration, identity, artifact, budget);
        return tx(() -> {
            sameConfiguration(owner, configuration); remaining(budget);
            var previous = runs.find(owner, project, request);
            if (previous != null) {
                sameConfiguration(owner, configuration); return new Admission(previous, null);
            }
            if (!identities.identity(owner, project, build).equals(identity)) throw ExecutionRun.error(409, "RUN_MIGRATION_INPUT_CHANGED");
            var run = runs.admit(owner, project, request);
            sameConfiguration(owner, configuration); remaining(budget);
            return new Admission(run, new Ticket(new Claim(this, run, configuration, identity, artifact, budget)));
        });
    }

    Claim take(Ticket ticket) throws IOException {
        outsideTransaction();
        if (ticket == null || ticket.claim.issuer != this || !ticket.consumed.compareAndSet(false, true))
            throw new IOException("RUN_LAUNCH_ALREADY_CLAIMED");
        return ticket.claim;
    }

    /** Before command preparation (null execution), and again in the owning process's preflight (exact execution UUID). */
    void recheck(Claim claim, UUID execution) throws IOException {
        outsideTransaction();
        if (claim == null || claim.issuer != this) throw new IOException("RUN_INVOCATION_REJECTED");
        var run = claim.run;
        current(claim, execution);
        checkInputs(run.ownerId(), run.projectId(), run.buildId(), run.migrationReviewId(), claim.configuration,
                claim.identity, claim.artifact, claim.budget);
        current(claim, execution);
    }

    private void current(Claim claim, UUID execution) {
        tx(() -> {
            remaining(claim.budget);
            var row = boundRun(claim, execution);
            if (row.stopRequested()) throw ExecutionRun.error(409, "RUN_CANCELLED");
            if (!row.status().equals("STARTING")) throw ExecutionRun.error(409, "RUN_STATE_CONFLICT");
            remaining(claim.budget); return row;
        });
    }

    /** Resident monitoring checks current authorization and durable stop intent, never the expired startup clock or migration history. */
    boolean stopRequested(Claim claim, UUID execution) throws IOException {
        outsideTransaction();
        if (claim == null || claim.issuer != this || execution == null) throw new IOException("RUN_INVOCATION_REJECTED");
        return tx(() -> {
            var row = boundRun(claim, execution);
            if (!Set.of("STARTING", "READY", "STOPPING").contains(row.status())) throw ExecutionRun.error(409, "RUN_STATE_CONFLICT");
            return row.stopRequested();
        });
    }

    private ExecutionRun boundRun(Claim claim, UUID execution) {
        var expected = claim.run;
        sameConfiguration(expected.ownerId(), claim.configuration);
        if (!identities.identity(expected.ownerId(), expected.projectId(), expected.buildId()).equals(claim.identity))
            throw ExecutionRun.error(409, "RUN_MIGRATION_INPUT_CHANGED");
        var row = runs.get(expected.projectId(), expected.id());
        if (!row.ownerId().equals(expected.ownerId()) || !row.requestSha256().equals(expected.requestSha256())
                || !row.buildId().equals(expected.buildId()) || !row.sourceVersionId().equals(expected.sourceVersionId())
                || !row.jarSha256().equals(expected.jarSha256()) || !row.manifestSha256().equals(expected.manifestSha256())
                || !row.bindingIdentitySha256().equals(expected.bindingIdentitySha256()) || row.finishedAt() != null
                || !Objects.equals(row.executionId(), execution) || row.executionPending() != (execution != null))
            throw ExecutionRun.error(409, "RUN_STATE_CONFLICT");
        sameConfiguration(expected.ownerId(), claim.configuration); return row;
    }

    private void checkInputs(UUID owner, UUID project, UUID build, UUID review, ExecutionSettings.Configuration configuration,
            ExecutionMigrationReviewRepository.Identity identity, ExecutionArtifact artifact, OwnedProcess.Budget budget) throws IOException {
        sameConfiguration(owner, configuration); requireRoot(configuration); remaining(budget);
        artifact.verify(budget);
        if (!identity.binding().equals(databases.verify(project, budget))) throw new IOException("RUN_DB_BINDING_CONFLICT");
        sameConfiguration(owner, configuration);
        migrations.requireCompatible(owner, project, build, review, artifact, budget);
        sameConfiguration(owner, configuration); remaining(budget);
    }

    private void requireRoot(ExecutionSettings.Configuration configuration) throws IOException {
        if (!paths.checkedRoot().equals(configuration.root())) throw new IOException("EXECUTION_STORAGE_UNAVAILABLE");
    }
    private ExecutionSettings.Configuration authorize(UUID owner) {
        var configuration = settings.load();
        if (owner == null || !configuration.maintainerIds().contains(owner))
            throw new ApiException(403, "EXECUTION_FORBIDDEN", "当前身份没有完整工程执行权限。");
        return configuration;
    }
    private void sameConfiguration(UUID owner, ExecutionSettings.Configuration expected) {
        if (!authorize(owner).equals(expected)) throw ExecutionRun.error(409, "RUN_CONFIGURATION_CHANGED");
    }
    private <T> T tx(Supplier<T> action) {
        try { return transaction.execute(tx -> action.get()); }
        catch (DataAccessException | TransactionException error) { throw ExecutionRun.error(503, "RUN_STATE_UNAVAILABLE"); }
    }
    private static void outsideTransaction() throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
    }
    private static void remaining(OwnedProcess.Budget budget) {
        try {
            var left = budget.remaining();
            if (Thread.currentThread().isInterrupted() || left == null || left.isNegative() || left.isZero()) throw new IOException();
        } catch (IOException error) { throw ExecutionRun.error(409, "RUN_START_TIMEOUT"); }
    }
}
