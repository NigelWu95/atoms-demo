package dev.atoms.demo.projectexecution;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Authority and transaction faults; real PostgreSQL state transitions are covered by ExecutionRunStateIT. */
class ExecutionRunRecoveryTest {
    @TempDir Path temporary;
    final ExecutionRunRepository repository = mock(ExecutionRunRepository.class);
    final ExecutionCoordinatorLease lease = mock(ExecutionCoordinatorLease.class);
    final ExecutionRun run = mock(ExecutionRun.class);
    final AtomicBoolean sourceReady = new AtomicBoolean(true);
    final Transactions transactions = new Transactions();
    MockEnvironment environment;
    ExecutionRunRecovery recovery;

    @BeforeEach void setup() {
        environment = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", UUID.randomUUID().toString());
        for (String key : List.of("root", "node", "npm", "java", "maven", "policy"))
            environment.withProperty("atoms.project-execution." + key, temporary.resolve(key).toString());
        when(lease.healthy()).thenReturn(true);
        recovery = new ExecutionRunRecovery(new ExecutionSettings(environment), repository, transactions, lease, sourceReady::get);
    }

    @Test void reconcilesOnceInAShortTransactionWithAuthorityBeforeAndAfterTheWrite() throws Exception {
        when(repository.recover(run)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); return run;
        });
        assertThat(recovery.reconcile(run)).isSameAs(run);
        assertThat(transactions.commits).isEqualTo(1); assertThat(transactions.timeout).isEqualTo(5);
        verify(lease, times(3)).healthy(); verify(repository, times(1)).recover(run);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test void disabledConfigurationUnreadySourceOrLostLeaseDoesNotTouchMetadata() {
        sourceReady.set(false); unavailable(); sourceReady.set(true);
        environment.withProperty("atoms.project-execution.enabled", "false"); unavailable();
        environment.withProperty("atoms.project-execution.enabled", "true");
        when(lease.healthy()).thenReturn(false); unavailable();
        verifyNoInteractions(repository); assertThat(transactions.commits).isZero();
    }

    @Test void configurationDriftBeforeOrWhileOpeningTheTransactionRejectsSettlement() {
        environment.withProperty("atoms.project-execution.start-timeout-seconds", "119"); unavailable();
        environment.withProperty("atoms.project-execution.start-timeout-seconds", "120");
        transactions.onBegin = () -> environment.withProperty("atoms.project-execution.root", temporary.resolve("changed").toString());
        unavailable(); verifyNoInteractions(repository);
        assertThat(transactions.commits).isZero(); assertThat(transactions.rollbacks).isEqualTo(1);
    }

    @Test void authorityLostWhileWaitingForTheTransactionPreventsTheWrite() {
        when(lease.healthy()).thenReturn(true, false); unavailable();
        verifyNoInteractions(repository); assertThat(transactions.rollbacks).isEqualTo(1);
    }

    @Test void leaseLossAfterTheWriteRollsBackInsteadOfPublishingReconciliation() {
        when(lease.healthy()).thenReturn(true, true, false);
        when(repository.recover(run)).thenReturn(run); unavailable();
        verify(repository, times(1)).recover(run);
        assertThat(transactions.commits).isZero(); assertThat(transactions.rollbacks).isEqualTo(1);
    }

    @Test void configurationOrSourceChangeAfterTheWriteRollsBack() {
        doAnswer(call -> { sourceReady.set(false); return run; }).when(repository).recover(run);
        unavailable(); sourceReady.set(true);
        doAnswer(call -> {
            environment.withProperty("atoms.project-execution.max-running-projects", "3"); return run;
        }).when(repository).recover(run);
        unavailable(); assertThat(transactions.commits).isZero(); assertThat(transactions.rollbacks).isEqualTo(2);
    }

    @Test void databaseFailureIsSanitizedAndIsNotRetried() {
        when(repository.recover(run)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                "jdbc:private-host password=private-value", new IllegalStateException("private-path")));
        unavailable(); verify(repository, times(1)).recover(run);
        assertThat(transactions.rollbacks).isEqualTo(1);
    }

    @Test void lostCommitAcknowledgementDoesNotCauseASecondRecoveryAttempt() {
        when(repository.recover(run)).thenReturn(run); transactions.failCommit = true;
        unavailable(); verify(repository, times(1)).recover(run);
        assertThat(transactions.commits).isEqualTo(1);
    }

    @Test void ambientTransactionIsRejectedBeforeObservingAuthorityOrChangingMetadata() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> unavailable());
        verifyNoInteractions(repository, lease);
    }

    private void unavailable() {
        assertThatThrownBy(() -> recovery.reconcile(run)).isInstanceOf(java.io.IOException.class)
                .hasMessage("RUN_RECOVERY_UNAVAILABLE").hasNoCause().hasNoSuppressedExceptions();
    }
    private static final class Transactions extends AbstractPlatformTransactionManager {
        int commits, rollbacks, timeout;
        boolean failCommit;
        Runnable onBegin = () -> {};
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { timeout = definition.getTimeout(); onBegin.run(); }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            commits++;
            if (failCommit) throw new org.springframework.transaction.TransactionSystemException("private-commit-result");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
