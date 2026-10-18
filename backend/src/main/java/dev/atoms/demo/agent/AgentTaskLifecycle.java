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
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentTaskLifecycle.class);
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
        var result = transaction.execute(status -> {
            var task = repository.lock(id);
            if (task.terminal()) return task;
            var operation = source.operationFact(requireHandle(id));
            if (operation != null && operation.status().equals("COMMITTED")) {
                requireMatchingSource(task, operation);
                task = repository.bindSource(task, operation.id());
                return repository.sourceSaved(task, operation.resultVersionId());
            }
            if (uncertain) return repository.finish(task, "NEEDS_RECONCILIATION", code);
            String terminal = task.cancelRequested() || code.equals("AGENT_CANCELLED") ? "CANCELLED"
                    : code.equals("AGENT_TIMEOUT") || code.equals("SOURCE_OPERATION_TIMEOUT") ? "TIMED_OUT" : "FAILED";
            return finish(task, terminal, terminal.equals("CANCELLED") ? "AGENT_CANCELLED" : code);
        });
        if (result.status().equals("SOURCE_SAVED")) releaseSaved(result);
        return result;
    }

    AgentTask phase(UUID id, String expected, String next) {
        if (!(expected.equals("RUNNING") && next.equals("VALIDATING")
                || expected.equals("VALIDATING") && next.equals("ACCEPTING"))) throw new IllegalArgumentException("Invalid Agent phase");
        return transaction.execute(status -> {
            var task = repository.lock(id); authorize(task);
            if (task.cancelRequested()) throw AgentTask.conflict("AGENT_CANCELLED");
            return repository.phase(task, expected, next);
        });
    }

    void usage(UUID id, AgentStreamDecoder.Summary summary) {
        transaction.executeWithoutResult(status -> {
            var task = repository.lock(id);
            if (!task.terminal()) repository.usage(task, summary);
        });
    }

    AgentTask noChanges(UUID id) {
        return transaction.execute(status -> {
            var task = repository.lock(id); authorize(task);
            if (!task.status().equals("ACCEPTING")) throw AgentTask.conflict("AGENT_TASK_CONFLICT");
            if (task.cancelRequested()) return finish(task, "CANCELLED", "AGENT_CANCELLED");
            return finish(task, "NO_CHANGES", null);
        });
    }

    void bindSource(UUID id, dev.atoms.demo.source.SourceOperation operation) {
        transaction.executeWithoutResult(status -> {
            var task = repository.lock(id); requireMatchingSource(task, operation);
            repository.bindSource(task, operation.id());
        });
    }

    AgentTask sourceSaved(UUID id, dev.atoms.demo.source.SourceOperation operation) {
        if (!operation.status().equals("COMMITTED")) throw AgentTask.conflict("AGENT_SOURCE_NOT_SAVED");
        var result = transaction.execute(status -> {
            var task = repository.lock(id); requireMatchingSource(task, operation);
            if (task.terminal()) return task;
            task = repository.bindSource(task, operation.id());
            return repository.sourceSaved(task, operation.resultVersionId());
        });
        releaseSaved(result);
        return result;
    }

    private void releaseSaved(AgentTask result) {
        UUID id = result.id();
        if (service.handle(id) == null) return;
        // A cleanup failure cannot undo or misreport an already committed source version.
        try {
            transaction.executeWithoutResult(status -> {
                source.releaseEdit(requireHandle(id)); service.forgetAfterCommit(id);
            });
        } catch (RuntimeException error) {
            LOG.warn("{\"event\":\"agent_saved_edit_retained\",\"entryPoint\":\"{}\",\"requestId\":\"{}\",\"errorCode\":\"AGENT_EDIT_RELEASE_PENDING\"}",
                    result.entryPoint(), id);
        }
    }

    private void requireMatchingSource(AgentTask task, dev.atoms.demo.source.SourceOperation operation) {
        if (!task.projectId().equals(operation.projectId()) || !task.editLeaseId().equals(operation.editLeaseId()))
            throw AgentTask.conflict("AGENT_SOURCE_MISMATCH");
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
