package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class ProjectAgentCancellationIT extends AgentIntegrationSupport {
    @Autowired DataSource database;
    ProjectAgentService service;
    AgentTaskRepository repository;
    AgentTaskLifecycle lifecycle;
    @BeforeEach void setupWorker() {
        repository = new AgentTaskRepository(jdbc);
        service = new ProjectAgentService(settings, repository, projects, source.gateway(), transactions);
        lifecycle = new AgentTaskLifecycle(service, repository, projects, source.gateway(), transactions);
    }
    private ProjectAgentService.TaskView submit() {
        var base = source.create(owner);
        return service.submit(owner, base.projectId(), new ProjectAgentService.Input("edit", base.versionId().toString(), 1), "cancel");
    }
    private AgentWorker worker(CodingAgent agent) { return new AgentWorker(service, repository, lifecycle, source.gateway(), agent); }

    @Test void queuedCancelRequiresCurrentOwnershipAndNeverStartsTheAdapter() {
        var task = submit(); var adapter = new AgentFixtureAdapter(temporary, "success");
        assertThatThrownBy(() -> service.cancel(stranger, task.projectId(), task.id())).isInstanceOf(ApiException.class);
        assertThat(repository.get(task.id()).cancelRequested()).isFalse();
        assertThat(service.cancel(owner, task.projectId(), task.id()).status()).isEqualTo("CANCELLED");
        assertThat(worker(adapter).execute(task.id()).status()).isEqualTo("CANCELLED");
        assertThat(adapter.calls.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM source_edit_leases WHERE task_id=?", String.class, task.id())).isEqualTo("RELEASED");
    }

    @Test void runningCancelDoesNotClaimStoppedUntilOwnedProcessAndIoHaveExited() throws Exception {
        var task = submit(); var adapter = new AgentFixtureAdapter(temporary, "hang");
        var thread = Executors.newSingleThreadExecutor();
        try {
            var result = thread.submit(() -> worker(adapter).execute(task.id()));
            long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(adapter.pid(task.id())) && System.nanoTime()<end) Thread.sleep(10);
            assertThat(adapter.pid(task.id())).isRegularFile();
            var requested = service.cancel(owner, task.projectId(), task.id());
            assertThat(requested.cancelRequested()).isTrue();
            assertThat(requested.status()).isEqualTo("RUNNING");
            var stopped = result.get(15, TimeUnit.SECONDS);
            assertThat(stopped.status()).isEqualTo("CANCELLED");
            assertThat(ProcessHandle.of(Long.parseLong(Files.readString(adapter.pid(task.id())))).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(jdbc.queryForObject("SELECT execution_pending FROM source_edit_leases WHERE task_id=?", Boolean.class, task.id())).isFalse();
        } finally { thread.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancellationAndSourceCommitAreOrderedByTheSameProjectLock(boolean beforeCommit) throws Exception {
        var entered = new CountDownLatch(1); var unblock = new CountDownLatch(1);
        source.start(database, stage -> {
            if (stage.equals(beforeCommit ? "BEFORE_PUBLISH" : "AFTER_COMMIT")) {
                entered.countDown();
                try { if (!unblock.await(15, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            }
        });
        var task = submit(); var adapter = new AgentFixtureAdapter(temporary, "project-edit");
        var thread = Executors.newSingleThreadExecutor();
        try {
            var future = thread.submit(() -> worker(adapter).execute(task.id()));
            assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
            var cancelled = service.cancel(owner, task.projectId(), task.id());
            if (!beforeCommit) assertThat(cancelled.status()).isEqualTo("SOURCE_SAVED");
            unblock.countDown();
            var result = future.get(20, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(beforeCommit ? "CANCELLED" : "SOURCE_SAVED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, task.projectId()))
                    .isEqualTo(beforeCommit ? 1 : 2);
            assertThat(adapter.calls.get()).isEqualTo(1);
        } finally { unblock.countDown(); thread.shutdownNow(); }
    }

    @Test void interruptStillRecordsStoppedEvidenceAndRestoresInterruptFlag() throws Exception {
        var task = submit(); var adapter = new AgentFixtureAdapter(temporary, "hang");
        var result = new java.util.concurrent.CompletableFuture<AgentTask>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var settlement = new AgentTaskLifecycle(service, repository, projects, source.gateway(), transactions) {
            @Override AgentTask fail(UUID id, String code, boolean uncertain) {
                assertThat(Thread.currentThread().isInterrupted()).as("JDBC settlement must be allowed to acquire a connection").isFalse();
                return super.fail(id, code, uncertain);
            }
        };
        var thread = new Thread(() -> {
            try { var value = new AgentWorker(service, repository, settlement, source.gateway(), adapter).execute(task.id());
                interrupted.set(Thread.currentThread().isInterrupted()); result.complete(value); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        thread.start();
        try {
            long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(adapter.pid(task.id())) && System.nanoTime()<end) Thread.sleep(10);
            assertThat(adapter.pid(task.id())).isRegularFile();
            thread.interrupt();
            assertThat(result.get(15, TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
            assertThat(interrupted).isTrue();
            assertThat(jdbc.queryForObject("SELECT execution_pending FROM source_edit_leases WHERE task_id=?", Boolean.class, task.id())).isFalse();
            assertThat(ProcessHandle.of(Long.parseLong(Files.readString(adapter.pid(task.id())))).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } finally { thread.interrupt(); thread.join(15000); }
    }
}
