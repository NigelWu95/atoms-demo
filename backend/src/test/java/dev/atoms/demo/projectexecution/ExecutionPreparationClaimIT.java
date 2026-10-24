package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
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

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionPreparationClaimIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
    SourceAgentFixture source;
    MockEnvironment config;
    DependencyPreparationRepository repository;
    DependencyPreparationService service;
    ExecutionBuildRepository capacity;

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
        repository = new DependencyPreparationRepository(jdbc);
        capacity = new ExecutionBuildRepository(jdbc);
        service = new DependencyPreparationService(new ExecutionSettings(config), repository, projects,
                source.readGateway(), source.gateway(), source.environment(), config, transactions);
    }

    @AfterEach void close() throws Exception {
        if (source == null) return;
        try {
            for (UUID project : source.projectIds()) {
                // These fixtures only claim metadata. They never start or adopt an execution.
                assertThat(jdbc.queryForObject("SELECT count(*) FROM source_edit_leases WHERE project_id=? AND execution_id IS NOT NULL", Integer.class, project)).isZero();
                for (var lease : jdbc.queryForList("SELECT id,task_id FROM source_edit_leases WHERE project_id=? AND status<>'RELEASED'", project))
                    source.gateway().reconcileAbandonedEdit(project, (UUID) lease.get("id"), (UUID) lease.get("task_id"));
                jdbc.update("UPDATE dependency_preparations SET status='CANCELLED',cancel_requested=true,queue_slot=null,worker_slot=null,finished_at=now() WHERE project_id=? AND finished_at IS NULL AND NOT execution_pending", project);
                jdbc.update("UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,queue_slot=null,worker_slot=null,finished_at=now() WHERE project_id=? AND finished_at IS NULL AND NOT execution_pending", project);
            }
        } finally { source.close(); }
    }

    @Test void commitsOneClaimPreservingInputAndRejectsReplayOrMissingCapability() throws Exception {
        var queued = queue();
        assertCode("EXECUTION_PREPARATION_EDIT_UNAVAILABLE", () -> claim().claim(owner, queued.projectId(), queued.id(), null));
        var claimed = claim(queued);
        assertThat(claimed.status()).isEqualTo("PREPARING");
        assertThat(claimed.editLeaseId()).isEqualTo(queued.editLeaseId());
        assertThat(claimed.treeSha256()).isEqualTo(queued.treeSha256());
        assertThat(claimed.policySha256()).isEqualTo(queued.policySha256());
        var state = row(queued);
        assertThat(state).containsEntry("queue_slot", null).containsEntry("worker_slot", 1).containsEntry("execution_pending", false);
        assertThat(temporary.resolve("unused-root")).doesNotExist();
        assertCode("EXECUTION_PREPARATION_NOT_QUEUED", () -> claim(queued));
        assertThat(row(queued)).isEqualTo(state);
    }

    @Test void concurrentClaimsOfOneTaskGrantExactlyOneWorker() throws Exception {
        var task = queue();
        assertThat(race(() -> outcome(() -> claim(task)), () -> outcome(() -> claim(task))))
                .containsExactlyInAnyOrder("PREPARING", "EXECUTION_PREPARATION_NOT_QUEUED");
    }

    @Test void buildAndPreparationRaceForOneSharedWorkerOnTwoConnections() throws Exception {
        var prepared = queue();
        var base = source.create(owner);
        var builds = new ProjectExecutionService(new ExecutionSettings(config), capacity, projects, source.readGateway(), transactions);
        var build = builds.submit(owner, base.projectId(), base.versionId(), "race");
        var barrier = new CyclicBarrier(2);
        var connections = ConcurrentHashMap.<Integer>newKeySet();
        capacity = new ExecutionBuildRepository(jdbc) {
            @Override void lockCapacity() {
                connections.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                try { barrier.await(3, TimeUnit.SECONDS); } catch (Exception error) { throw new AssertionError(error); }
                super.lockCapacity();
            }
        };
        var buildClaim = new ExecutionBuildClaim(new ExecutionSettings(config), projects, capacity, jdbc, transactions);
        assertThat(race(() -> outcome(() -> claim(prepared)), () -> outcome(() -> buildClaim.claim(owner, base.projectId(), build.id()))))
                .containsExactlyInAnyOrder("PREPARING", "EXECUTION_WORKER_FULL");
        assertThat(connections).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE project_id IN (?,?) AND worker_slot IS NOT NULL", Integer.class,
                prepared.projectId(), base.projectId())).isEqualTo(1);
    }

    @Test void originalCapacityAndCurrentReducedCapacityBothApply() throws Exception {
        var a = queue(); var b = queue();
        config.withProperty("atoms.project-execution.max-concurrent-builds", "4");
        claim(a);
        assertCode("EXECUTION_WORKER_FULL", () -> claim(b));
        var c = queue(); var d = queue();
        claim(c);
        source.gateway().releaseEdit(service.handle(a.id()));
        jdbc.update("UPDATE dependency_preparations SET status='CANCELLED',queue_slot=null,worker_slot=null,finished_at=now() WHERE id=?", a.id());
        jdbc.update("UPDATE dependency_preparations SET status='NEEDS_RECONCILIATION' WHERE id=?", c.id());
        assertThat(row(c)).containsEntry("worker_slot", 2);
        config.withProperty("atoms.project-execution.max-concurrent-builds", "1");
        assertCode("EXECUTION_WORKER_FULL", () -> claim(d));
        assertThat(row(d)).containsEntry("status", "QUEUED");
    }

    @Test void cancelledReleasedOrForeignEditCannotGrantAnExecution() throws Exception {
        var a = queue(); var b = queue();
        assertCode("EXECUTION_PREPARATION_EDIT_UNAVAILABLE", () -> claim().claim(owner, a.projectId(), a.id(), service.handle(b.id())));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> assertThatThrownBy(() -> claim(a)).isInstanceOf(IllegalStateException.class));
        jdbc.update("UPDATE dependency_preparations SET cancel_requested=true WHERE id=?", a.id());
        assertCode("EXECUTION_CANCELLED", () -> claim(a));
        source.gateway().releaseEdit(service.handle(b.id()));
        assertCode("SOURCE_EDIT_UNAVAILABLE", () -> claim(b));
    }

    @Test void permissionArchiveAndSourceRecoveryFenceLeaveTaskQueued() throws Exception {
        var task = queue(); var initial = row(task);
        config.withProperty("atoms.project-execution.enabled", "false");
        assertCode("EXECUTION_DISABLED", () -> claim(task));
        config.withProperty("atoms.project-execution.enabled", "true");
        assertCode("EXECUTION_FORBIDDEN", () -> claim().claim(stranger, task.projectId(), task.id(), service.handle(task.id())));
        projects.archive(owner, task.projectId().toString(), true);
        assertCode("EXECUTION_PROJECT_ARCHIVED", () -> claim(task));
        projects.archive(owner, task.projectId().toString(), false);
        jdbc.update("UPDATE project_sources SET status='NEEDS_RECONCILIATION' WHERE project_id=?", task.projectId());
        try { assertCode("SOURCE_NEEDS_RECONCILIATION", () -> claim(task)); }
        finally { jdbc.update("UPDATE project_sources SET status='READY' WHERE project_id=?", task.projectId()); }
        config.withProperty("atoms.project-execution.maintainer-ids", owner + "," + stranger);
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, task.projectId());
        assertCode("PROJECT_NOT_FOUND", () -> claim(task));
        assertCode("EXECUTION_PREPARATION_OWNER_CHANGED", () -> claim().claim(stranger, task.projectId(), task.id(), service.handle(task.id())));
        assertThat(row(task)).isEqualTo(initial);
    }

    @Test void queueExpiryAndConfigurationRevocationDuringLockWaitRollBackClaim() throws Exception {
        config.withProperty("atoms.project-execution.queue-timeout-seconds", "1");
        var task = queue(); var before = row(task);
        capacity = new ExecutionBuildRepository(jdbc) {
            @Override void lockCapacity() { super.lockCapacity(); jdbc.queryForObject("SELECT 1 FROM pg_catalog.pg_sleep(1.1)", Integer.class); }
        };
        assertCode("EXECUTION_QUEUE_TIMEOUT", () -> claim(task));
        assertThat(row(task)).isEqualTo(before);
        config.withProperty("atoms.project-execution.queue-timeout-seconds", "300");
        var revoked = queue();
        capacity = new ExecutionBuildRepository(jdbc) {
            @Override void lockCapacity() { super.lockCapacity(); config.withProperty("atoms.project-execution.enabled", "false"); }
        };
        assertCode("EXECUTION_DISABLED", () -> claim(revoked));
        assertThat(row(revoked)).containsEntry("status", "QUEUED").containsEntry("worker_slot", null);
    }

    @Test void databaseErrorAfterClaimRollsBackBothTaskAndSharedSlot() throws Exception {
        var task = queue(); var before = row(task);
        repository = new DependencyPreparationRepository(jdbc) {
            @Override Task get(UUID project, UUID id) {
                var result = super.get(project, id);
                if (result.status().equals("PREPARING")) throw new org.springframework.dao.DataAccessResourceFailureException("PRIVATE_DB_CANARY");
                return result;
            }
        };
        assertThatThrownBy(() -> claim(task)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo("EXECUTION_STATE_UNAVAILABLE");
            assertThat(error.getMessage()).doesNotContain("CANARY"); assertThat(error.getCause()).isNull();
        });
        assertThat(row(task)).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT queue_slot,worker_slot FROM execution_task_slots WHERE task_id=?", task.id()))
                .containsEntry("queue_slot", before.get("queue_slot")).containsEntry("worker_slot", null);
    }

    private DependencyPreparationRepository.Task queue() throws Exception {
        var base = source.create(owner);
        var files = new ArrayList<>(source.snapshot(owner, base.projectId(), base.versionId()).files());
        files.add(new SourceTree.File("frontend/package.json", Files.readAllBytes(Path.of("../templates/full-project-v1/frontend/package.json")), 0100644));
        base = source.accept(owner, base, new ProjectSourcePolicy().validate(files));
        var task = service.submit(owner, base.projectId(), new DependencyPreparationService.Input(base.versionId(), base.revision()), "claim");
        return repository.get(task.projectId(), task.id());
    }
    private DependencyPreparationClaim claim() {
        return new DependencyPreparationClaim(new ExecutionSettings(config), projects, repository, capacity, source.gateway(), jdbc, transactions);
    }
    private DependencyPreparationRepository.Task claim(DependencyPreparationRepository.Task task) {
        return claim().claim(owner, task.projectId(), task.id(), service.handle(task.id()));
    }
    private Map<String, Object> row(DependencyPreparationRepository.Task task) {
        return jdbc.queryForMap("SELECT * FROM dependency_preparations WHERE id=?", task.id());
    }
    private String outcome(Callable<?> action) throws Exception {
        try { action.call(); return "PREPARING"; } catch (ApiException error) { return error.code(); }
    }
    private List<String> race(Callable<String> a, Callable<String> b) throws Exception {
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return a.call(); });
            var second = pool.submit(() -> { start.await(); return b.call(); });
            start.countDown(); return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }
    private void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
