package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.source.ProjectSourceReadGateway;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal opt-in workflow. Every approval/start check reads fresh source, sealed artifact and business history. */
final class ExecutionMigrationReview {
    private final ExecutionSettings settings;
    private final ExecutionMigrationReviewRepository repository;
    private final ProjectSourceReadGateway source;
    private final ExecutionDatabaseBinding databases;
    private final TransactionTemplate transaction;
    ExecutionMigrationReview(ExecutionSettings settings, ExecutionMigrationReviewRepository repository, PlatformTransactionManager transactions,
            ProjectSourceReadGateway source, ExecutionDatabaseBinding databases) {
        this.settings=settings; this.repository=repository; this.source=source; this.databases=databases;
        transaction=new TransactionTemplate(transactions); transaction.setTimeout(5);
    }
    record View(UUID projectId, UUID buildId, UUID bindingId, String jarSha256, String historySha256, String targetSha256,
                ExecutionMigrationPolicy.Decision decision) {}
    private record Checked(ExecutionSettings.Configuration configuration, ExecutionMigrationReviewRepository.Identity identity,
                           ExecutionMigrations.Target target, ExecutionMigrationHistory.Snapshot database, View view) {}

    View inspect(UUID owner, UUID project, UUID build, ExecutionArtifact artifact, OwnedProcess.Budget budget) throws IOException {
        return checked(owner,project,build,artifact,budget).view();
    }
    record Inspection(View view, List<ExecutionMigrations.Migration> migrations, int appliedCount) {}
    Inspection inspectForWorkbench(UUID owner, UUID project, UUID build, ExecutionArtifact artifact, OwnedProcess.Budget budget) throws IOException {
        var checked = checked(owner, project, build, artifact, budget);
        return new Inspection(checked.view(), checked.target().migrations(), checked.database().state().applied().size());
    }
    UUID approve(UUID owner, UUID project, UUID build, String expectedHistory, String expectedTarget,
                 ExecutionArtifact artifact, OwnedProcess.Budget budget) throws IOException {
        var checked=checked(owner,project,build,artifact,budget);
        return tx(() -> {
            sameConfiguration(owner,checked.configuration());
            UUID id=repository.approve(owner,checked.identity(),checked.target(),checked.database(),expectedHistory,expectedTarget);
            sameConfiguration(owner,checked.configuration()); remaining(budget); return id;
        });
    }
    /** Invoke again at launch preflight; a returned view neither authorizes credentials nor proves a process is ready. */
    View requireCompatible(UUID owner, UUID project, UUID build, UUID review, ExecutionArtifact artifact, OwnedProcess.Budget budget) throws IOException {
        var checked=checked(owner,project,build,artifact,budget);
        return tx(() -> {
            sameConfiguration(owner,checked.configuration());
            repository.requireCompatible(owner,checked.identity(),checked.target(),checked.database(),review);
            sameConfiguration(owner,checked.configuration()); remaining(budget); return checked.view();
        });
    }
    private Checked checked(UUID owner, UUID project, UUID build, ExecutionArtifact artifact, OwnedProcess.Budget budget) throws IOException {
        var configuration=authorize(owner);
        ExecutionMigrations.check(budget);
        if (project==null || build==null || artifact==null) throw ExecutionRun.error(422,"RUN_INVALID_INPUT");
        var identity=tx(() -> {
            var value=repository.identity(owner,project,build); sameConfiguration(owner,configuration); remaining(budget); return value;
        });
        try {
            var snapshot=source.read(owner,project,identity.build().sourceVersionId(),budget::remaining);
            if (!snapshot.version().id().equals(identity.build().sourceVersionId()) || !snapshot.version().projectId().equals(project))
                throw new IOException("RUN_MIGRATION_INPUT_CHANGED");
            var target=ExecutionMigrations.inspect(snapshot.tree(),artifact,identity.build(),budget);
            sameConfiguration(owner,configuration);
            var database=databases.history(project,budget);
            var decision=tx(() -> {
                var value=repository.validate(owner,identity,target,database); sameConfiguration(owner,configuration); remaining(budget); return value;
            });
            return new Checked(configuration,identity,target,database,new View(project,build,database.binding().id(),
                    target.jarSha256(),database.state().sha256(),target.sha256(),decision));
        } catch (IOException error) {
            String code=Objects.toString(error.getMessage(),"");
            throw new IOException(Set.of("EXECUTION_TIMEOUT","EXECUTION_CANCELLED","EXECUTION_ARTIFACT_INVALID","EXECUTION_STORAGE_UNAVAILABLE",
                    "EXECUTION_DB_CONFIGURATION_INVALID","EXECUTION_DB_TARGET_REJECTED","RUN_DB_BINDING_NOT_FOUND",
                    "RUN_MIGRATION_TARGET_INVALID","RUN_MIGRATION_HISTORY_INVALID","RUN_MIGRATION_INPUT_CHANGED","RUN_MIGRATION_DB_UNAVAILABLE")
                    .contains(code) ? code : "RUN_MIGRATION_CHECK_FAILED");
        } catch (IllegalArgumentException error) { throw new IOException("RUN_MIGRATION_TARGET_INVALID"); }
    }
    private ExecutionSettings.Configuration authorize(UUID owner) {
        var configuration=settings.load();
        if (owner==null || !configuration.maintainerIds().contains(owner)) throw new ApiException(403,"EXECUTION_FORBIDDEN","当前身份没有完整工程执行权限。");
        return configuration;
    }
    private void sameConfiguration(UUID owner, ExecutionSettings.Configuration expected) {
        if (!authorize(owner).equals(expected)) throw ExecutionRun.error(409,"RUN_CONFIGURATION_CHANGED");
    }
    private <T> T tx(Supplier<T> work) {
        try { return transaction.execute(status -> work.get()); }
        catch (DataAccessException | TransactionException error) { throw ExecutionRun.error(503,"RUN_STATE_UNAVAILABLE"); }
    }
    private static void remaining(OwnedProcess.Budget budget) {
        try {
            var remaining=budget.remaining();
            if (Thread.currentThread().isInterrupted() || remaining==null || remaining.isNegative() || remaining.isZero()) throw new IOException();
        } catch (IOException error) { throw ExecutionRun.error(409,"EXECUTION_TIMEOUT"); }
    }
}
