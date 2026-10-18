package dev.atoms.demo.agent;

import dev.atoms.demo.source.ProjectSourceEditGateway;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Invoked only with exclusive coordinator ownership after source recovery; no CLI, paths or PID probing. */
@Component
final class AgentRecovery {
    private final AgentTaskRepository tasks;
    private final ProjectSourceEditGateway source;
    private final TransactionTemplate transaction;
    AgentRecovery(AgentTaskRepository tasks, ProjectSourceEditGateway source, PlatformTransactionManager transactions) {
        this.tasks = tasks; this.source = source; this.transaction = new TransactionTemplate(transactions);
    }

    AgentTask reconcile(UUID id) {
        return transaction.execute(status -> {
            var task = tasks.lock(id);
            var fact = source.reconcileAbandonedEdit(task.projectId(), task.editLeaseId(), task.id());
            if (task.terminal()) return task;
            if (fact.uncertain()) return tasks.finish(task, "NEEDS_RECONCILIATION", "AGENT_EXECUTION_UNCERTAIN");
            var operation = fact.operation();
            if (operation != null && operation.status().equals("COMMITTED")) {
                task = tasks.bindSource(task, operation.id());
                return tasks.sourceSaved(task, operation.resultVersionId());
            }
            if (operation != null) task = tasks.bindSource(task, operation.id());
            return tasks.finish(task, task.cancelRequested() ? "CANCELLED" : "FAILED",
                    task.cancelRequested() ? "AGENT_CANCELLED" : "AGENT_INTERRUPTED");
        });
    }
}
