package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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

/** Real PG/Git and retained JVM stop receipts; restart metadata recovery, not a real npm contract. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionPreparationRecoveryIT {
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
    final AtomicBoolean ready = new AtomicBoolean(true);
    final List<DependencyPreparationRepository.Task> tasks = new ArrayList<>();
    final Map<UUID, OwnedProcess.Stopped> observedStops = new HashMap<>();

    @BeforeEach void fixture() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
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
                if (repository.get(task.projectId(), task.id()).finishedAt() != null) continue;
                // Only a real receipt retained by this fixture can resolve its deliberately lost completion.
                var edit = service.handle(task.id()); var stopped = observedStops.get(task.id());
                if (stopped != null) {
                    source.gateway().confirmExecutionStopped(edit, stopped);
                    jdbc.update("UPDATE dependency_preparations SET execution_pending=false WHERE id=?", task.id());
                }
                assertThat(lease(task)).containsEntry("execution_pending", false);
                source.gateway().releaseSettledEdit(edit);
                jdbc.update("UPDATE dependency_preparations SET status='CANCELLED',cancel_requested=true,queue_slot=null,worker_slot=null,finished_at=now() WHERE id=?", task.id());
            }
        } finally {
            try { if (source != null) source.close(); }
            finally { if (coordination != null) coordination.close(); }
        }
    }

    @Test void freshQueueKeepsItsCapabilityButLostQueueIsNeverReexecuted() throws Exception {
        var task = queue(); var before = row(task); var leaseBefore = lease(task);
        assertThat(repository.unfinished()).contains(task);
        assertThat(recovery(service, jdbc).reconcile(task)).isEqualTo(task);
        assertThat(source.gateway().inspectAbandonedEdit(task.projectId(), task.editLeaseId(), task.id()).uncertain()).isFalse();
        assertThat(row(task)).isEqualTo(before); assertThat(lease(task)).isEqualTo(leaseBefore);
        assertThatThrownBy(() -> source.gateway().inspectAbandonedEdit(task.projectId(), task.editLeaseId(), UUID.randomUUID()))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class);
        var restarted = newService();
        var result = recovery(restarted, jdbc).reconcile(task);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("EXECUTION_INTERRUPTED");
        assertThat(restarted.handle(task.id())).isNull();
        assertReleased(task); assertBaseline(task);
        assertThat(temporary.resolve("unused-root")).doesNotExist();
        assertThat(recovery(restarted, jdbc).reconcile(task)).isEqualTo(result);
    }

    @Test void queuedCancellationWinsOverItsOriginalDatabaseDeadline() throws Exception {
        config.withProperty("atoms.project-execution.queue-timeout-seconds", "1");
        var expired = queue(); var cancelled = queue(); var restarted = newService();
        assertThat(restarted.cancel(owner, cancelled.projectId(), cancelled.id()).status()).isEqualTo("QUEUED");
        Thread.sleep(1200);
        var recovery = recovery(restarted, jdbc);
        assertThat(recovery.reconcile(expired).status()).isEqualTo("TIMED_OUT");
        assertThat(repository.get(expired.projectId(), expired.id()).errorCode()).isEqualTo("EXECUTION_QUEUE_TIMEOUT");
        assertThat(recovery.reconcile(cancelled).status()).isEqualTo("CANCELLED");
        assertReleased(expired); assertReleased(cancelled);
    }

    @Test void neverStartedClaimIsInterruptedAfterOwnerChangeWithoutReopeningTheWorkspace() throws Exception {
        var task = claimed();
        jdbc.update("UPDATE projects SET owner_id=?,archived=true WHERE id=?", stranger, task.projectId());
        var result = recovery(service, jdbc).reconcile(task);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("EXECUTION_INTERRUPTED");
        assertThat(service.handle(task.id())).isNull();
        assertReleased(task); assertBaseline(task);
    }

    @Test void lostStopCompletionRetainsBothLeaseAndWorkerDespiteCancellation() throws Exception {
        var task = claimed(); completeExecution(task, false);
        service.cancel(owner, task.projectId(), task.id());
        var result = recovery(service, jdbc).reconcile(task);
        assertThat(result.status()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(result.executionPending()).isTrue();
        assertThat(lease(task)).containsEntry("status", "ACTIVE").containsEntry("execution_pending", true);
        assertRetained(task); assertBaseline(task);
        var before = row(task); var leaseBefore = lease(task);
        recovery(service, jdbc).reconcile(task);
        assertThat(row(task)).isEqualTo(before); assertThat(lease(task)).isEqualTo(leaseBefore);
    }

    @Test void earlierUnknownOutcomeWithClearedPendingIsNotReleasedByRecovery() throws Exception {
        var task = claimed(); completeExecution(task, true);
        assertThat(flow(task).settle("EXECUTION_SOURCE_UNSETTLED").status()).isEqualTo("NEEDS_RECONCILIATION");
        var before = row(task); var leaseBefore = lease(task);
        recovery(service, jdbc).reconcile(task);
        assertThat(row(task)).isEqualTo(before); assertThat(lease(task)).isEqualTo(leaseBefore);
        assertRetained(task);
    }

    @Test void stoppedButUnrecordedNoChangeDoesNotInventASuccess() throws Exception {
        var task = claimed(); completeExecution(task, true); flow(task).accepting();
        assertThat(source.gateway().sealAndAccept(service.handle(task.id()), "no-change", candidate -> {}))
                .isInstanceOf(ProjectSourceEditGateway.NoChanges.class);
        var result = recovery(newService(), jdbc).reconcile(task);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("EXECUTION_INTERRUPTED");
        assertThat(result.resultVersionId()).isNull(); assertThat(result.sourceOperationId()).isNull();
        assertReleased(task); assertBaseline(task);
    }

    @Test void committedSourceWithoutTaskBindingIsRecoveredOnceEvenAfterOwnershipChanged() throws Exception {
        source.start(Objects.requireNonNull(jdbc.getDataSource()));
        var task = claimed(); var accepted = publish(task);
        var committed = accepted.completion().get(30, TimeUnit.SECONDS);
        assertThat(repository.get(task.projectId(), task.id()).sourceOperationId()).isNull();
        jdbc.update("UPDATE projects SET owner_id=?,archived=true WHERE id=?", stranger, task.projectId());
        jdbc.update("UPDATE dependency_preparations SET cancel_requested=true WHERE id=?", task.id());
        var restarted = newService(); var recovery = recovery(restarted, jdbc); var result = recovery.reconcile(task);
        assertThat(result.status()).isEqualTo("SOURCE_SAVED");
        assertThat(result.sourceOperationId()).isEqualTo(committed.id());
        assertThat(result.resultVersionId()).isEqualTo(committed.resultVersionId());
        assertThat(result.errorCode()).isNull(); assertReleased(task);
        assertThat(recovery.reconcile(task)).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, task.projectId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE edit_lease_id=?", Integer.class, task.editLeaseId())).isEqualTo(1);
        assertThat(restarted.handle(task.id())).isNull();
    }

    @Test void publicationInFlightIsRetainedThenItsActualCommitWins() throws Exception {
        var reached = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        source.start(Objects.requireNonNull(jdbc.getDataSource()), stage -> {
            if (stage.equals("BEFORE_PUBLISH")) {
                reached.countDown();
                try { assertThat(proceed.await(30, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }
        });
        var task = claimed(); var accepted = publish(task); var recovery = recovery(service, jdbc);
        try {
            assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(recovery.reconcile(task).status()).isEqualTo("NEEDS_RECONCILIATION");
            assertRetained(task);
        } finally { proceed.countDown(); }
        var outcome = accepted.completion().get(30, TimeUnit.SECONDS);
        assertThat(outcome.status()).isEqualTo("COMMITTED");
        assertThat(recovery.reconcile(task).resultVersionId()).isEqualTo(outcome.resultVersionId());
        assertReleased(task);
    }

    @Test void unavailableAuthorityAndAmbientTransactionsDoNotMutateEvidence() throws Exception {
        var task = claimed(); var before = row(task); var leaseBefore = lease(task); var recovery = recovery(service, jdbc);
        ready.set(false); assertUnavailable(() -> recovery.reconcile(task)); ready.set(true);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> assertUnavailable(() -> recovery.reconcile(task)));
        coordination.close(); assertUnavailable(() -> recovery.reconcile(task));
        assertThat(row(task)).isEqualTo(before); assertThat(lease(task)).isEqualTo(leaseBefore);
    }

    @Test void failedTaskWriteRollsBackSourceReleaseAndKeepsOriginalCapability() throws Exception {
        var task = claimed(); var before = row(task); var leaseBefore = lease(task); var failing = spy(jdbc);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("PRIVATE_RECOVERY_CANARY"))
                .when(failing).update(startsWith("UPDATE dependency_preparations SET status=?"), any(Object[].class));
        assertUnavailable(() -> recovery(service, failing).reconcile(task));
        assertThat(row(task)).isEqualTo(before); assertThat(lease(task)).isEqualTo(leaseBefore);
        assertRetained(task);
    }

    @Test void authorityLossAfterWritingRollsBackTheWholeRecovery() throws Exception {
        var task = claimed(); var before = row(task); var leaseBefore = lease(task);
        var changed = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public int update(String sql, Object... args) {
                int result = super.update(sql, args);
                if (sql.startsWith("UPDATE dependency_preparations SET status=?")) ready.set(false);
                return result;
            }
        };
        assertUnavailable(() -> recovery(service, changed).reconcile(task)); ready.set(true);
        assertThat(row(task)).isEqualTo(before); assertThat(lease(task)).isEqualTo(leaseBefore);
        assertRetained(task);
    }

    private DependencyPreparationService newService() {
        return new DependencyPreparationService(new ExecutionSettings(config), repository, projects,
                source.readGateway(), source.gateway(), source.environment(), config, transactions);
    }
    private DependencyPreparationRecovery recovery(DependencyPreparationService current, JdbcTemplate store) {
        return new DependencyPreparationRecovery(new ExecutionSettings(config), repository, current, source.gateway(), store,
                transactions, coordination, ready::get);
    }
    private DependencyPreparationRepository.Task queue() throws Exception {
        var base = source.create(owner); var files = new ArrayList<>(source.snapshot(owner, base.projectId(), base.versionId()).files());
        files.add(new SourceTree.File("frontend/package.json", Files.readAllBytes(Path.of("../templates/full-project-v1/frontend/package.json")), 0100644));
        base = source.accept(owner, base, new ProjectSourcePolicy().validate(files));
        var view = service.submit(owner, base.projectId(), new DependencyPreparationService.Input(base.versionId(), base.revision()), "recover");
        var task = repository.get(view.projectId(), view.id()); tasks.add(task); return task;
    }
    private DependencyPreparationRepository.Task claimed() throws Exception {
        var task = queue();
        return new DependencyPreparationClaim(new ExecutionSettings(config), projects, repository, new ExecutionBuildRepository(jdbc),
                source.gateway(), jdbc, transactions).claim(owner, task.projectId(), task.id(), service.handle(task.id()));
    }
    private DependencyPreparationLifecycle flow(DependencyPreparationRepository.Task task) {
        return new DependencyPreparationLifecycle(new ExecutionSettings(config), projects, jdbc, transactions, repository,
                service, source.gateway(), task, service.handle(task.id()));
    }
    private void completeExecution(DependencyPreparationRepository.Task task, boolean recordStop) throws Exception {
        var edit = service.handle(task.id()); source.gateway().prepareEdit(edit);
        Path work = source.gateway().workPath(edit); UUID execution = UUID.randomUUID(); flow(task).begin(execution);
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-version").directory(work.toFile());
        command.environment().clear();
        try {
            var stopped = new OwnedProcess().runCommand(command, () -> Duration.ofSeconds(10), () -> false, task.id(), execution,
                    stream -> stream.readNBytes(2048), stream -> { stream.readNBytes(2048); return null; }, () -> {}).stopped();
            observedStops.put(task.id(), stopped);
            if (recordStop) flow(task).stopped(stopped);
        } catch (OwnedProcess.Failure error) {
            if (error.stopped() != null) observedStops.put(task.id(), error.stopped());
            throw error;
        }
    }
    private ProjectSourceEditGateway.Submitted publish(DependencyPreparationRepository.Task task) throws Exception {
        completeExecution(task, true);
        Files.writeString(source.gateway().workPath(service.handle(task.id())).resolve("frontend/package-lock.json"), "recovery fixture");
        flow(task).accepting();
        return (ProjectSourceEditGateway.Submitted) source.gateway().sealAndAccept(service.handle(task.id()), "publication", candidate -> {});
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
        assertThat(lease(task)).containsEntry("status", "RELEASED").containsEntry("execution_pending", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE task_id=?", Integer.class, task.id())).isZero();
    }
    private void assertBaseline(DependencyPreparationRepository.Task task) {
        assertThat(jdbc.queryForObject("SELECT current_source_version_id FROM project_sources WHERE project_id=?", UUID.class, task.projectId())).isEqualTo(task.baseVersionId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, task.projectId())).isEqualTo(2);
    }
    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(IOException.class).hasMessage("EXECUTION_RECOVERY_UNAVAILABLE").hasNoCause();
    }
}
