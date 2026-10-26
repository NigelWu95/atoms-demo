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

/** Service transaction/ownership boundary; native source/artifact/database checks have separate real contracts. */
class ExecutionRunReconciliationTest {
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
    final ExecutionRunReconciliationRepository repository = mock(ExecutionRunReconciliationRepository.class);
    final Transactions transactions = new Transactions();
    final ExecutionRunReconciliation.Owner ownership = mock(ExecutionRunReconciliation.Owner.class);
    final ExecutionRunReconciliation.Scope scope = mock(ExecutionRunReconciliation.Scope.class);
    final ExecutionRunReconciliation.Inputs inputs = mock(ExecutionRunReconciliation.Inputs.class);
    static final String HASH = "a".repeat(64), HISTORY = "b".repeat(64);
    MockEnvironment environment;
    ExecutionRun run;
    ExecutionRunReconciliationRepository.View view;
    ExecutionRunReconciliationRepository.Audit audit;
    ExecutionRunReconciliation service;

    @BeforeEach void setup() throws Exception {
        run = new ExecutionRun(UUID.randomUUID(), project, owner, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                HASH, HASH, HASH, HASH, HASH, HASH, "NEEDS_RECONCILIATION", false, UUID.randomUUID(), false, HASH,
                "RUN_HEALTH_INVALID", Instant.now(), null, null);
        view = new ExecutionRunReconciliationRepository.View(run, HISTORY);
        audit = new ExecutionRunReconciliationRepository.Audit(UUID.randomUUID(), run.id(), project, owner, run.executionId(),
                HASH, HISTORY, HASH, HASH, Instant.now());
        environment = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        for (String key : List.of("root", "node", "npm", "java", "maven", "policy"))
            environment.withProperty("atoms.project-execution." + key, temporary.resolve(key).toString());
        when(repository.read(owner, project, run.id())).thenReturn(run);
        when(repository.inspect(owner, project, run.id(), HISTORY)).thenReturn(view);
        when(repository.acknowledge(owner, view, HISTORY, "confirm")).thenReturn(audit);
        when(ownership.open(run)).thenReturn(scope);
        when(inputs.history(eq(run), eq(scope), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return HISTORY;
        });
        service = new ExecutionRunReconciliation(new ExecutionSettings(environment), repository, transactions, ownership, inputs);
    }

    @Test void inspectionAndAcknowledgementKeepExternalReadsOutsideShortTransactions() throws Exception {
        assertThat(service.inspectStoppedFailure(owner, project, run.id())).isEqualTo(view);
        assertThat(service.acknowledgeStoppedFailure(owner, view, "confirm")).isEqualTo(audit);
        verify(ownership).accepted(run, audit);
        verify(scope, times(2)).close();
        assertThat(transactions.timeout).isEqualTo(5); assertThat(transactions.rollbacks).isZero();
    }

    @Test void explicitReplayReadsTheExistingAuditWithoutRepeatingDatabaseAcceptance() throws Exception {
        when(repository.confirmed(owner, view, "confirm")).thenReturn(audit);
        assertThat(service.acknowledgeStoppedFailure(owner, view, "confirm")).isEqualTo(audit);
        verify(repository, never()).acknowledge(any(), any(), any(), any());
        verifyNoInteractions(inputs, scope);
        verify(ownership, never()).open(any());
        verify(ownership).accepted(run, audit);
    }

    @Test void lostCommitAcknowledgementNeverReleasesResourcesOrRetriesTheWrite() throws Exception {
        when(repository.acknowledge(owner, view, HISTORY, "confirm")).thenAnswer(call -> {
            transactions.failCommit = true; return audit;
        });
        assertThatThrownBy(() -> service.acknowledgeStoppedFailure(owner, view, "confirm"))
                .hasMessage("RUN_RECONCILIATION_UNAVAILABLE").hasNoCause().hasNoSuppressedExceptions();
        verify(repository, times(1)).acknowledge(owner, view, HISTORY, "confirm");
        verify(ownership, never()).accepted(any(), any());
        verify(scope).close();
    }

    @Test void revokedMaintainerCannotReadOrAccept() {
        environment.withProperty("atoms.project-execution.maintainer-ids", UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.inspectStoppedFailure(owner, project, run.id())).hasMessageContaining("执行权限");
        assertThatThrownBy(() -> service.acknowledgeStoppedFailure(owner, view, "confirm")).hasMessageContaining("执行权限");
        verifyNoInteractions(repository, ownership, inputs);
    }

    @Test void permissionRevokedDuringTheWriteRollsBackWithoutReleasing() throws Exception {
        when(repository.acknowledge(owner, view, HISTORY, "confirm")).thenAnswer(call -> {
            environment.withProperty("atoms.project-execution.maintainer-ids", UUID.randomUUID().toString()); return audit;
        });
        assertThatThrownBy(() -> service.acknowledgeStoppedFailure(owner, view, "confirm")).hasMessageContaining("执行权限");
        assertThat(transactions.rollbacks).isEqualTo(1);
        verify(ownership, never()).accepted(any(), any());
        verify(scope).close();
    }

    @Test void configurationChangedDuringExternalReadsCannotReachTheWrite() throws Exception {
        when(inputs.history(eq(run), eq(scope), any())).thenAnswer(call -> {
            environment.withProperty("atoms.project-execution.max-running-projects", "3"); return HISTORY;
        });
        assertThatThrownBy(() -> service.acknowledgeStoppedFailure(owner, view, "confirm"))
                .hasMessage("RUN_RECONCILIATION_UNAVAILABLE").hasNoCause().hasNoSuppressedExceptions();
        verify(repository, never()).acknowledge(any(), any(), any(), any());
        verify(ownership, never()).accepted(any(), any());
    }

    @Test void expiredInputBudgetCannotReachTheWrite() throws Exception {
        when(inputs.history(eq(run), eq(scope), any())).thenThrow(new java.io.IOException("EXECUTION_TIMEOUT"));
        assertThatThrownBy(() -> service.acknowledgeStoppedFailure(owner, view, "confirm"))
                .hasMessage("EXECUTION_TIMEOUT").hasNoCause().hasNoSuppressedExceptions();
        verify(repository, never()).acknowledge(any(), any(), any(), any());
        verify(ownership, never()).accepted(any(), any());
        verify(scope).close();
    }

    @Test void apiFailureDoesNotExposePrivateSuppressedCloseDetails() throws Exception {
        when(inputs.history(eq(run), eq(scope), any())).thenThrow(ExecutionRun.error(409, "RUN_RECONCILIATION_CONFLICT"));
        doThrow(new java.io.IOException("private-source-path-and-database-details")).when(scope).close();
        assertThatThrownBy(() -> service.acknowledgeStoppedFailure(owner, view, "confirm"))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class).hasNoCause().hasNoSuppressedExceptions()
                .satisfies(error -> assertThat(((dev.atoms.demo.common.ApiException) error).code()).isEqualTo("RUN_RECONCILIATION_CONFLICT"));
        verify(repository, never()).acknowledge(any(), any(), any(), any());
        verify(ownership, never()).accepted(any(), any());
    }

    @Test void afterCommittedAcceptanceAnExplicitRetryOnlyReleasesTheSameRecordedAudit() throws Exception {
        doThrow(new java.io.IOException("private-release-detail")).doNothing().when(ownership).accepted(run, audit);
        assertThatThrownBy(() -> service.acknowledgeStoppedFailure(owner, view, "confirm"))
                .hasMessage("RUN_RECONCILIATION_UNAVAILABLE").hasNoCause().hasNoSuppressedExceptions();
        when(repository.confirmed(owner, view, "confirm")).thenReturn(audit);
        assertThat(service.acknowledgeStoppedFailure(owner, view, "confirm")).isEqualTo(audit);
        verify(repository, times(1)).acknowledge(owner, view, HISTORY, "confirm");
        verify(inputs, times(1)).history(eq(run), eq(scope), any());
        verify(ownership, times(2)).accepted(run, audit);
    }

    @Test void callerTransactionCannotEncloseFilesystemAndBusinessDatabaseChecks() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThatThrownBy(() -> service.inspectStoppedFailure(owner, project, run.id())).hasMessage("EXECUTION_TRANSACTION_ACTIVE");
            assertThatThrownBy(() -> service.acknowledgeStoppedFailure(owner, view, "confirm")).hasMessage("EXECUTION_TRANSACTION_ACTIVE");
        });
        verifyNoInteractions(repository, ownership, inputs);
    }

    @Test void privateInputFailureCannotExposeFilesystemOrDatabaseDetails() throws Exception {
        when(inputs.history(eq(run), eq(scope), any())).thenThrow(new java.io.IOException("private-business-database-configuration"));
        assertThatThrownBy(() -> service.inspectStoppedFailure(owner, project, run.id()))
                .hasMessage("RUN_RECONCILIATION_UNAVAILABLE").hasNoCause().hasNoSuppressedExceptions();
        verify(repository, never()).inspect(any(), any(), any(), any());
        verify(scope).close();
    }

    @Test void changedRunSnapshotBetweenReadingAndConfirmationCannotBeAccepted() throws Exception {
        when(repository.read(owner, project, run.id())).thenReturn(mock(ExecutionRun.class));
        assertThatThrownBy(() -> service.acknowledgeStoppedFailure(owner, view, "confirm"))
                .isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RUN_RECONCILIATION_CONFLICT"));
        verifyNoInteractions(ownership, inputs);
        verify(repository, never()).acknowledge(any(), any(), any(), any());
    }

    @Test void scopeAuthorityLostAfterWriteRollsBackWithoutRelease() throws Exception {
        when(repository.acknowledge(owner, view, HISTORY, "confirm")).thenAnswer(call -> {
            doThrow(new java.io.IOException("private-lease-detail")).when(scope).checkAuthority(); return audit;
        });
        assertThatThrownBy(() -> service.acknowledgeStoppedFailure(owner, view, "confirm"))
                .hasMessage("RUN_RECONCILIATION_UNAVAILABLE").hasNoCause().hasNoSuppressedExceptions();
        assertThat(transactions.rollbacks).isEqualTo(1);
        verify(ownership, never()).accepted(any(), any()); verify(scope).close();
    }

    private static class Transactions extends AbstractPlatformTransactionManager {
        int commits, rollbacks, timeout; boolean failCommit;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { timeout = definition.getTimeout(); }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            commits++; if (failCommit) throw new org.springframework.transaction.TransactionSystemException("private-commit-detail");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
