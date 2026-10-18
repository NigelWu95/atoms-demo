package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.ProjectSourceEditGateway;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Short state transitions. CLI and filesystem work must always happen outside these transactions. */
@Component
class AgentTaskLifecycle {
    private final ProjectAgentService service;
    private final AgentTaskRepository repository;
    private final ProjectService projects;
    private final ProjectSourceEditGateway source;
    private final TransactionTemplate transaction;
    AgentTaskLifecycle(ProjectAgentService service, AgentTaskRepository repository, ProjectService projects,
                       ProjectSourceEditGateway source, PlatformTransactionManager transactions) {
        this.service = service; this.repository = repository; this.projects = projects; this.source = source;
        this.transaction = new TransactionTemplate(transactions);
    }

    AgentTask preparing(UUID id) {
        return transaction.execute(status -> {
            var task = repository.lock(id); authorize(task);
            if (task.terminal()) return task;
            if (!task.status().equals("QUEUED")) throw AgentTask.conflict("AGENT_TASK_CONFLICT");
            if (task.cancelRequested()) return finish(task, "CANCELLED", "AGENT_CANCELLED");
            if (!Instant.now().isBefore(task.createdAt().plusSeconds(task.queueTimeoutSeconds())))
                return finish(task, "TIMED_OUT", "AGENT_QUEUE_TIMEOUT");
            requireHandle(task.id());
            return repository.preparing(task);
        });
    }

    AgentTask startExecution(UUID id, boolean live) {
        return transaction.execute(status -> {
            var task = repository.lock(id); var config = authorize(task);
            if (!task.status().equals("PREPARING") || task.executionId() != null || task.cancelRequested())
                throw AgentTask.conflict("AGENT_TASK_CONFLICT");
            if (live && !config.liveEnabled()) throw new ApiException(503, "AGENT_LIVE_DISABLED", "真实工程 Agent 调用尚未启用。");
            if (task.maxTokens() != config.maxOutputTokens() || task.maxTurns() != config.maxTurns()
                    || task.taskTimeoutSeconds() != config.taskTimeout().toSeconds())
                throw AgentTask.conflict("AGENT_CONFIGURATION_CHANGED");
            UUID execution = UUID.randomUUID();
            source.markExecutionStarted(requireHandle(id), execution);
            if (live) repository.reserveLive(config.maxLiveRuns());
            return repository.running(task, execution, live);
        });
    }

    AgentTask fail(UUID id, String code, boolean uncertain) {
        return transaction.execute(status -> {
            var task = repository.lock(id);
            if (task.terminal()) return task;
            if (uncertain) return repository.finish(task, "NEEDS_RECONCILIATION", code);
            String terminal = code.equals("AGENT_CANCELLED") ? "CANCELLED"
                    : code.equals("AGENT_TIMEOUT") || code.equals("SOURCE_OPERATION_TIMEOUT") ? "TIMED_OUT" : "FAILED";
            return finish(task, terminal, code);
        });
    }

    private AgentSettings.Configuration authorize(AgentTask task) {
        var config = service.authorizeMaintainer(task.ownerId());
        if (projects.lock(task.ownerId(), task.projectId().toString()).archived()) throw AgentTask.conflict("AGENT_PROJECT_ARCHIVED");
        return config;
    }
    private ProjectSourceEditGateway.EditHandle requireHandle(UUID id) {
        var handle = service.handle(id);
        if (handle == null) throw AgentTask.conflict("AGENT_EDIT_UNAVAILABLE");
        return handle;
    }
    private AgentTask finish(AgentTask task, String status, String code) {
        source.releaseEdit(requireHandle(task.id()));
        var result = repository.finish(task, status, code);
        service.forgetAfterCommit(task.id());
        return result;
    }
}
