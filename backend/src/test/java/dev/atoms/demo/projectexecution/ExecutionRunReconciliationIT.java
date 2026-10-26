package dev.atoms.demo.projectexecution;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Rolled-back metadata with an actual stopped Java child; no business SQL or application migration. */
@ActiveProfiles("test") @Transactional
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionRunReconciliationIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final ExecutionRunStateIT fixture = new ExecutionRunStateIT();
    static final String HISTORY = "b".repeat(64);
    ExecutionRunReconciliationRepository repository;

    @BeforeEach void metadata() throws Exception {
        fixture.jdbc = jdbc; fixture.transactions = transactions; fixture.temporary = temporary.toRealPath();
        fixture.metadata();
        repository = new ExecutionRunReconciliationRepository(jdbc);
    }

    @Test void explicitAcknowledgementIsAtomicIdempotentAndPreservesOriginalFailure() {
        var failed = stoppedFailure();
        var view = repository.inspect(fixture.owner, fixture.project, failed.id(), HISTORY);
        var audit = repository.acknowledge(fixture.owner, view, HISTORY, "confirm-one");
        assertThat(repository.acknowledge(fixture.owner, view, HISTORY, "confirm-one")).isEqualTo(audit);
        var ended = fixture.repository.get(fixture.project, failed.id());
        assertThat(ended.status()).isEqualTo("FAILED"); assertThat(ended.finishedAt()).isNotNull();
        assertThat(ended.errorCode()).isEqualTo(failed.errorCode());
        assertThat(ended.executionId()).isEqualTo(failed.executionId());
        assertThat(ended.stoppedSha256()).isEqualTo(failed.stoppedSha256());
        assertThat(audit.runId()).isEqualTo(failed.id()); assertThat(audit.historySha256()).isEqualTo(HISTORY);
        assertThat(count()).isEqualTo(1);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        assertThat(fixture.repository.unfinished()).noneMatch(run -> run.id().equals(failed.id()));
    }

    @Test void issuedButUnsettledProcessCannotBeAcknowledged() {
        rejected(() -> repository.inspect(fixture.owner, fixture.project, fixture.run.id(), HISTORY));
        fixture.repository.begin(fixture.run, fixture.execution);
        fixture.repository.finish(fixture.run, fixture.execution, temporary,
                new ExecutionRunProcess.Outcome("RUN_PROCESS_UNSETTLED", new ExecutionRunProcess.Output(0, 0), null), null);
        rejected(() -> repository.inspect(fixture.owner, fixture.project, fixture.run.id(), HISTORY));
        assertThat(fixture.repository.get(fixture.project, fixture.run.id()).executionPending()).isTrue();
        assertThat(count()).isZero();
    }

    @Test void changedHistoryOrRunSnapshotRequiresFreshExplicitConfirmation() {
        var failed = stoppedFailure();
        var view = repository.inspect(fixture.owner, fixture.project, failed.id(), HISTORY);
        rejected(() -> repository.acknowledge(fixture.owner, view, "c".repeat(64), "stale-history"));
        assertThat(fixture.repository.get(fixture.project, failed.id())).isEqualTo(failed);
        fixture.repository.stop(fixture.owner, fixture.project, failed.id());
        rejected(() -> repository.acknowledge(fixture.owner, view, HISTORY, "stale-run"));
        assertThat(count()).isZero();
        var refreshed = repository.inspect(fixture.owner, fixture.project, failed.id(), HISTORY);
        repository.acknowledge(fixture.owner, refreshed, HISTORY, "fresh");
        assertThat(count()).isEqualTo(1);
    }

    @Test void wrongIdentityArchivedProjectAndMalformedConfirmationNeverWriteAnAudit() {
        var failed = stoppedFailure();
        var view = repository.inspect(fixture.owner, fixture.project, failed.id(), HISTORY);
        rejected(() -> repository.inspect(UUID.randomUUID(), fixture.project, failed.id(), HISTORY));
        rejected(() -> repository.inspect(fixture.owner, UUID.randomUUID(), failed.id(), HISTORY));
        rejected(() -> repository.inspect(fixture.owner, fixture.project, UUID.randomUUID(), HISTORY));
        rejected(() -> repository.acknowledge(UUID.randomUUID(), view, HISTORY, "wrong-owner"));
        for (String key : Arrays.asList(null, "", " ", "a".repeat(129), "line\nbreak", "\ud800"))
            rejected(() -> repository.acknowledge(fixture.owner, view, HISTORY, key));
        rejected(() -> repository.acknowledge(fixture.owner, view, "invalid", "invalid-history"));
        jdbc.update("UPDATE projects SET archived=true WHERE id=?", fixture.project);
        rejected(() -> repository.acknowledge(fixture.owner, view, HISTORY, "archived"));
        assertThat(count()).isZero();
        assertThat(fixture.repository.get(fixture.project, failed.id())).isEqualTo(failed);
    }

    @Test void changedConfirmationCannotOverwriteOrDeleteAnExistingAudit() {
        var failed = stoppedFailure();
        var view = repository.inspect(fixture.owner, fixture.project, failed.id(), HISTORY);
        var audit = repository.acknowledge(fixture.owner, view, HISTORY, "accepted");
        rejected(() -> repository.acknowledge(fixture.owner, view, HISTORY, "different-key"));
        var changed = new ExecutionRunReconciliationRepository.View(failed, "c".repeat(64));
        rejected(() -> repository.acknowledge(fixture.owner, changed, changed.historySha256(), "accepted"));
        for (String sql : List.of("UPDATE execution_run_reconciliations SET history_sha256='" + "d".repeat(64) + "' WHERE id=?",
                "DELETE FROM execution_run_reconciliations WHERE id=?"))
            assertThatThrownBy(() -> nested().execute(tx -> jdbc.update(sql, audit.id())))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count()).isEqualTo(1);
        assertThat(repository.acknowledge(fixture.owner, view, HISTORY, "accepted")).isEqualTo(audit);
    }

    @Test void failedTerminalWriteRollsBackTheAuditAndPreservesTheUnfinishedRun() {
        var failed = stoppedFailure();
        var view = repository.inspect(fixture.owner, fixture.project, failed.id(), HISTORY);
        var broken = spy(jdbc);
        doAnswer(call -> {
            if (((String) call.getArgument(0)).startsWith("UPDATE execution_runs SET status='FAILED'"))
                throw new org.springframework.dao.DataAccessResourceFailureException("injected-write-failure");
            return call.callRealMethod();
        }).when(broken).update(anyString(), any(Object[].class));
        assertThatThrownBy(() -> nested().execute(tx -> new ExecutionRunReconciliationRepository(broken)
                .acknowledge(fixture.owner, view, HISTORY, "atomic"))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count()).isZero();
        assertThat(fixture.repository.get(fixture.project, failed.id())).isEqualTo(failed);
    }

    @Test void databaseConstraintRefusesAnAuditWithoutItsTerminalTransition() {
        var failed = stoppedFailure();
        var view = repository.inspect(fixture.owner, fixture.project, failed.id(), HISTORY);
        var incomplete = spy(jdbc);
        doAnswer(call -> ((String) call.getArgument(0)).startsWith("UPDATE execution_runs SET status='FAILED'")
                ? 1 : call.callRealMethod()).when(incomplete).update(anyString(), any(Object[].class));
        assertThatThrownBy(() -> nested().execute(tx -> {
            new ExecutionRunReconciliationRepository(incomplete).acknowledge(fixture.owner, view, HISTORY, "incomplete");
            jdbc.execute("SET CONSTRAINTS execution_run_reconciliation_finish IMMEDIATE");
            return null;
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count()).isZero();
        assertThat(fixture.repository.get(fixture.project, failed.id())).isEqualTo(failed);
    }

    private TransactionTemplate nested() {
        var nested = new TransactionTemplate(transactions);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return nested;
    }

    private ExecutionRun stoppedFailure() {
        fixture.repository.begin(fixture.run, fixture.execution);
        var suspended = new TransactionTemplate(transactions);
        suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var outcome = suspended.execute(tx -> {
            var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "--version")
                    .directory(temporary.toFile());
            command.environment().clear();
            var child = ExecutionRunProcess.start(command, fixture.run.id(), fixture.execution,
                    new ExecutionProcess.Deadline(Duration.ofSeconds(15)), () -> {});
            try { return child.await(Duration.ofSeconds(20)); }
            catch (Exception error) { child.requestStop(); throw new AssertionError(error); }
        });
        assertThat(outcome.stopped()).isNotNull();
        return fixture.repository.finish(fixture.run, fixture.execution, temporary.toAbsolutePath(), outcome, "RUN_HEALTH_INVALID");
    }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM execution_run_reconciliations WHERE project_id=?", Integer.class, fixture.project); }
    private void rejected(Runnable operation) { assertThatThrownBy(operation::run).isInstanceOf(dev.atoms.demo.common.ApiException.class); }
}
