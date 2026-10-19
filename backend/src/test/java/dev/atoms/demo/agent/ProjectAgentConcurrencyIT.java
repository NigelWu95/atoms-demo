package dev.atoms.demo.agent;

import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class ProjectAgentConcurrencyIT extends AgentIntegrationSupport {
    enum Stop { CANCEL, OWNER_CHANGED, TIMEOUT }

    @ParameterizedTest @EnumSource(Stop.class)
    void duplicateWorkersCannotRestartOrUnlockAnExecutingProject(Stop stop) throws Exception {
        if (stop == Stop.TIMEOUT) configuration.setProperty("atoms.agent.task-timeout-seconds", "10");
        var base = source.create(owner);
        var repository = new AgentTaskRepository(jdbc);
        var service = new ProjectAgentService(settings, repository, projects, source.gateway(), transactions);
        var lifecycle = new AgentTaskLifecycle(service, repository, projects, source.gateway(), transactions);
        var adapter = new AgentFixtureAdapter(temporary, "hang");
        var worker = new AgentWorker(service, repository, lifecycle, source.gateway(), adapter);
        var input = new ProjectAgentService.Input("edit", base.versionId().toString(), 1);
        var task = service.submit(owner, base.projectId(), input, "concurrent");
        var threads = Executors.newFixedThreadPool(4);
        var recovery = new AgentRecovery(repository, source.gateway(), transactions);
        try {
            var first = threads.submit(() -> worker.execute(task.id()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(adapter.pid(task.id())) && !first.isDone() && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(adapter.pid(task.id())).isRegularFile();
            var started = repository.get(task.id());
            var startTogether = new CountDownLatch(1);
            var duplicates = java.util.stream.IntStream.range(0, 3).mapToObj(i -> threads.submit(() -> {
                startTogether.await(); return worker.execute(task.id());
            })).toList();
            startTogether.countDown();
            for (var duplicate : duplicates) {
                var observed = duplicate.get(5, TimeUnit.SECONDS);
                assertThat(observed.status()).isEqualTo("RUNNING");
                assertThat(observed.executionId()).isEqualTo(started.executionId());
            }
            assertThatThrownBy(() -> service.submit(owner, base.projectId(), input, "another-edit"))
                    .isInstanceOf(dev.atoms.demo.common.ApiException.class);
            assertThat(jdbc.queryForObject("SELECT execution_pending FROM source_edit_leases WHERE task_id=?", Boolean.class, task.id())).isTrue();
            assertThat(jdbc.queryForObject("SELECT stopped_sha256 FROM source_edit_leases WHERE task_id=?", String.class, task.id())).isNull();
            if (stop == Stop.CANCEL) service.cancel(owner, base.projectId(), task.id());
            if (stop == Stop.OWNER_CHANGED) jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, base.projectId());
            var stopped = first.get(15, TimeUnit.SECONDS);
            assertThat(stopped.status()).isEqualTo(switch (stop) {
                case CANCEL -> "CANCELLED";
                case OWNER_CHANGED -> "NEEDS_RECONCILIATION";
                case TIMEOUT -> "TIMED_OUT";
            });
            assertThat(jdbc.queryForObject("SELECT execution_pending FROM source_edit_leases WHERE task_id=?", Boolean.class, task.id())).isFalse();
            assertThat(jdbc.queryForObject("SELECT stopped_sha256 FROM source_edit_leases WHERE task_id=?", String.class, task.id())).matches("[a-f0-9]{64}");
            if (stop == Stop.OWNER_CHANGED) assertThat(recovery.reconcile(task.id()).status()).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT status FROM source_edit_leases WHERE task_id=?", String.class, task.id())).isEqualTo("RELEASED");
            assertThat(ProcessHandle.of(Long.parseLong(Files.readString(adapter.pid(task.id())))).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(worker.execute(task.id()).terminal()).isTrue();
            assertThat(adapter.calls.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT current_source_version_id FROM project_sources WHERE project_id=?", UUID.class, base.projectId())).isEqualTo(base.versionId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(1);
        } finally {
            threads.shutdownNow();
            assertThat(threads.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            // This is after all owned workers stopped; recovery may not infer that from a PID.
            recovery.reconcile(task.id());
        }
    }
}
