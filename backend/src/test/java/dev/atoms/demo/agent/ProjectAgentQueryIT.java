package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class ProjectAgentQueryIT extends AgentIntegrationSupport {
    AgentTaskRepository repository;
    ProjectAgentService service;
    AgentEventRepository events;
    AgentQuery query;
    @BeforeEach void setupQuery() {
        repository = new AgentTaskRepository(jdbc);
        service = new ProjectAgentService(settings, repository, projects, source.gateway(), transactions);
        events = new AgentEventRepository(jdbc);
        query = new AgentQuery(settings, projects, repository, events, transactions);
    }
    private AgentTask submit(String prompt) {
        var base = source.create(owner);
        return repository.get(service.submit(owner, base.projectId(),
                new ProjectAgentService.Input(prompt, base.versionId().toString(), 1), "query").id());
    }
    private void stage(UUID id) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> events.stage(repository.lock(id)));
    }

    @Test void listsAndEventsAreBoundToTheCurrentOwnerAndProjectNotJustTheTaskId() {
        var task = submit("PRIVATE_PROMPT_CANARY"); stage(task.id());
        assertThat(query.list(owner, task.projectId(), 100)).hasSize(1);
        var page = query.events(owner, task.projectId(), task.id(), 0, 100);
        assertThat(page.events()).hasSize(1);
        assertThat(page.toString()).doesNotContain("PRIVATE_PROMPT_CANARY");
        assertThatThrownBy(() -> query.events(stranger, task.projectId(), task.id(), 0, 100)).isInstanceOf(ApiException.class);
        var another = source.create(owner);
        assertThatThrownBy(() -> query.events(owner, another.projectId(), task.id(), 0, 100)).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, task.projectId());
        assertThatThrownBy(() -> query.list(owner, task.projectId(), 100)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> query.events(owner, task.projectId(), task.id(), 0, 100)).isInstanceOf(ApiException.class);
    }

    @Test void cursorPagesAreBoundedMonotonicAndRejectInvalidInput() {
        var task = submit("edit"); stage(task.id()); stage(task.id()); // A repeated stage is not another event.
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            repository.lock(task.id()); events.usage(task.id(), 12, 34, 2);
        });
        var first = query.events(owner, task.projectId(), task.id(), 0, 1);
        assertThat(first.events()).hasSize(1); assertThat(first.hasMore()).isTrue();
        var second = query.events(owner, task.projectId(), task.id(), first.nextCursor(), 1);
        assertThat(second.events()).hasSize(1); assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isEqualTo(first.nextCursor()+1);
        assertThatThrownBy(() -> query.events(owner, task.projectId(), task.id(), -1, 100)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> query.events(owner, task.projectId(), task.id(), 0, 101)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> query.events(owner, task.projectId(), task.id(), 100, 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> query.list(owner, task.projectId(), 101)).isInstanceOf(ApiException.class);
    }

    @Test void regularEventsCannotConsumeTheReservedTerminalSpace() {
        var task = submit("edit");
        jdbc.update("UPDATE agent_tasks SET last_event_seq=4095,event_bytes=261120 WHERE id=?", task.id());
        stage(task.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_events WHERE task_id=?", Integer.class, task.id())).isEqualTo(1);
        service.cancel(owner, task.projectId(), task.id()); stage(task.id());
        var page = query.events(owner, task.projectId(), task.id(), 4095, 100);
        assertThat(page.events()).hasSize(1);
        assertThat(page.events().get(0).kind()).isEqualTo("RESULT");
        assertThat(page.nextCursor()).isEqualTo(4096);
        assertThat(jdbc.queryForObject("SELECT event_bytes FROM agent_tasks WHERE id=?", Integer.class, task.id())).isLessThanOrEqualTo(262144);
    }

    @Test void usageIsOnlyObservedMetadataAndCannotBeAppendedAfterTermination() {
        var task = submit("edit");
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            repository.lock(task.id()); events.usage(task.id(), 1, 2, 3);
        });
        var usage = query.events(owner, task.projectId(), task.id(), 0, 100).events().stream()
                .filter(event -> event.kind().equals("USAGE")).findFirst().orElseThrow();
        assertThat(usage.payload()).containsEntry("observedPaths", 3).doesNotContainKey("changedFiles");
        service.cancel(owner, task.projectId(), task.id());
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            repository.lock(task.id()); assertThat(events.usage(task.id(), 4, 5, 6)).isFalse();
        });
    }

    @Test void expiredOrCorruptedEventHistoryIsExplicitAndDoesNotExposeUnapprovedPayloads() {
        var task = submit("edit"); stage(task.id());
        jdbc.update("UPDATE agent_events SET payload=?::jsonb WHERE task_id=?", "{\"prompt\":\"SECRET_EVENT_CANARY\"}", task.id());
        assertThatThrownBy(() -> query.events(owner, task.projectId(), task.id(), 0, 100))
                .isInstanceOf(ApiException.class).hasMessageNotContaining("SECRET_EVENT_CANARY");
        jdbc.update("UPDATE agent_tasks SET history_expired=true WHERE id=?", task.id());
        assertThatThrownBy(() -> query.events(owner, task.projectId(), task.id(), 0, 100))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AGENT_EVENT_HISTORY_EXPIRED"));
    }
}
