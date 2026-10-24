package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
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
class ExecutionPreparationLifecycleIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
    SourceAgentFixture source;
    MockEnvironment config;
    DependencyPreparationRepository repository;
    DependencyPreparationService service;
    final Map<UUID, OwnedProcess.Stopped> observedStops = new HashMap<>();
    final List<DependencyPreparationRepository.Task> tasks = new ArrayList<>();

    @BeforeEach void fixture() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo("atoms_demo_test.atoms_verify_20261021_ex10");
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
        repository = new DependencyPreparationRepository(jdbc);
        service = new DependencyPreparationService(new ExecutionSettings(config), repository, projects,
                source.readGateway(), source.gateway(), source.environment(), config, transactions);
    }

    @AfterEach void close() throws Exception {
        if (source == null) return;
        try {
            for (var task : tasks) {
                if (repository.get(task.projectId(), task.id()).finishedAt() != null) continue;
                var edit = service.handle(task.id());
                // Only these fixtures' own real stop receipts may settle an intentionally interrupted journal.
                var stopped = observedStops.get(task.id());
                if (stopped != null) source.gateway().confirmExecutionStopped(edit, stopped);
                assertThat(jdbc.queryForObject("SELECT execution_pending FROM source_edit_leases WHERE id=?", Boolean.class, task.editLeaseId())).isFalse();
                jdbc.update("UPDATE dependency_preparations SET execution_pending=false WHERE id=?", task.id());
                source.gateway().releaseSettledEdit(edit);
                jdbc.update("UPDATE dependency_preparations SET status='CANCELLED',cancel_requested=true,queue_slot=null,worker_slot=null,finished_at=now() WHERE id=?", task.id());
            }
        } finally { source.close(); }
    }

    @Test void oneExecutionNeedsItsStopAndActualNoChangeAcceptanceBeforeCompletion() throws Exception {
        var task = preparing(); var flow = lifecycle(task, jdbc); var edit = service.handle(task.id());
        assertUnavailable(flow::accepting);
        UUID execution = UUID.randomUUID(); flow.begin(execution);
        assertThat(row(task)).containsEntry("status", "RESOLVING").containsEntry("execution_pending", true);
        assertThat(lease(task)).containsEntry("execution_id", execution).containsEntry("execution_pending", true);
        assertUnavailable(() -> flow.begin(UUID.randomUUID()));
        assertUnavailable(flow::accepting);
        var stopped = run(task, execution); flow.stopped(stopped);
        assertThat(row(task)).containsEntry("execution_pending", false);
        assertThat(lease(task)).containsEntry("execution_pending", false);
        assertThat(lease(task).get("stopped_sha256")).isNotNull();
        flow.accepting();
        assertUnavailable(() -> flow.settle(null)); // A stopped process alone is not a no-change result.
        assertThat(source.gateway().sealAndAccept(edit, "unchanged", candidate -> {})).isInstanceOf(ProjectSourceEditGateway.NoChanges.class);
        assertThat(flow.settle(null).status()).isEqualTo("NO_CHANGES");
        assertThat(flow.settle("EXECUTION_TIMEOUT").status()).isEqualTo("NO_CHANGES");
        assertReleased(task);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, task.projectId())).isEqualTo(2);
    }

    @Test void committedSourceWinsOverCancellationTimeoutAndOwnerChange() throws Exception {
        source.start(Objects.requireNonNull(jdbc.getDataSource()));
        var task = preparing(); var flow = lifecycle(task, jdbc); var edit = service.handle(task.id());
        UUID execution = UUID.randomUUID(); flow.begin(execution);
        Files.writeString(source.gateway().workPath(edit).resolve("frontend/package-lock.json"), "fixture publication");
        flow.stopped(run(task, execution)); flow.accepting();
        var accepted = (ProjectSourceEditGateway.Submitted) source.gateway().sealAndAccept(edit, "saved", candidate -> {});
        flow.bindSource();
        var result = accepted.completion().get(30, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo("COMMITTED");
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, task.projectId());
        jdbc.update("UPDATE dependency_preparations SET cancel_requested=true WHERE id=?", task.id());
        config.withProperty("atoms.project-execution.enabled", "false");
        assertThat(flow.settle("EXECUTION_TIMEOUT").status()).isEqualTo("SOURCE_SAVED");
        assertThat(row(task)).containsEntry("source_operation_id", result.id()).containsEntry("result_version_id", result.resultVersionId()).containsEntry("error_code", null);
        assertReleased(task);
        assertThat(jdbc.queryForObject("SELECT current_source_version_id FROM project_sources WHERE project_id=?", UUID.class, task.projectId())).isEqualTo(result.resultVersionId());
    }

    @Test void stoppedFailuresCanReleaseAfterPermissionChangesWithoutPublishing() throws Exception {
        var task = preparing(); var flow = lifecycle(task, jdbc);
        UUID execution = UUID.randomUUID(); flow.begin(execution);
        var stopped = run(task, execution);
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, task.projectId());
        config.withProperty("atoms.project-execution.enabled", "false");
        flow.stopped(stopped);
        assertThat(flow.settle("EXECUTION_NPM_POLICY_REJECTED").status()).isEqualTo("FAILED");
        assertReleased(task);
        assertThat(jdbc.queryForObject("SELECT current_source_version_id FROM project_sources WHERE project_id=?", UUID.class, task.projectId())).isEqualTo(task.baseVersionId());
    }

    @Test void unprovenStopRetainsCapacityAndRejectsAnotherTasksReceipt() throws Exception {
        var task = preparing(); var flow = lifecycle(task, jdbc);
        UUID execution = UUID.randomUUID(); flow.begin(execution);
        var real = run(task, execution);
        assertUnavailable(() -> flow.stopped(stop(UUID.randomUUID(), execution, source.gateway().workPath(service.handle(task.id())))));
        assertThat(row(task)).containsEntry("execution_pending", true);
        flow.stopped(null);
        assertThat(flow.settle("EXECUTION_TIMEOUT").status()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(row(task)).containsEntry("execution_pending", true).containsEntry("worker_slot", 1).containsEntry("finished_at", null);
        assertThatThrownBy(() -> source.gateway().releaseSettledEdit(service.handle(task.id()))).isInstanceOf(dev.atoms.demo.common.ApiException.class);
        flow.stopped(real);
        assertThat(row(task)).containsEntry("execution_pending", false).containsEntry("status", "NEEDS_RECONCILIATION");
        assertThat(flow.settle("EXECUTION_TIMEOUT").status()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(service.handle(task.id())).isNotNull();
    }

    @Test void beginningIntentAndSourceLeaseRollBackTogetherOnWriteFailure() throws Exception {
        var task = preparing(); var failing = spy(jdbc);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("PRIVATE_BEGIN_CANARY"))
                .when(failing).update(eq("UPDATE dependency_preparations SET status='RESOLVING',execution_pending=true WHERE id=?"), eq(task.id()));
        assertUnavailable(() -> lifecycle(task, failing).begin(UUID.randomUUID()));
        assertThat(row(task)).containsEntry("status", "PREPARING").containsEntry("execution_pending", false);
        assertThat(lease(task)).containsEntry("execution_id", null).containsEntry("execution_pending", false);
        assertThat(lifecycle(task, jdbc).settle("EXECUTION_PREFLIGHT_FAILED").status()).isEqualTo("FAILED");
        assertReleased(task);
    }

    @Test void explicitUnknownOutcomeCannotBecomeFailureJustBecausePendingWasCleared() throws Exception {
        var task = preparing(); var flow = lifecycle(task, jdbc);
        UUID execution = UUID.randomUUID(); flow.begin(execution); flow.stopped(run(task, execution));
        assertUnavailable(() -> flow.settle("EXECUTION_STATE_UNAVAILABLE"));
        assertThat(row(task)).containsEntry("status", "RESOLVING").containsEntry("worker_slot", 1);
        assertThat(flow.settle("EXECUTION_PROCESS_UNSETTLED").status()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(row(task)).containsEntry("worker_slot", 1).containsEntry("finished_at", null);
    }

    @Test void sourcePublicationInFlightRetainsCapacityThenSettlesItsActualCommit() throws Exception {
        var reached = new java.util.concurrent.CountDownLatch(1);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        source.start(Objects.requireNonNull(jdbc.getDataSource()), stage -> {
            if (!stage.equals("BEFORE_PUBLISH")) return;
            reached.countDown();
            try { assertThat(proceed.await(15, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        });
        var task = preparing(); var flow = lifecycle(task, jdbc); var edit = service.handle(task.id());
        UUID execution = UUID.randomUUID(); flow.begin(execution);
        Files.writeString(source.gateway().workPath(edit).resolve("frontend/package-lock.json"), "fixture publication");
        flow.stopped(run(task, execution)); flow.accepting();
        var accepted = (ProjectSourceEditGateway.Submitted) source.gateway().sealAndAccept(edit, "pending", candidate -> {});
        try {
            assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> source.gateway().releaseSettledEdit(edit)).isInstanceOf(dev.atoms.demo.common.ApiException.class);
            assertThat(flow.settle("EXECUTION_TIMEOUT").status()).isEqualTo("NEEDS_RECONCILIATION");
            assertThat(row(task)).containsEntry("worker_slot", 1).containsEntry("finished_at", null)
                    .containsEntry("source_operation_id", accepted.operation().id());
        } finally { proceed.countDown(); }
        assertThat(accepted.completion().get(20, TimeUnit.SECONDS).status()).isEqualTo("COMMITTED");
        assertThat(flow.settle(null).status()).isEqualTo("SOURCE_SAVED");
        assertReleased(task);
    }

    @Test void stopReceiptAndPreparationPendingFlagRollBackTogether() throws Exception {
        var task = preparing(); var flow = lifecycle(task, jdbc);
        UUID execution = UUID.randomUUID(); flow.begin(execution);
        var stopped = run(task, execution); var failing = spy(jdbc);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("PRIVATE_STOP_CANARY"))
                .when(failing).update(eq("UPDATE dependency_preparations SET execution_pending=false WHERE id=?"), eq(task.id()));
        assertUnavailable(() -> lifecycle(task, failing).stopped(stopped));
        assertThat(row(task)).containsEntry("execution_pending", true);
        assertThat(lease(task)).containsEntry("execution_pending", true).containsEntry("stopped_sha256", null);
        // Injected failure was known to roll back. The original owner still holds the actual receipt.
        flow.stopped(stopped);
        assertThat(flow.settle("EXECUTION_PROCESS_FAILED").status()).isEqualTo("FAILED");
        assertReleased(task);
    }

    @Test void cancellationAndAmbientTransactionsDoNotStartOrPublish() throws Exception {
        var task = preparing(); var flow = lifecycle(task, jdbc);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> assertUnavailable(() -> flow.begin(UUID.randomUUID())));
        jdbc.update("UPDATE dependency_preparations SET cancel_requested=true WHERE id=?", task.id());
        assertUnavailable(() -> flow.begin(UUID.randomUUID()));
        assertThat(lease(task)).containsEntry("execution_id", null);
        assertThat(flow.settle("EXECUTION_TIMEOUT").status()).isEqualTo("CANCELLED");
        assertReleased(task);
    }

    @Test void failedTerminalWriteKeepsBothCapabilitiesAndSlotUntilKnownRollbackIsResolved() throws Exception {
        var task = preparing();
        var failing = spy(jdbc);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("PRIVATE_SETTLE_CANARY"))
                .when(failing).update(startsWith("UPDATE dependency_preparations SET status=?,error_code=?"), any(Object[].class));
        assertUnavailable(() -> lifecycle(task, failing).settle("EXECUTION_PREFLIGHT_FAILED"));
        assertThat(service.handle(task.id())).isNotNull();
        assertThat(lease(task)).containsEntry("status", "ACTIVE");
        assertThat(row(task)).containsEntry("status", "PREPARING").containsEntry("worker_slot", 1);
        assertThat(lifecycle(task, jdbc).settle("EXECUTION_PREFLIGHT_FAILED").status()).isEqualTo("FAILED");
        assertReleased(task);
    }

    private DependencyPreparationRepository.Task preparing() throws Exception {
        var base = source.create(owner);
        var files = new ArrayList<>(source.snapshot(owner, base.projectId(), base.versionId()).files());
        files.add(new SourceTree.File("frontend/package.json", Files.readAllBytes(Path.of("../templates/full-project-v1/frontend/package.json")), 0100644));
        base = source.accept(owner, base, new ProjectSourcePolicy().validate(files));
        var view = service.submit(owner, base.projectId(), new DependencyPreparationService.Input(base.versionId(), base.revision()), "lifecycle");
        var task = new DependencyPreparationClaim(new ExecutionSettings(config), projects, repository,
                new ExecutionBuildRepository(jdbc), source.gateway(), jdbc, transactions).claim(owner, base.projectId(), view.id(), service.handle(view.id()));
        tasks.add(task);
        source.gateway().prepareEdit(service.handle(task.id()));
        return task;
    }
    private DependencyPreparationLifecycle lifecycle(DependencyPreparationRepository.Task task, JdbcTemplate store) {
        return new DependencyPreparationLifecycle(new ExecutionSettings(config), projects, store, transactions, repository, service,
                source.gateway(), task, service.handle(task.id()));
    }
    private OwnedProcess.Stopped run(DependencyPreparationRepository.Task task, UUID execution) throws Exception {
        var result = stop(task.id(), execution, source.gateway().workPath(service.handle(task.id())));
        observedStops.put(task.id(), result); return result;
    }
    private OwnedProcess.Stopped stop(UUID task, UUID execution, Path work) throws Exception {
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-version").directory(work.toFile());
        command.environment().clear();
        return new OwnedProcess().runCommand(command, () -> Duration.ofSeconds(10), () -> false, task, execution,
                stream -> stream.readNBytes(2048), stream -> { stream.readNBytes(2048); return null; }, () -> {}).stopped();
    }
    private Map<String, Object> row(DependencyPreparationRepository.Task task) {
        return jdbc.queryForMap("SELECT * FROM dependency_preparations WHERE id=?", task.id());
    }
    private Map<String, Object> lease(DependencyPreparationRepository.Task task) {
        return jdbc.queryForMap("SELECT * FROM source_edit_leases WHERE id=?", task.editLeaseId());
    }
    private void assertReleased(DependencyPreparationRepository.Task task) {
        assertThat(service.handle(task.id())).isNull();
        assertThat(lease(task)).containsEntry("status", "RELEASED").containsEntry("execution_pending", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE task_id=?", Integer.class, task.id())).isZero();
    }
    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(IOException.class).hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
    }
}
