package dev.atoms.demo.agent;

import dev.atoms.demo.source.ProjectSourceEditGateway;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class ProjectAgentRecoveryIT extends AgentIntegrationSupport {
    @Autowired DataSource database;
    AgentTaskRepository repository;
    ProjectAgentService service;
    AgentTaskLifecycle lifecycle;
    @BeforeEach void setupRecovery() {
        repository = new AgentTaskRepository(jdbc);
        service = new ProjectAgentService(settings, repository, projects, source.gateway(), transactions);
        lifecycle = new AgentTaskLifecycle(service, repository, projects, source.gateway(), transactions);
    }
    private AgentTask submit() {
        var base = source.create(owner);
        return repository.get(service.submit(owner, base.projectId(),
                new ProjectAgentService.Input("edit", base.versionId().toString(), 1), "recovery").id());
    }
    private AgentRecovery recovery() { return new AgentRecovery(repository, source.gateway(), transactions); }

    @Test void neverStartedTaskIsSettledWithoutReplayingItsPromptOrRestoringAHandle() {
        var task = submit();
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, task.projectId());
        assertThat(recovery().reconcile(task.id()).status()).isEqualTo("FAILED");
        assertThat(repository.get(task.id()).errorCode()).isEqualTo("AGENT_INTERRUPTED");
        assertThat(jdbc.queryForObject("SELECT status FROM source_edit_leases WHERE id=?", String.class, task.editLeaseId())).isEqualTo("RELEASED");
        assertThatThrownBy(() -> service.get(owner, task.projectId(), task.id())).isInstanceOf(dev.atoms.demo.common.ApiException.class);
        assertThat(recovery().reconcile(task.id()).status()).isEqualTo("FAILED");
    }

    @Test void unknownExecutionStaysFencedAndRecoveryDoesNotCreateAStopReceipt() throws Exception {
        var task = submit(); lifecycle.preparing(task.id());
        var handle = service.handle(task.id()); source.gateway().prepareEdit(handle);
        try (var session = new AgentFixtureAdapter(temporary, "success").prepare(task, source.gateway().workPath(handle),
                new AgentDeadline(java.time.Duration.ofSeconds(15)))) {
            var started = lifecycle.startExecution(task.id(), false);
            try {
                assertThat(recovery().reconcile(task.id()).status()).isEqualTo("NEEDS_RECONCILIATION");
                assertThat(recovery().reconcile(task.id()).status()).isEqualTo("NEEDS_RECONCILIATION");
                assertThat(jdbc.queryForObject("SELECT execution_pending FROM source_edit_leases WHERE id=?", Boolean.class, task.editLeaseId())).isTrue();
                assertThat(jdbc.queryForObject("SELECT stopped_sha256 FROM source_edit_leases WHERE id=?", String.class, task.editLeaseId())).isNull();
            } finally {
                // Only the original owner can supply real process evidence; no PID guessing in recovery.
                source.gateway().confirmExecutionStopped(handle, session.execute(started.executionId(), () -> false).stopped());
            }
        }
        assertThat(recovery().reconcile(task.id()).status()).isEqualTo("FAILED");
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void savedSourceIsRecoveredEvenBeforeTheAgentRecordedItsOperationId(boolean lateTimeout) throws Exception {
        source.start(database);
        var task = submit(); lifecycle.preparing(task.id()); var handle = service.handle(task.id());
        source.gateway().prepareEdit(handle);
        try (var session = new AgentFixtureAdapter(temporary, "project-edit").prepare(task, source.gateway().workPath(handle),
                new AgentDeadline(java.time.Duration.ofSeconds(30)))) {
            var started = lifecycle.startExecution(task.id(), false);
            source.gateway().confirmExecutionStopped(handle, session.execute(started.executionId(), () -> false).stopped());
        }
        var accepted = (ProjectSourceEditGateway.Submitted) source.gateway().sealAndAccept(handle, "recovery-source");
        var committed = accepted.completion().get(20, TimeUnit.SECONDS);
        assertThat(repository.get(task.id()).sourceOperationId()).isNull();
        long budget = jdbc.queryForObject("SELECT used FROM agent_live_budget WHERE id", Long.class);
        var recovered = lateTimeout ? lifecycle.fail(task.id(), "AGENT_TIMEOUT", false) : recovery().reconcile(task.id());
        assertThat(recovered.status()).isEqualTo("SOURCE_SAVED");
        assertThat(recovered.resultVersionId()).isEqualTo(committed.resultVersionId());
        assertThat(recovery().reconcile(task.id()).resultVersionId()).isEqualTo(committed.resultVersionId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, task.projectId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT used FROM agent_live_budget WHERE id", Long.class)).isEqualTo(budget);
    }
}
