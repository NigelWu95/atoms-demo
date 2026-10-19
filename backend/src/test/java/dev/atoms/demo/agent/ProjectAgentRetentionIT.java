package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class ProjectAgentRetentionIT extends AgentIntegrationSupport {
    AgentTaskRepository tasks;
    ProjectAgentService service;
    AgentRetention retention;
    AgentQuery query;
    @BeforeEach void setupRetention() {
        tasks = new AgentTaskRepository(jdbc);
        service = new ProjectAgentService(settings, tasks, projects, source.gateway(), transactions);
        retention = new AgentRetention(settings, jdbc, tasks, transactions);
        query = new AgentQuery(settings, projects, tasks, new AgentEventRepository(jdbc), transactions);
        // Prior runs retain immutable test audit rows. Drain their synthetic expired bodies only.
        for (int batch=0; batch<10 && retention.expireBatch(100)>0; batch++) { }
    }
    private AgentTask submit() {
        var base = source.create(owner);
        return tasks.get(service.submit(owner, base.projectId(), new ProjectAgentService.Input(
                "PRIVATE_RETENTION_CANARY", base.versionId().toString(), 1), "retained-key").id());
    }
    private AgentTask finish(AgentTask task, int hoursAgo, boolean release) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            tasks.lock(task.id());
            if (release) source.gateway().releaseEdit(service.handle(task.id()));
            jdbc.update("""
                    UPDATE agent_tasks SET status='FAILED',error_code='AGENT_TEST_FINISHED',
                        finished_at=clock_timestamp()-?*interval '1 hour' WHERE id=?
                    """, hoursAgo, task.id());
            new AgentEventRepository(jdbc).stage(tasks.get(task.id()));
            if (release) service.forgetAfterCommit(task.id());
        });
        return tasks.get(task.id());
    }
    private boolean expired(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT history_expired FROM agent_tasks WHERE id=?", Boolean.class, id));
    }

    @Test void expirationErasesOnlyBodiesAndPreservesIdempotencyResultsAndCursorWatermark() {
        var task = finish(submit(), 721, true);
        var before = service.get(owner, task.projectId(), task.id());
        int last = query.events(owner, task.projectId(), task.id(), 0, 100).lastSequence();
        assertThat(retention.expireBatch(100)).isPositive();
        assertThat(expired(task.id())).isTrue();
        assertThat(tasks.get(task.id()).prompt()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_events WHERE task_id=?", Integer.class, task.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT last_event_seq FROM agent_tasks WHERE id=?", Integer.class, task.id())).isEqualTo(last);
        assertThat(service.get(owner, task.projectId(), task.id())).isEqualTo(before);
        assertThat(service.submit(owner, task.projectId(), new ProjectAgentService.Input(
                task.prompt(), task.baseVersionId().toString(), task.baseRevision()), "retained-key")).isEqualTo(before);
        assertThat(service.handle(task.id())).isNull();
        assertThatThrownBy(() -> service.submit(owner, task.projectId(), new ProjectAgentService.Input(
                "different", task.baseVersionId().toString(), task.baseRevision()), "retained-key"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AGENT_IDEMPOTENCY_CONFLICT"));
        for (int cursor : new int[] {0, last}) assertThatThrownBy(() -> query.events(owner, task.projectId(), task.id(), cursor, 100))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AGENT_EVENT_HISTORY_EXPIRED"));
        assertThat(query.list(owner, task.projectId(), 100)).containsExactly(before);
        assertThat(retention.expireBatch(100)).isZero();
    }

    @Test void recentActiveUncertainAndUnreleasedTasksKeepTheirEvidence() {
        var recent = finish(submit(), 719, true);
        var active = submit();
        var uncertain = submit();
        new TransactionTemplate(transactions).executeWithoutResult(s -> tasks.finish(tasks.lock(uncertain.id()),
                "NEEDS_RECONCILIATION", "AGENT_EXECUTION_UNCERTAIN"));
        var unreleased = finish(submit(), 721, false);
        retention.expireBatch(100);
        for (var task : new AgentTask[] {recent, active, uncertain, unreleased}) {
            assertThat(expired(task.id())).isFalse();
            assertThat(tasks.get(task.id()).prompt()).isEqualTo("PRIVATE_RETENTION_CANARY");
            assertThat(query.events(owner, task.projectId(), task.id(), 0, 100).events()).isNotEmpty();
        }
    }

    @Test void batchesAreBoundedAndDisabledModuleDoesNotClean() {
        var first = finish(submit(), 721, true);
        var second = finish(submit(), 721, true);
        configuration.withProperty("atoms.agent.enabled", "false");
        retention.clean();
        assertThat(retention.expireBatch(1)).isZero();
        assertThat(expired(first.id())).isFalse(); assertThat(expired(second.id())).isFalse();
        configuration.withProperty("atoms.agent.enabled", "true");
        assertThat(retention.expireBatch(1)).isEqualTo(1);
        assertThat(expired(first.id()) ^ expired(second.id())).isTrue();
        assertThat(retention.expireBatch(1)).isEqualTo(1);
        assertThat(expired(first.id()) && expired(second.id())).isTrue();
        assertThatThrownBy(() -> retention.expireBatch(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retention.expireBatch(101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void failedDeletionRollsBackTheExpiryMarkerAndPromptTogether() {
        var task = finish(submit(), 721, true);
        var broken = new org.springframework.jdbc.core.JdbcTemplate(jdbc.getDataSource()) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("DELETE FROM agent_events"))
                    throw new org.springframework.dao.DataAccessResourceFailureException("RETENTION_FAILURE_CANARY");
                return super.update(sql, args);
            }
        };
        assertThatThrownBy(() -> new AgentRetention(settings, broken, tasks, transactions).expireBatch(100))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(expired(task.id())).isFalse();
        assertThat(tasks.get(task.id()).prompt()).isEqualTo(task.prompt());
        assertThat(query.events(owner, task.projectId(), task.id(), 0, 100).events()).hasSize(2);
        assertThat(retention.expireBatch(100)).isEqualTo(1);
    }

    @Test void competingCleanersSettleTheSameTaskOnlyOnce() throws Exception {
        var task = finish(submit(), 721, true);
        var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> clean = () -> { start.await(); return retention.expireBatch(1); };
            var first = threads.submit(clean); var second = threads.submit(clean); start.countDown();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)+second.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(expired(task.id())).isTrue();
            assertThat(tasks.get(task.id()).requestSha256()).isEqualTo(task.requestSha256());
        } finally {
            start.countDown(); threads.shutdownNow();
            assertThat(threads.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }
}
