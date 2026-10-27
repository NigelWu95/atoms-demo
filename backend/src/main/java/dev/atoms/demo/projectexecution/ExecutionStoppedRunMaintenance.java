package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit restart maintenance for one stopped run. No dispatcher, process capability or automatic acceptance. */
final class ExecutionStoppedRunMaintenance implements ExecutionRunReconciliation.Owner, AutoCloseable {
    private final ExecutionSettings settings;
    private final ExecutionSettings.Configuration configuration;
    private final BooleanSupplier sourceReady;
    private final ExecutionCoordinatorLease lease;
    private final ExecutionPaths paths;
    private final UUID owner, project, run;
    private boolean closed, scopeOpen;

    static ExecutionStoppedRunMaintenance acquire(ExecutionSettings settings, DataSource database,
            BooleanSupplier sourceReady, List<Path> protectedRoots, UUID owner, UUID project, UUID run) throws IOException {
        outside();
        ExecutionCoordinatorLease lease = null; ExecutionPaths paths = null;
        try {
            var config = settings.load();
            if (owner == null || project == null || run == null || !config.maintainerIds().contains(owner)
                    || !sourceReady.getAsBoolean()) throw unavailable();
            lease = new ExecutionCoordinatorLease(database);
            paths = ExecutionPaths.existing(config.root(), protectedRoots);
            var result = new ExecutionStoppedRunMaintenance(settings, config, sourceReady, lease, paths, owner, project, run);
            result.checkAuthority(); result.checkRoot(); return result;
        } catch (IOException | SQLException | RuntimeException error) {
            if (paths != null) try { paths.close(); } catch (IOException ignored) { /* No process was acquired. */ }
            if (lease != null) try { lease.close(); } catch (SQLException ignored) { /* Lease disposal retains uncertain connections. */ }
            throw unavailable();
        }
    }

    private ExecutionStoppedRunMaintenance(ExecutionSettings settings, ExecutionSettings.Configuration configuration,
            BooleanSupplier sourceReady, ExecutionCoordinatorLease lease, ExecutionPaths paths, UUID owner, UUID project, UUID run) {
        this.settings = settings; this.configuration = configuration; this.sourceReady = sourceReady;
        this.lease = lease; this.paths = paths; this.owner = owner; this.project = project; this.run = run;
    }

    @Override public synchronized ExecutionRunReconciliation.Scope open(ExecutionRun failed) throws IOException {
        outside(); checkAuthority(); eligible(failed); checkRoot();
        if (scopeOpen) throw unavailable();
        scopeOpen = true;
        return new ExecutionRunReconciliation.Scope() {
            private boolean ended;
            @Override public void checkAuthority() throws IOException {
                synchronized (ExecutionStoppedRunMaintenance.this) {
                    if (ended) throw unavailable();
                    ExecutionStoppedRunMaintenance.this.checkAuthority();
                }
            }
            @Override public ExecutionArtifact artifact(ExecutionBuild build, String jar, String manifest, OwnedProcess.Budget budget) throws IOException {
                outside(); checkAuthority(); checkRoot();
                if (build == null || !build.id().equals(failed.buildId()) || !build.projectId().equals(project)
                        || !build.ownerId().equals(owner) || !build.sourceVersionId().equals(failed.sourceVersionId())
                        || !build.treeSha256().equals(failed.treeSha256()) || !failed.jarSha256().equals(jar)
                        || !failed.manifestSha256().equals(manifest)) throw unavailable();
                var artifact = ExecutionArtifact.openSuccessful(paths, build, jar, manifest, budget);
                checkRoot(); checkAuthority(); return artifact;
            }
            @Override public void close() {
                synchronized (ExecutionStoppedRunMaintenance.this) {
                    if (!ended) { ended = true; scopeOpen = false; }
                }
            }
        };
    }

    @Override public synchronized void accepted(ExecutionRun failed, ExecutionRunReconciliationRepository.Audit audit) throws IOException {
        outside(); checkAuthority(); eligible(failed); checkRoot();
        if (audit == null || !audit.runId().equals(run) || !audit.projectId().equals(project) || !audit.approvedBy().equals(owner)
                || !audit.executionId().equals(failed.executionId()) || !audit.stoppedSha256().equals(failed.stoppedSha256()))
            throw unavailable();
        // The existing repository owns the atomic audit/terminal transition. There is no old job to adopt or release.
    }

    private void eligible(ExecutionRun failed) throws IOException {
        if (failed == null || !owner.equals(failed.ownerId()) || !project.equals(failed.projectId()) || !run.equals(failed.id())
                || !"NEEDS_RECONCILIATION".equals(failed.status()) || failed.executionPending() || failed.executionId() == null
                || failed.stoppedSha256() == null || !failed.stoppedSha256().matches("[a-f0-9]{64}")
                || failed.errorCode() == null || failed.readyAt() != null || failed.finishedAt() != null) throw unavailable();
    }
    private void checkAuthority() throws IOException {
        try {
            if (closed || !configuration.equals(settings.load()) || !configuration.maintainerIds().contains(owner)
                    || !sourceReady.getAsBoolean() || !lease.healthy()) throw unavailable();
        } catch (RuntimeException error) { throw unavailable(); }
    }
    private void checkRoot() throws IOException {
        if (!paths.checkedRoot().equals(configuration.root())) throw unavailable();
    }
    @Override public synchronized void close() throws IOException {
        outside();
        if (closed) return;
        if (scopeOpen) throw unavailable();
        closed = true;
        try { paths.close(); }
        finally { try { lease.close(); } catch (SQLException error) { throw unavailable(); } }
    }
    private static void outside() throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
    }
    private static IOException unavailable() { return new IOException("RUN_RECONCILIATION_UNAVAILABLE"); }
}
