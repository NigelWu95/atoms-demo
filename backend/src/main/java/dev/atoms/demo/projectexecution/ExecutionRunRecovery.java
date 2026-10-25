package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Startup-only reconciliation under the original coordinator lease. Never recreates tickets or process capabilities. */
final class ExecutionRunRecovery {
    private final ExecutionSettings settings;
    private final ExecutionSettings.Configuration configuration;
    private final ExecutionRunRepository runs;
    private final TransactionTemplate transaction;
    private final ExecutionCoordinatorLease lease;
    private final BooleanSupplier sourceReady;

    ExecutionRunRecovery(ExecutionSettings settings, ExecutionRunRepository runs, PlatformTransactionManager transactions,
                         ExecutionCoordinatorLease lease, BooleanSupplier sourceReady) {
        this.settings = settings; configuration = settings.load(); this.runs = runs;
        this.lease = lease; this.sourceReady = sourceReady;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }

    /** Caller holds and checks the execution-root lease outside this metadata-only transaction. */
    ExecutionRun reconcile(ExecutionRun candidate) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        try {
            authority();
            return transaction.execute(tx -> {
                authority();
                var result = runs.recover(candidate);
                authority();
                return result;
            });
        } catch (RuntimeException error) {
            // A missing commit acknowledgement is not proof of rollback and never permits an automatic retry.
            throw unavailable();
        }
    }

    private void authority() {
        if (!lease.healthy() || !sourceReady.getAsBoolean() || !configuration.equals(settings.load()))
            throw new IllegalStateException();
    }
    private static IOException unavailable() { return new IOException("RUN_RECOVERY_UNAVAILABLE"); }
}
