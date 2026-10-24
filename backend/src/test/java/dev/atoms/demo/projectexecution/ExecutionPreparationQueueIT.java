package dev.atoms.demo.projectexecution;

import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionPreparationQueueIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
    SourceAgentFixture source;
    MockEnvironment config;
    DependencyPreparationRepository repository;
    DependencyPreparationService service;
    ExecutionCoordinatorLease coordination;
    final List<DependencyPreparationRepository.Task> tasks = new ArrayList<>();

    @BeforeEach void fixture() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo("atoms_demo_test.atoms_verify_20261024_ex16");
        source = new SourceAgentFixture(jdbc, projects, transactions, temporary.toRealPath());
        jdbc.update("INSERT INTO guest_users(id) VALUES (?),(?)", owner, stranger);
        config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        for (String key : List.of("root", "node", "npm", "java", "maven"))
            config.withProperty("atoms.project-execution." + key, temporary.toRealPath().resolve("unused-" + key).toString());
        Path policy = temporary.toRealPath().resolve("policy.json");
        Files.copy(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json"), policy);
        Files.setPosixFilePermissions(policy, PosixFilePermissions.fromString("rw-------"));
        config.withProperty("atoms.project-execution.policy", policy.toString());
        repository = new DependencyPreparationRepository(jdbc); service = newService();
        coordination = new ExecutionCoordinatorLease(Objects.requireNonNull(jdbc.getDataSource()));
    }

    @AfterEach void close() throws Exception {
        try {
            if (source != null) for (var task : tasks) {
                // These queue fixtures never execute a tool or assign an execution identity.
                assertThat(lease(task)).containsEntry("execution_id", null).containsEntry("execution_pending", false);
                if (repository.get(task.projectId(), task.id()).finishedAt() != null) continue;
                source.gateway().releaseSettledEdit(service.handle(task.id()));
                jdbc.update("UPDATE dependency_preparations SET status='CANCELLED',cancel_requested=true,queue_slot=null,worker_slot=null,finished_at=now() WHERE id=?", task.id());
            }
        } finally {
            try { if (source != null) source.close(); }
            finally { if (coordination != null) coordination.close(); }
        }
    }

    @Test void orderedCandidatesDoNotClaimOrCreateExecutionDirectories() throws Exception {
        var first = submit(); var second = submit(); var queue = queue(service, repository);
        assertThat(queue.waiting()).containsExactly(first, second);
        var before = row(first); var leaseBefore = lease(first);
        assertThat(queue.beforeDispatch(first)).isEqualTo(first);
        assertThat(queue.dispatchable(first)).isTrue();
        assertThat(row(first)).isEqualTo(before); assertThat(lease(first)).isEqualTo(leaseBefore);
        assertThat(temporary.resolve("unused-root")).doesNotExist();
    }

    @Test void savedDeadlineStillExpiresAfterCurrentConfigurationIncreasesIt() throws Exception {
        config.withProperty("atoms.project-execution.queue-timeout-seconds", "1");
        var task = submit(); config.withProperty("atoms.project-execution.queue-timeout-seconds", "300");
        Thread.sleep(1200);
        var result = queue(service, repository).beforeDispatch(task);
        assertThat(result.status()).isEqualTo("TIMED_OUT");
        assertThat(result.errorCode()).isEqualTo("EXECUTION_QUEUE_TIMEOUT");
        assertReleased(task);
    }

    @Test void durableCancellationWinsOverExpiryAndReplaysTheTerminalFact() throws Exception {
        config.withProperty("atoms.project-execution.queue-timeout-seconds", "1");
        var task = submit();
        assertThat(newService().cancel(owner, task.projectId(), task.id()).status()).isEqualTo("QUEUED");
        Thread.sleep(1200);
        var queue = queue(service, repository); var result = queue.beforeDispatch(task);
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.cancelRequested()).isTrue(); assertThat(result.errorCode()).isEqualTo("EXECUTION_CANCELLED");
        assertThat(queue.cancelWaiting(task)).isEqualTo(result);
        assertThat(queue.dispatchable(result)).isFalse(); assertReleased(task);
    }

    enum Rejection { ARCHIVED, TRANSFERRED, REVOKED }
    @ParameterizedTest @EnumSource(Rejection.class)
    void invalidProjectPolicySettlesOnlyItsNeverStartedTask(Rejection reason) throws Exception {
        var task = submit();
        String code = switch (reason) {
            case ARCHIVED -> { jdbc.update("UPDATE projects SET archived=true WHERE id=?", task.projectId()); yield "EXECUTION_PROJECT_ARCHIVED"; }
            case TRANSFERRED -> { jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, task.projectId()); yield "EXECUTION_PREPARATION_OWNER_CHANGED"; }
            case REVOKED -> { config.withProperty("atoms.project-execution.maintainer-ids", stranger.toString()); yield "EXECUTION_FORBIDDEN"; }
        };
        var result = queue(service, repository).beforeDispatch(task);
        assertThat(result.status()).isEqualTo("FAILED"); assertThat(result.errorCode()).isEqualTo(code);
        assertReleased(task);
    }

    @Test void shutdownCanSettleTheOwnedQueueAfterDisableAndTransfer() throws Exception {
        var task = submit(); var queue = queue(service, repository);
        jdbc.update("UPDATE projects SET owner_id=?,archived=true WHERE id=?", stranger, task.projectId());
        config.withProperty("atoms.project-execution.enabled", "false");
        assertUnavailable(() -> queue.beforeDispatch(task));
        assertThat(queue.cancelWaiting(task).status()).isEqualTo("CANCELLED");
        assertReleased(task);
    }

    @Test void claimedAndUnknownTasksCannotBeReleasedThroughTheWaitingQueue() throws Exception {
        var task = submit(); var queue = queue(service, repository);
        var claimed = new DependencyPreparationClaim(new ExecutionSettings(config), projects, repository,
                new ExecutionBuildRepository(jdbc), source.gateway(), jdbc, transactions)
                .claim(owner, task.projectId(), task.id(), service.handle(task.id()));
        assertThat(queue.waiting()).isEmpty(); assertThat(queue.dispatchable(claimed)).isFalse();
        var before = row(task); assertThat(queue.cancelWaiting(task)).isEqualTo(claimed);
        assertThat(row(task)).isEqualTo(before);
        jdbc.update("UPDATE dependency_preparations SET status='NEEDS_RECONCILIATION' WHERE id=?", task.id());
        var unknown = row(task); queue.cancelWaiting(task);
        assertThat(row(task)).isEqualTo(unknown);
        assertRetained(task);
    }

    @Test void missingCapabilityWaitsForAdmissionHandoffOrStartupRecovery() throws Exception {
        var task = submit(); var queue = queue(newService(), repository); var before = lease(task);
        assertThat(queue.dispatchable(task)).isFalse();
        assertThat(queue.beforeDispatch(task)).isEqualTo(task);
        var cancelled = queue.cancelWaiting(task);
        assertThat(cancelled.status()).isEqualTo("QUEUED"); assertThat(cancelled.cancelRequested()).isTrue();
        assertThat(lease(task)).isEqualTo(before); assertRetained(task);
        assertThat(queue(service, repository).cancelWaiting(task).status()).isEqualTo("CANCELLED");
        assertReleased(task);
    }

    @Test void failedWriteRollsBackTheSourceReleaseAndSharedSlot() throws Exception {
        var task = submit(); var before = row(task); var leaseBefore = lease(task); var failing = spy(repository);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("PRIVATE_QUEUE_CANARY"))
                .when(failing).finishQueued(any(), anyString(), anyString());
        assertUnavailable(() -> queue(service, failing).cancelWaiting(task));
        assertThat(row(task)).isEqualTo(before); assertThat(lease(task)).isEqualTo(leaseBefore); assertRetained(task);
    }

    @Test void lostCoordinationAfterWritingRollsBackAllSettlement() throws Exception {
        var task = submit(); var before = row(task); var leaseBefore = lease(task); var changed = spy(repository);
        doAnswer(call -> { var result = call.callRealMethod(); coordination.close(); return result; })
                .when(changed).finishQueued(any(), anyString(), anyString());
        assertUnavailable(() -> queue(service, changed).cancelWaiting(task));
        assertThat(row(task)).isEqualTo(before); assertThat(lease(task)).isEqualTo(leaseBefore); assertRetained(task);
    }

    @Test void ambientTransactionOrLostLeaseCannotInspectOrMutateTheQueue() throws Exception {
        var task = submit(); var queue = queue(service, repository); var before = row(task);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertUnavailable(queue::waiting); assertUnavailable(() -> queue.beforeDispatch(task));
        });
        coordination.close();
        assertUnavailable(queue::waiting); assertUnavailable(() -> queue.cancelWaiting(task));
        assertThat(row(task)).isEqualTo(before); assertRetained(task);
    }

    private DependencyPreparationService newService() {
        return new DependencyPreparationService(new ExecutionSettings(config), repository, projects,
                source.readGateway(), source.gateway(), source.environment(), config, transactions);
    }
    private DependencyPreparationQueue queue(DependencyPreparationService current, DependencyPreparationRepository store) {
        return new DependencyPreparationQueue(new ExecutionSettings(config), store, current, source.gateway(), jdbc, transactions, coordination);
    }
    private DependencyPreparationRepository.Task submit() throws Exception {
        var base = source.create(owner); var files = new ArrayList<>(source.snapshot(owner, base.projectId(), base.versionId()).files());
        files.add(new SourceTree.File("frontend/package.json", Files.readAllBytes(Path.of("../templates/full-project-v1/frontend/package.json")), 0100644));
        base = source.accept(owner, base, new ProjectSourcePolicy().validate(files));
        var view = service.submit(owner, base.projectId(), new DependencyPreparationService.Input(base.versionId(), base.revision()), "queue");
        var task = repository.get(view.projectId(), view.id()); tasks.add(task); return task;
    }
    private Map<String, Object> row(DependencyPreparationRepository.Task task) {
        return jdbc.queryForMap("SELECT * FROM dependency_preparations WHERE id=?", task.id());
    }
    private Map<String, Object> lease(DependencyPreparationRepository.Task task) {
        return jdbc.queryForMap("SELECT * FROM source_edit_leases WHERE id=?", task.editLeaseId());
    }
    private void assertRetained(DependencyPreparationRepository.Task task) {
        assertThat(service.handle(task.id())).isNotNull();
        assertThat(lease(task).get("status")).isNotEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE task_id=?", Integer.class, task.id())).isEqualTo(1);
    }
    private void assertReleased(DependencyPreparationRepository.Task task) {
        assertThat(service.handle(task.id())).isNull();
        assertThat(lease(task)).containsEntry("status", "RELEASED").containsEntry("execution_pending", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE task_id=?", Integer.class, task.id())).isZero();
    }
    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(IOException.class).hasMessage("EXECUTION_QUEUE_UNAVAILABLE").hasNoCause();
    }
}
