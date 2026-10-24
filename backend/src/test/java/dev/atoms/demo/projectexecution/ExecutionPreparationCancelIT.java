package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
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
class ExecutionPreparationCancelIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
    SourceAgentFixture source;
    MockEnvironment config;
    DependencyPreparationRepository repository;
    DependencyPreparationService service;
    final List<DependencyPreparationRepository.Task> tasks = new ArrayList<>();
    final Map<UUID, OwnedProcess.Stopped> observedStops = new HashMap<>();

    @BeforeEach void fixture() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isIn("atoms_demo_test.atoms_verify_20261021_ex10", "atoms_demo_test.atoms_verify_20261024_ex16");
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
        service = service(repository);
    }

    @AfterEach void close() throws Exception {
        if (source == null) return;
        try {
            for (var task : tasks) {
                if (repository.get(task.projectId(), task.id()).finishedAt() != null) continue;
                // Never invent a stop receipt for test cleanup.
                var stopped = observedStops.get(task.id());
                if (stopped != null) {
                    source.gateway().confirmExecutionStopped(service.handle(task.id()), stopped);
                    jdbc.update("UPDATE dependency_preparations SET execution_pending=false WHERE id=?", task.id());
                }
                assertThat(lease(task)).containsEntry("execution_pending", false);
                source.gateway().releaseSettledEdit(service.handle(task.id()));
                jdbc.update("UPDATE dependency_preparations SET status='CANCELLED',cancel_requested=true,queue_slot=null,worker_slot=null,finished_at=now() WHERE id=?", task.id());
            }
        } finally { source.close(); }
    }

    @Test void queuedCancellationAtomicallyReleasesEvenAfterArchiveAndIsReplayable() throws Exception {
        var task = queue();
        jdbc.update("UPDATE projects SET archived=true WHERE id=?", task.projectId());
        var result = service.cancel(owner, task.projectId(), task.id());
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.cancelRequested()).isTrue();
        assertThat(result.executionPending()).isFalse();
        assertThat(result.errorCode()).isEqualTo("EXECUTION_CANCELLED");
        assertThat(result.sourceOperationId()).isNull();
        assertThat(result.resultVersionId()).isNull();
        assertReleased(task);
        assertThat(lease(task)).containsEntry("execution_id", null).containsEntry("cancel_requested", true);
        var before = row(task);
        assertThat(service.cancel(owner, task.projectId(), task.id())).isEqualTo(result);
        assertThat(service.get(owner, task.projectId(), task.id())).isEqualTo(result);
        assertThat(row(task)).isEqualTo(before);
    }

    @Test void ownerMaintainerAndIndependentTransactionAreRequired() throws Exception {
        var task = queue(); var before = row(task);
        assertCode("EXECUTION_FORBIDDEN", () -> service.cancel(stranger, task.projectId(), task.id()));
        assertCode("EXECUTION_INVALID_INPUT", () -> service.cancel(owner, task.projectId(), null));
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                assertThatThrownBy(() -> service.cancel(owner, task.projectId(), task.id())).isInstanceOf(IllegalStateException.class));
        config.withProperty("atoms.project-execution.maintainer-ids", owner + "," + stranger);
        assertThatThrownBy(() -> service.cancel(stranger, task.projectId(), task.id())).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, task.projectId());
        assertCode("EXECUTION_PREPARATION_OWNER_CHANGED", () -> service.cancel(stranger, task.projectId(), task.id()));
        assertThat(row(task)).isEqualTo(before);
        assertThat(lease(task)).containsEntry("cancel_requested", false).containsEntry("status", "ACTIVE");
    }

    @Test void failedTerminalWriteRollsBackIntentSourceReleaseAndCapacity() throws Exception {
        repository = spy(repository); service = service(repository);
        var task = queue(); var before = row(task); var sourceBefore = lease(task);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("PRIVATE_CANCEL_CANARY"))
                .when(repository).cancelQueued(any());
        assertCode("EXECUTION_STATE_UNAVAILABLE", () -> service.cancel(owner, task.projectId(), task.id()));
        assertThat(row(task)).isEqualTo(before);
        assertThat(lease(task)).isEqualTo(sourceBefore);
        assertThat(service.handle(task.id())).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE task_id=?", Integer.class, task.id())).isEqualTo(1);
    }

    @Test void missingInMemoryCapabilityOnlyPersistsIntentAndDoesNotRecreateAnEdit() throws Exception {
        var task = queue(); var restarted = service(repository);
        var result = restarted.cancel(owner, task.projectId(), task.id());
        assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(result.cancelRequested()).isTrue();
        assertThat(restarted.handle(task.id())).isNull();
        assertThat(lease(task)).containsEntry("status", "ACTIVE").containsEntry("cancel_requested", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE task_id=?", Integer.class, task.id())).isEqualTo(1);
        assertThat(service.cancel(owner, task.projectId(), task.id()).status()).isEqualTo("CANCELLED");
        assertReleased(task);
    }

    @Test void claimedCancellationPreservesWorkerAndEditUntilItsStopIsProven() throws Exception {
        var task = queue(); claim(task);
        var edit = service.handle(task.id()); source.gateway().prepareEdit(edit);
        var flow = lifecycle(task); UUID execution = UUID.randomUUID(); flow.begin(execution);
        var stopped = stop(task, execution); // Retain the real receipt before cancellation revokes workspace access.
        var result = service.cancel(owner, task.projectId(), task.id());
        assertThat(result.status()).isEqualTo("RESOLVING");
        assertThat(result.executionPending()).isTrue();
        assertThat(result.cancelRequested()).isTrue();
        assertThat(lease(task)).containsEntry("status", "ACTIVE").containsEntry("execution_pending", true).containsEntry("cancel_requested", true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE edit_lease_id=?", Integer.class, task.editLeaseId())).isZero();
        flow.stopped(stopped);
        assertThat(flow.settle("EXECUTION_TIMEOUT").status()).isEqualTo("CANCELLED");
        assertReleased(task);
    }

    @Test void claimAndCancellationSerializeWithoutReleasingAClaimedWorker() throws Exception {
        var task = queue(); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var claim = pool.submit(() -> {
                start.await();
                try { return claim(task).status(); } catch (ApiException error) { return error.code(); }
            });
            var cancel = pool.submit(() -> { start.await(); return service.cancel(owner, task.projectId(), task.id()); });
            start.countDown(); var claimed = claim.get(15, TimeUnit.SECONDS); var cancelled = cancel.get(15, TimeUnit.SECONDS);
            if (claimed.equals("PREPARING")) {
                assertThat(cancelled.status()).isEqualTo("PREPARING");
                assertThat(lease(task)).containsEntry("status", "ACTIVE");
                assertThat(lifecycle(task).settle("EXECUTION_CANCELLED").status()).isEqualTo("CANCELLED");
            } else {
                assertThat(claimed).isIn("EXECUTION_PREPARATION_EDIT_UNAVAILABLE", "EXECUTION_PREPARATION_NOT_QUEUED");
                assertThat(cancelled.status()).isEqualTo("CANCELLED");
            }
            assertReleased(task);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void alreadyCommittedSourceIsNotRelabelledAsCancelled() throws Exception {
        source.start(Objects.requireNonNull(jdbc.getDataSource()));
        var task = queue(); claim(task); var flow = lifecycle(task); var edit = service.handle(task.id());
        source.gateway().prepareEdit(edit); UUID execution = UUID.randomUUID(); flow.begin(execution);
        Files.writeString(source.gateway().workPath(edit).resolve("frontend/package-lock.json"), "publication fixture");
        flow.stopped(stop(task, execution)); flow.accepting();
        var accepted = (ProjectSourceEditGateway.Submitted) source.gateway().sealAndAccept(edit, "committed", candidate -> {});
        var saved = accepted.completion().get(30, TimeUnit.SECONDS);
        // Simulate the response/binding gap: cancellation finds the original operation through its lease.
        assertThat(service.cancel(owner, task.projectId(), task.id()).cancelRequested()).isFalse();
        assertThat(lease(task)).containsEntry("cancel_requested", false);
        assertThat(flow.settle(null).status()).isEqualTo("SOURCE_SAVED");
        var result = service.get(owner, task.projectId(), task.id());
        assertThat(result.sourceOperationId()).isEqualTo(saved.id());
        assertThat(result.resultVersionId()).isEqualTo(saved.resultVersionId());
        assertThat(service.cancel(owner, task.projectId(), task.id())).isEqualTo(result);
        assertReleased(task);
    }

    private DependencyPreparationService service(DependencyPreparationRepository store) {
        return new DependencyPreparationService(new ExecutionSettings(config), store, projects,
                source.readGateway(), source.gateway(), source.environment(), config, transactions);
    }
    private DependencyPreparationRepository.Task queue() throws Exception {
        var base = source.create(owner);
        var files = new ArrayList<>(source.snapshot(owner, base.projectId(), base.versionId()).files());
        files.add(new SourceTree.File("frontend/package.json", Files.readAllBytes(Path.of("../templates/full-project-v1/frontend/package.json")), 0100644));
        base = source.accept(owner, base, new ProjectSourcePolicy().validate(files));
        var view = service.submit(owner, base.projectId(), new DependencyPreparationService.Input(base.versionId(), base.revision()), "cancel");
        var task = repository.get(view.projectId(), view.id()); tasks.add(task); return task;
    }
    private DependencyPreparationRepository.Task claim(DependencyPreparationRepository.Task task) {
        return new DependencyPreparationClaim(new ExecutionSettings(config), projects, repository,
                new ExecutionBuildRepository(jdbc), source.gateway(), jdbc, transactions).claim(owner, task.projectId(), task.id(), service.handle(task.id()));
    }
    private DependencyPreparationLifecycle lifecycle(DependencyPreparationRepository.Task task) {
        return new DependencyPreparationLifecycle(new ExecutionSettings(config), projects, jdbc, transactions, repository, service,
                source.gateway(), task, service.handle(task.id()));
    }
    private OwnedProcess.Stopped stop(DependencyPreparationRepository.Task task, UUID execution) throws Exception {
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-version")
                .directory(source.gateway().workPath(service.handle(task.id())).toFile());
        command.environment().clear();
        var stopped = new OwnedProcess().runCommand(command, () -> Duration.ofSeconds(10), () -> false, task.id(), execution,
                stream -> stream.readNBytes(2048), stream -> { stream.readNBytes(2048); return null; }, () -> {}).stopped();
        observedStops.put(task.id(), stopped); return stopped;
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
    private void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo(code); assertThat(error.getCause()).isNull();
        });
    }
}
