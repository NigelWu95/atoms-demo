package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One held runtime's short metadata transactions. No process launch, filesystem I/O or business DB queries. */
final class ExecutionRunJournal {
    private final ExecutionSettings settings;
    private final ExecutionSettings.Configuration configuration;
    private final ExecutionRunRepository repository;
    private final TransactionTemplate transaction;
    private final ExecutionRun run;
    private final Path directory;

    ExecutionRunJournal(ExecutionSettings settings, ExecutionSettings.Configuration configuration, ExecutionRunRepository repository,
            PlatformTransactionManager transactions, ExecutionRun run, Path directory) {
        this.settings = Objects.requireNonNull(settings); this.configuration = Objects.requireNonNull(configuration);
        this.repository = Objects.requireNonNull(repository); this.run = Objects.requireNonNull(run);
        if (directory == null || !directory.isAbsolute() || !directory.normalize().equals(directory)
                || !directory.endsWith(run.id().toString())) throw new IllegalArgumentException("RUN_INVOCATION_REJECTED");
        this.directory = directory;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }

    ExecutionRun begin(UUID execution) throws IOException {
        return transact(() -> repository.begin(run, execution), true);
    }
    ExecutionRun ready(UUID execution, ExecutionBindingRepository.Target target, ExecutionHealth.Confirmed proof,
            dev.atoms.demo.execution.OwnedProcess.Budget budget) throws IOException {
        return transact(() -> {
            remaining(budget);
            var result = repository.ready(run, execution, target, proof);
            remaining(budget); return result;
        }, true);
    }
    ExecutionRun notStarted(String failure) throws IOException {
        return transact(() -> repository.notStarted(run, failure), false);
    }
    ExecutionRun finish(UUID execution, ExecutionRunProcess.Outcome outcome, String failure) throws IOException {
        return transact(() -> repository.finish(run, execution, directory, outcome, failure), false);
    }

    private ExecutionRun transact(Supplier<ExecutionRun> operation, boolean authorize) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
        try {
            return transaction.execute(tx -> {
                if (authorize) currentConfiguration();
                var result = Objects.requireNonNull(operation.get());
                if (authorize) currentConfiguration();
                return result;
            });
        } catch (RuntimeException error) {
            // Commit outcome may be unknown. Never retry here or expose driver/configuration details.
            if (error instanceof dev.atoms.demo.common.ApiException api && api.code().equals("RUN_START_TIMEOUT"))
                throw new IOException("RUN_START_TIMEOUT");
            throw new IOException("RUN_STATE_UNAVAILABLE");
        }
    }
    private void currentConfiguration() {
        var current = settings.load();
        if (!current.equals(configuration) || !current.allows(run.ownerId()))
            throw ExecutionRun.error(409, "RUN_CONFIGURATION_CHANGED");
    }
    private static void remaining(dev.atoms.demo.execution.OwnedProcess.Budget budget) {
        try {
            var left = budget.remaining();
            if (Thread.currentThread().isInterrupted() || left == null || left.isNegative() || left.isZero()) throw new IOException();
        } catch (IOException | RuntimeException error) { throw ExecutionRun.error(409, "RUN_START_TIMEOUT"); }
    }
}
