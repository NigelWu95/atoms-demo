package dev.atoms.demo.agent;

import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class ProjectAgentCoordinatorIT extends AgentIntegrationSupport {
    @Autowired DataSource database;
    AgentTaskRepository repository;
    ProjectAgentService service;
    AgentTaskLifecycle lifecycle;
    @BeforeEach void setupCoordinator() {
        repository = new AgentTaskRepository(jdbc);
        service = new ProjectAgentService(settings, repository, projects, source.gateway(), transactions);
        lifecycle = new AgentTaskLifecycle(service, repository, projects, source.gateway(), transactions);
    }
    private AgentCoordinator coordinator(CodingAgent adapter) {
        return coordinator(adapter, database);
    }
    private AgentCoordinator coordinator(CodingAgent adapter, DataSource leaseDatabase) {
        return new AgentCoordinator(settings, repository, service,
                new AgentWorker(service, repository, lifecycle, source.gateway(), adapter),
                new AgentRecovery(repository, source.gateway(), transactions), source.gateway(), leaseDatabase);
    }
    private ProjectAgentService.TaskView submit() {
        var base = source.create(owner);
        return service.submit(owner, base.projectId(), new ProjectAgentService.Input("edit", base.versionId().toString(), 1), "dispatch");
    }
    private AgentTask awaitTerminal(UUID id) throws Exception {
        long until = System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime()<until) {
            // Commit is visible before afterCommit/local cleanup returns. Assert both within the same bound.
            var task = repository.get(id); if (task.terminal() && service.handle(id) == null) return task;
            Thread.sleep(30);
        }
        throw new AssertionError("Task did not settle");
    }
    private void awaitProcess(AgentFixtureAdapter adapter, UUID id) throws Exception {
        long until = System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while ((!Files.exists(adapter.pid(id)) || Files.size(adapter.pid(id)) == 0) && System.nanoTime()<until) Thread.sleep(20);
        assertThat(adapter.pid(id)).isRegularFile();
        assertThat(Files.readString(adapter.pid(id))).matches("[0-9]+");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void distinctProjectsUseConfiguredSlotsAndCancellationAdmitsTheNextTask(int configured) throws Exception {
        if (configured != 0) configuration.withProperty("atoms.agent.max-concurrent-tasks", Integer.toString(configured));
        int expected = configured == 0 ? 4 : configured;
        source.start(database); var adapter = new AgentFixtureAdapter(temporary, "hang");
        var running = new java.util.ArrayList<ProjectAgentService.TaskView>();
        try (var coordinator = coordinator(adapter)) {
            coordinator.start();
            for (int i=0; i<expected; i++) {
                var task = submit(); running.add(task); awaitProcess(adapter, task.id());
            }
            assertThat(running.stream().map(task -> repository.get(task.id()).executionId()).toList())
                    .doesNotContainNull().doesNotHaveDuplicates();
            assertThat(running.stream().map(task -> adapter.work.get(task.id())).toList())
                    .doesNotContainNull().doesNotHaveDuplicates();
            var pids = new java.util.ArrayList<Long>();
            for (var task : running) {
                long pid = Long.parseLong(Files.readString(adapter.pid(task.id()))); pids.add(pid);
                assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isTrue();
            }
            assertThat(pids).doesNotHaveDuplicates();
            var queued = submit();
            // Existing children remain blocked: observe several dispatcher polls, not just admission.
            long until = System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(750);
            do {
                assertThat(repository.get(queued.id()).status()).isEqualTo("QUEUED");
                assertThat(adapter.calls.get()).isEqualTo(expected);
                assertThat(adapter.pid(queued.id())).doesNotExist();
                Thread.sleep(25);
            } while (System.nanoTime()<until);
            var cancelled = running.get(0);
            service.cancel(owner, cancelled.projectId(), cancelled.id());
            assertThat(awaitTerminal(cancelled.id()).status()).isEqualTo("CANCELLED");
            awaitProcess(adapter, queued.id());
            assertThat(adapter.calls.get()).isEqualTo(expected+1);
            for (var task : running.subList(1, running.size())) {
                assertThat(repository.get(task.id()).status()).isEqualTo("RUNNING");
                assertThat(ProcessHandle.of(Long.parseLong(Files.readString(adapter.pid(task.id()))))
                        .map(ProcessHandle::isAlive).orElse(false)).isTrue();
            }
            assertThat(ProcessHandle.of(Long.parseLong(Files.readString(adapter.pid(cancelled.id()))))
                    .map(ProcessHandle::isAlive).orElse(false)).isFalse();
        }
    }

    @Test void aRunningProjectRejectsAnotherEditUntilItsLeaseIsReleased() throws Exception {
        source.start(database); var adapter = new AgentFixtureAdapter(temporary, "hang");
        try (var coordinator = coordinator(adapter)) {
            coordinator.start(); var first = submit(); awaitProcess(adapter, first.id());
            var request = new ProjectAgentService.Input("next edit", first.baseSourceVersionId().toString(), first.baseSourceRevision());
            assertThatThrownBy(() -> service.submit(owner, first.projectId(), request, "next"))
                    .isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("SOURCE_EDIT_BUSY"));
            assertThat(adapter.calls.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_tasks WHERE project_id=?", Integer.class, first.projectId())).isEqualTo(1);
            service.cancel(owner, first.projectId(), first.id()); awaitTerminal(first.id());
            var next = service.submit(owner, first.projectId(), request, "next");
            awaitProcess(adapter, next.id());
            assertThat(next.id()).isNotEqualTo(first.id());
            assertThat(adapter.work.get(next.id())).isNotEqualTo(adapter.work.get(first.id()));
            assertThat(adapter.calls.get()).isEqualTo(2);
        }
    }

    @Test void disabledOrMissingSourceCoordinatorNeverRunsTheAdapter() throws Exception {
        var adapter = new AgentFixtureAdapter(temporary, "success");
        configuration.withProperty("atoms.agent.enabled", "false");
        try (var disabled = coordinator(adapter)) { disabled.start(); assertThat(disabled.isRunning()).isFalse(); }
        configuration.withProperty("atoms.agent.enabled", "true");
        try (var missing = coordinator(adapter)) { missing.start(); assertThat(missing.isRunning()).isFalse(); }
        assertThat(adapter.calls.get()).isZero();
        assertThat(temporary.resolve("agent-state")).doesNotExist();
    }

    @Test void oneDispatcherRunsNewTasksAutomaticallyAndRejectsAnotherDispatcher() throws Exception {
        source.start(database); var adapter = new AgentFixtureAdapter(temporary, "project-edit");
        try (var first = coordinator(adapter); var second = coordinator(adapter)) {
            first.start(); assertThat(first.isRunning()).isTrue();
            second.start(); assertThat(second.isRunning()).isFalse();
            assertThat(first.getPhase()).isEqualTo(Integer.MAX_VALUE);
            var task = submit();
            assertThat(awaitTerminal(task.id()).status()).isEqualTo("SOURCE_SAVED");
            assertThat(adapter.calls.get()).isEqualTo(1);
            var events = new AgentQuery(settings, projects, repository, new AgentEventRepository(jdbc), transactions)
                    .events(owner, task.projectId(), task.id(), 0, 100);
            assertThat(events.events().stream().filter(e -> !e.kind().equals("USAGE")).map(e -> e.payload().get("status")))
                    .containsExactly("QUEUED", "PREPARING", "RUNNING", "VALIDATING", "ACCEPTING", "SOURCE_SAVED");
        }
    }

    @Test void shutdownStopsItsOwnedProcessBeforeReleasingDispatchAndSettlesQueuedWork() throws Exception {
        configuration.withProperty("atoms.agent.max-concurrent-tasks", "2");
        source.start(database); var adapter = new AgentFixtureAdapter(temporary, "hang");
        try (var coordinator = coordinator(adapter)) {
            coordinator.start(); var first = submit(); awaitProcess(adapter, first.id());
            var second = submit(); awaitProcess(adapter, second.id());
            var queued = submit();
            coordinator.close();
            assertThat(repository.get(first.id()).status()).isEqualTo("CANCELLED");
            assertThat(repository.get(second.id()).status()).isEqualTo("CANCELLED");
            assertThat(repository.get(queued.id()).status()).isEqualTo("FAILED");
            assertThat(adapter.calls.get()).isEqualTo(2);
            assertThat(ProcessHandle.of(Long.parseLong(Files.readString(adapter.pid(first.id())))).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(ProcessHandle.of(Long.parseLong(Files.readString(adapter.pid(second.id())))).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(service.handle(first.id())).isNull(); assertThat(service.handle(queued.id())).isNull();
            assertThat(service.handle(second.id())).isNull();
            try (var next = new AgentCoordinatorLease(database)) { assertThat(next.healthy()).isTrue(); }
        }
    }

    @Test void waitingPastItsQueueBudgetDoesNotStartAnotherModelProcess() throws Exception {
        source.start(database); var adapter = new AgentFixtureAdapter(temporary, "hang");
        configuration.withProperty("atoms.agent.queue-timeout-seconds", "1");
        configuration.withProperty("atoms.agent.max-concurrent-tasks", "1");
        try (var coordinator = coordinator(adapter)) {
            coordinator.start(); var first = submit(); awaitProcess(adapter, first.id());
            var queued = submit();
            assertThat(awaitTerminal(queued.id()).status()).isEqualTo("TIMED_OUT");
            assertThat(adapter.calls.get()).isEqualTo(1);
        }
    }

    @Test void lostDispatchOwnershipStopsTheCurrentProcess() throws Exception {
        source.start(database); var adapter = new AgentFixtureAdapter(temporary, "hang");
        var leaseDatabase = org.mockito.Mockito.mock(DataSource.class);
        var connection = database.getConnection();
        org.mockito.Mockito.when(leaseDatabase.getConnection()).thenReturn(connection);
        try (var coordinator = coordinator(adapter, leaseDatabase)) {
            coordinator.start(); var task = submit(); awaitProcess(adapter, task.id());
            var second = submit(); awaitProcess(adapter, second.id());
            try (var statement = connection.createStatement()) { statement.execute("SELECT pg_advisory_unlock(718395202)"); }
            assertThat(awaitTerminal(task.id()).status()).isEqualTo("CANCELLED");
            assertThat(awaitTerminal(second.id()).status()).isEqualTo("CANCELLED");
            assertThat(coordinator.isRunning()).isFalse();
            assertThat(ProcessHandle.of(Long.parseLong(Files.readString(adapter.pid(task.id())))).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(ProcessHandle.of(Long.parseLong(Files.readString(adapter.pid(second.id())))).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        }
    }

    @Test void unexpectedAdapterFailureCannotStrandAQueuedTaskOrSilentlyRetryIt() throws Exception {
        source.start(database);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        CodingAgent failed = (task, work, deadline) -> { calls.incrementAndGet(); throw new AssertionError("CONTROLLED_ADAPTER_FAILURE"); };
        try (var coordinator = coordinator(failed)) {
            coordinator.start(); var task = submit();
            assertThat(awaitTerminal(task.id()).status()).isEqualTo("FAILED");
            assertThat(calls.get()).isEqualTo(1);
            assertThat(service.handle(task.id())).isNull();
        }
    }
}
