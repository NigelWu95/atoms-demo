package dev.atoms.demo.projectexecution;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Transaction boundaries and injected commit faults; actual row constraints and stop/health proofs have PG contracts. */
class ExecutionRunJournalTest {
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), execution = UUID.randomUUID();
    final ExecutionRunRepository repository = mock(ExecutionRunRepository.class);
    final Transactions transactions = new Transactions();
    MockEnvironment environment;
    ExecutionRun run;
    ExecutionRunJournal journal;

    @BeforeEach void setup() throws Exception {
        String hash = "a".repeat(64);
        run = new ExecutionRun(UUID.randomUUID(), project, owner, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                hash, hash, hash, hash, hash, hash, "STARTING", false, null, false, null, null, Instant.now(), null, null);
        environment = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        for (String key : List.of("root", "node", "npm", "java", "maven", "policy"))
            environment.withProperty("atoms.project-execution." + key, temporary.resolve(key).toString());
        var settings = new ExecutionSettings(environment);
        journal = new ExecutionRunJournal(settings, settings.load(), repository, transactions, run, temporary.resolve(run.id().toString()));
    }

    @Test void intentCommitsInItsOwnShortTransactionAndNeverLaunchesAnything() throws Exception {
        when(repository.begin(run, execution)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); return run;
        });
        assertThat(journal.begin(execution)).isEqualTo(run);
        assertThat(transactions.commits).isEqualTo(1); assertThat(transactions.timeout).isEqualTo(5);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test void disabledOrChangedMaintainerConfigurationPreventsFurtherClaims() {
        environment.withProperty("atoms.project-execution.enabled", "false");
        unavailable(() -> journal.begin(execution));
        environment.withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", UUID.randomUUID().toString());
        unavailable(() -> journal.begin(execution));
        verifyNoInteractions(repository);
    }

    @Test void configurationDriftAfterTheUpdateRollsBackBeforeCommit() {
        when(repository.begin(run, execution)).thenAnswer(call -> {
            environment.withProperty("atoms.project-execution.start-timeout-seconds", "119"); return run;
        });
        unavailable(() -> journal.begin(execution));
        assertThat(transactions.rollbacks).isEqualTo(1); assertThat(transactions.commits).isZero();
        verify(repository, times(1)).begin(run, execution);
    }

    @Test void lostCommitAcknowledgementIsSanitizedAndNeverRetried() {
        when(repository.begin(run, execution)).thenReturn(run); transactions.failCommit = true;
        unavailable(() -> journal.begin(execution));
        assertThat(transactions.commits).isEqualTo(1);
        verify(repository, times(1)).begin(run, execution);
    }

    @Test void databaseFailureDoesNotLeakPrivateDetailsOrRetryAnIntent() {
        when(repository.begin(run, execution)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                "jdbc:private-database password=private-value", new IllegalStateException("private-path")));
        unavailable(() -> journal.begin(execution));
        assertThat(transactions.rollbacks).isEqualTo(1);
        verify(repository, times(1)).begin(run, execution);
    }

    @Test void cleanupStillPersistsAfterConfigurationRevocation() throws Exception {
        environment.withProperty("atoms.project-execution.enabled", "false");
        when(repository.notStarted(run, "RUN_START_FAILED")).thenReturn(run);
        assertThat(journal.notStarted("RUN_START_FAILED")).isEqualTo(run);
        var lost = new ExecutionRunProcess.Outcome("RUN_PROCESS_UNSETTLED", new ExecutionRunProcess.Output(0, 0), null);
        when(repository.finish(run, execution, temporary.resolve(run.id().toString()), lost, null)).thenReturn(run);
        assertThat(journal.finish(execution, lost, null)).isEqualTo(run);
        assertThat(transactions.commits).isEqualTo(2);
    }

    @Test void callerTransactionCannotIncludeIntentCommit() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThatThrownBy(() -> journal.begin(execution)).hasMessage("EXECUTION_TRANSACTION_ACTIVE");
        });
        verifyNoInteractions(repository);
    }

    @Test void readyCommitCannotOutliveTheOriginalStartupBudget() {
        assertThatThrownBy(() -> journal.ready(execution, null, null, () -> java.time.Duration.ZERO))
                .hasMessage("RUN_START_TIMEOUT");
        verifyNoInteractions(repository);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        when(repository.ready(run, execution, null, null)).thenReturn(run);
        assertThatThrownBy(() -> journal.ready(execution, null, null,
                () -> checks.incrementAndGet() == 1 ? java.time.Duration.ofSeconds(1) : java.time.Duration.ZERO))
                .hasMessage("RUN_START_TIMEOUT");
        assertThat(transactions.commits).isZero(); assertThat(transactions.rollbacks).isEqualTo(2);
    }

    private void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(java.io.IOException.class).hasMessage("RUN_STATE_UNAVAILABLE")
                .hasNoCause().hasNoSuppressedExceptions();
    }
    private static class Transactions extends AbstractPlatformTransactionManager {
        int commits, rollbacks, timeout;
        boolean failCommit;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { timeout = definition.getTimeout(); }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            commits++;
            if (failCommit) throw new org.springframework.transaction.TransactionSystemException("private-commit-result");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
