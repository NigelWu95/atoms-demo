package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Authorized internal read boundary, deliberately independent of model invocation and HTTP transport. */
@Service
public class AgentQuery {
    private final AgentSettings settings;
    private final ProjectService projects;
    private final AgentTaskRepository tasks;
    private final AgentEventRepository events;
    private final TransactionTemplate transaction;
    AgentQuery(AgentSettings settings, ProjectService projects, AgentTaskRepository tasks, AgentEventRepository events,
               PlatformTransactionManager transactions) {
        this.settings = settings; this.projects = projects; this.tasks = tasks; this.events = events;
        this.transaction = new TransactionTemplate(transactions);
    }
    public record Event(int sequence, String kind, Map<String, Object> payload, Instant createdAt) {
        public Event { payload = Map.copyOf(payload); }
    }
    public record EventPage(List<Event> events, int nextCursor, boolean hasMore, int lastSequence) {
        public EventPage { events = List.copyOf(events); }
    }
    public List<ProjectAgentService.TaskView> list(UUID owner, UUID project, int limit) {
        limit(limit);
        return transaction.execute(status -> {
            authorize(owner, project);
            return tasks.list(project, limit).stream().map(ProjectAgentService::view).toList();
        });
    }
    public EventPage events(UUID owner, UUID project, UUID taskId, int after, int limit) {
        limit(limit); if (after<0 || after>4096) throw AgentTask.invalid();
        return transaction.execute(status -> {
            authorize(owner, project);
            var task = tasks.get(taskId);
            if (task == null || !task.projectId().equals(project)) throw new ApiException(404, "AGENT_TASK_MISSING", "任务不存在或当前账号无权访问。");
            return events.page(taskId, after, limit);
        });
    }
    private void authorize(UUID owner, UUID project) {
        if (owner == null || !settings.load().maintainerIds().contains(owner))
            throw new ApiException(403, "AGENT_NOT_ALLOWED", "当前账号未获准使用完整工程 Agent。");
        projects.lock(owner, project.toString());
    }
    private static void limit(int limit) { if (limit<1 || limit>100) throw AgentTask.invalid(); }
}
