package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.source.ProjectSourceEditGateway;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** File-edit pipeline only. Saving source is not a claim that the generated app builds or runs. */
@Component
final class AgentWorker {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentWorker.class);
    private final ProjectAgentService service;
    private final AgentTaskRepository repository;
    private final AgentTaskLifecycle lifecycle;
    private final ProjectSourceEditGateway source;
    private final CodingAgent agent;
    AgentWorker(ProjectAgentService service, AgentTaskRepository repository, AgentTaskLifecycle lifecycle,
                ProjectSourceEditGateway source, CodingAgent agent) {
        this.service = service; this.repository = repository; this.lifecycle = lifecycle; this.source = source; this.agent = agent;
    }

    AgentTask execute(UUID id) { return execute(id, () -> false); }

    AgentTask execute(UUID id, java.util.function.BooleanSupplier shutdown) {
        var task = repository.get(id);
        if (task == null) throw new ApiException(404, "AGENT_TASK_MISSING", "任务不存在。");
        if (!task.status().equals("QUEUED")) return task; // A duplicate dispatcher must not interfere with the owner.
        try { task = lifecycle.preparing(id); }
        catch (ApiException error) {
            if (error.code().equals("AGENT_TASK_CONFLICT")) return repository.get(id);
            throw error;
        }
        if (task.terminal()) return task;
        long started = System.nanoTime();
        var result = perform(task, shutdown);
        LOG.info("{\"event\":\"agent_task_finished\",\"entryPoint\":\"{}\",\"requestId\":\"{}\",\"projectId\":\"{}\",\"status\":\"{}\",\"errorCode\":\"{}\",\"durationMs\":{}}",
                result.entryPoint(), id, result.projectId(), result.status(), result.errorCode()==null ? "NONE" : result.errorCode(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        return result;
    }

    private AgentTask perform(AgentTask task, java.util.function.BooleanSupplier shutdown) {
        var handle = service.handle(task.id());
        var deadline = new AgentDeadline(Duration.ofSeconds(task.taskTimeoutSeconds()));
        boolean executionStarted = false, stopped = false;
        try {
            AgentStreamDecoder.Summary usage;
            var cancellation = new Cancellation(task, shutdown);
            source.prepareEdit(handle, () -> {
                if (cancellation.getAsBoolean()) throw new IOException("AGENT_CANCELLED");
                return deadline.remaining();
            });
            try (var session = agent.prepare(task, source.workPath(handle), deadline)) {
                deadline.remaining();
                if (cancellation.getAsBoolean()) throw new IOException("AGENT_CANCELLED");
                task = lifecycle.startExecution(task.id(), session.paid()); executionStarted = true;
                final CodingAgent.Result edited;
                try { edited = session.execute(task.executionId(), cancellation); }
                catch (CodingAgent.Failure error) {
                    if (error.stopped() != null) { confirmStopped(handle, error.stopped()); stopped = true; }
                    throw error;
                }
                confirmStopped(handle, edited.stopped()); stopped = true;
                usage = edited.summary();
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("AGENT_CANCELLED");
            lifecycle.usage(task.id(), usage);
            lifecycle.phase(task.id(), "RUNNING", "VALIDATING"); deadline.remaining();
            lifecycle.phase(task.id(), "VALIDATING", "ACCEPTING");
            var accepted = source.sealAndAccept(handle, "agent-task-" + task.id());
            if (accepted instanceof ProjectSourceEditGateway.NoChanges) return lifecycle.noChanges(task.id());
            var submitted = (ProjectSourceEditGateway.Submitted) accepted;
            lifecycle.bindSource(task.id(), submitted.operation());
            var saved = submitted.completion().get(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
            if (saved.status().equals("COMMITTED")) return lifecycle.sourceSaved(task.id(), saved);
            return fail(task.id(), saved.errorCode()==null ? "AGENT_SOURCE_NOT_SAVED" : saved.errorCode(), !saved.terminal());
        } catch (java.util.concurrent.TimeoutException error) {
            return fail(task.id(), "AGENT_TIMEOUT", executionStarted && !stopped);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); return fail(task.id(), "AGENT_CANCELLED", executionStarted && !stopped);
        } catch (IOException | RuntimeException | java.util.concurrent.ExecutionException error) {
            String code = error instanceof ApiException api ? api.code() : error instanceof IOException ? error.getMessage() : "AGENT_EXECUTION_FAILED";
            if (code == null || !code.matches("(AGENT|SOURCE)_[A-Z_]{1,80}")) code = "AGENT_EXECUTION_FAILED";
            if (Thread.currentThread().isInterrupted() || shutdown.getAsBoolean()) code = "AGENT_CANCELLED";
            return fail(task.id(), code, executionStarted && !stopped);
        }
    }

    private AgentTask fail(UUID id, String code, boolean uncertain) {
        boolean interrupted = Thread.interrupted();
        try {
            try { return lifecycle.fail(id, code, uncertain); }
            catch (RuntimeException error) { return lifecycle.fail(id, "AGENT_EXECUTION_UNCERTAIN", true); }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private void confirmStopped(ProjectSourceEditGateway.EditHandle handle, dev.atoms.demo.execution.OwnedProcess.Stopped proof) {
        // Let bounded JDBC acquisition persist the proof even when shutdown interrupted the worker.
        boolean interrupted = Thread.interrupted();
        try { source.confirmExecutionStopped(handle, proof); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    /** Bounded-rate DB cancellation/ownership observation; it never extends the execution deadline. */
    private final class Cancellation implements java.util.function.BooleanSupplier {
        private final UUID id, owner;
        private final java.util.function.BooleanSupplier shutdown;
        private long checked;
        private volatile boolean stopped;
        Cancellation(AgentTask task, java.util.function.BooleanSupplier shutdown) { id = task.id(); owner = task.ownerId(); this.shutdown = shutdown; }
        @Override public boolean getAsBoolean() {
            if (Thread.currentThread().isInterrupted() || shutdown.getAsBoolean()) return true;
            long now = System.nanoTime();
            if (!stopped && (checked == 0 || now-checked >= TimeUnit.MILLISECONDS.toNanos(250))) {
                stopped = repository.stopRequested(id, owner); checked = now;
            }
            return stopped;
        }
    }
}
