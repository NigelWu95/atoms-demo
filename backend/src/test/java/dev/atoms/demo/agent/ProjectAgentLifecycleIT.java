package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectAgentLifecycleIT extends AgentIntegrationSupport {
    private ProjectAgentService service;
    private AgentTaskRepository repository;
    private AgentTaskLifecycle lifecycle;
    @BeforeEach void lifecycle() {
        repository = new AgentTaskRepository(jdbc);
        service = new ProjectAgentService(settings, repository, projects, source.gateway(), transactions);
        lifecycle = new AgentTaskLifecycle(service, repository, projects, source.gateway(), transactions);
    }
    private AgentTask admit(String key) {
        var base = source.create(owner);
        var view = service.submit(owner, base.projectId(), new ProjectAgentService.Input("edit", base.versionId().toString(), 1), key);
        return repository.get(view.id());
    }

    @Test void aFreshTaskMustNotExpireByComparingDatabaseTimeToTheHostClock() {
        var task = admit("clock-domain");
        var databaseNow = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
        long skew = java.time.Duration.between(databaseNow, java.time.Instant.now()).toSeconds();
        System.out.println("Agent test clock difference (host minus database seconds): " + skew);
        assertThat(java.time.Duration.between(task.createdAt(), databaseNow)).isLessThan(java.time.Duration.ofSeconds(30));
        assertThat(lifecycle.preparing(task.id()).status()).isEqualTo("PREPARING");
    }

    @Test void paidExecutionReservationAndSourceIntentAreAtomicAndFailuresDoNotRefund() throws Exception {
        long before = jdbc.queryForObject("SELECT used FROM agent_live_budget WHERE id", Long.class);
        configuration.withProperty("atoms.agent.live-enabled", "true").withProperty("atoms.agent.max-live-runs", Long.toString(before+1));
        var one = admit("one"); var two = admit("two");
        lifecycle.preparing(one.id()); lifecycle.preparing(two.id());
        source.gateway().prepareEdit(service.handle(one.id())); source.gateway().prepareEdit(service.handle(two.id()));
        var started = lifecycle.startExecution(one.id(), true);
        assertThat(started.status()).isEqualTo("RUNNING"); assertThat(started.liveReserved()).isTrue();
        assertThat(jdbc.queryForObject("SELECT used FROM agent_live_budget WHERE id", Long.class)).isEqualTo(before+1);
        assertThatThrownBy(() -> lifecycle.startExecution(two.id(), true))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AGENT_QUOTA_EXHAUSTED"));
        assertThat(repository.get(two.id()).executionId()).isNull();
        assertThat(jdbc.queryForObject("SELECT execution_id FROM source_edit_leases WHERE id=?", UUID.class, two.editLeaseId())).isNull();
        assertThatThrownBy(() -> lifecycle.startExecution(one.id(), true)).isInstanceOf(ApiException.class);
        stop(started);
        assertThat(lifecycle.fail(one.id(), "AGENT_TOOL_FAILED", false).status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT used FROM agent_live_budget WHERE id", Long.class)).isEqualTo(before+1);
        assertThat(service.handle(one.id())).isNull();
    }

    @Test void disabledLiveCallsNeverRecordAnExecutionAndUnknownExitCannotBecomeTerminal() throws Exception {
        var task = admit("disabled"); lifecycle.preparing(task.id()); source.gateway().prepareEdit(service.handle(task.id()));
        assertThatThrownBy(() -> lifecycle.startExecution(task.id(), true)).isInstanceOf(ApiException.class);
        assertThat(repository.get(task.id()).executionId()).isNull();
        var started = lifecycle.startExecution(task.id(), false);
        assertThat(started.liveReserved()).isFalse();
        assertThat(lifecycle.fail(task.id(), "AGENT_PROCESS_UNSETTLED", true).status()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(jdbc.queryForObject("SELECT status FROM source_edit_leases WHERE id=?", String.class, task.editLeaseId())).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> lifecycle.fail(task.id(), "AGENT_TOOL_FAILED", false)).isInstanceOf(ApiException.class);
        stop(started);
        assertThat(lifecycle.fail(task.id(), "AGENT_TOOL_FAILED", false).status()).isEqualTo("FAILED");
    }

    @Test void queuedCancellationAndOwnershipChangesCannotBeginAnExecution() {
        var task = admit("owner");
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, task.projectId());
        assertThatThrownBy(() -> lifecycle.preparing(task.id())).isInstanceOf(ApiException.class);
        var cancelled = admit("cancel");
        jdbc.update("UPDATE agent_tasks SET cancel_requested=true WHERE id=?", cancelled.id());
        assertThat(lifecycle.preparing(cancelled.id()).status()).isEqualTo("CANCELLED");
        assertThat(repository.get(cancelled.id()).executionId()).isNull();
    }

    private void stop(AgentTask task) throws Exception {
        var handle = service.handle(task.id()); var work = source.gateway().workPath(handle);
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                Path.of("target/test-classes").toAbsolutePath().toString(), AgentChildFixture.class.getName(), "success").directory(work.toFile());
        command.environment().clear();
        var proof = new OwnedProcess().run(command, "fixture", () -> Duration.ofSeconds(10), () -> false,
                task.id(), task.executionId(), stream -> stream.readNBytes(2048)).stopped();
        source.gateway().confirmExecutionStopped(handle, proof);
    }
}
