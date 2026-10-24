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
import static org.mockito.Mockito.*;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionPreparationAdmissionIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
    SourceAgentFixture source;
    MockEnvironment config;
    DependencyPreparationRepository repository;
    DependencyPreparationService service;
    Path policy;

    @BeforeEach void fixture() throws Exception {
        source = new SourceAgentFixture(jdbc, projects, transactions, temporary.toRealPath());
        jdbc.update("INSERT INTO guest_users(id) VALUES (?),(?)", owner, stranger);
        config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        for (String key : List.of("root", "node", "npm", "java", "maven"))
            config.withProperty("atoms.project-execution." + key, temporary.toRealPath().resolve("unused-" + key).toString());
        policy = temporary.toRealPath().resolve("policy.json");
        Files.copy(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json"), policy);
        Files.setPosixFilePermissions(policy, PosixFilePermissions.fromString("rw-------"));
        config.withProperty("atoms.project-execution.policy", policy.toString());
        repository = new DependencyPreparationRepository(jdbc);
        service = service(source.readGateway());
    }

    private DependencyPreparationService service(ProjectSourceReadGateway read) {
        return new DependencyPreparationService(new ExecutionSettings(config), repository, projects,
                read, source.gateway(), source.environment(), config, transactions);
    }
    private SourceAgentFixture.Base base() throws Exception {
        var initial = source.create(owner);
        var files = new ArrayList<>(source.snapshot(owner, initial.projectId(), initial.versionId()).files());
        files.add(new SourceTree.File("frontend/package.json", Files.readAllBytes(Path.of("../templates/full-project-v1/frontend/package.json")), 0100644));
        return source.accept(owner, initial, new ProjectSourcePolicy().validate(files));
    }
    private DependencyPreparationService.Input input(SourceAgentFixture.Base base) {
        return new DependencyPreparationService.Input(base.versionId(), base.revision());
    }
    private ProjectExecutionService builds() {
        return new ProjectExecutionService(new ExecutionSettings(config), new ExecutionBuildRepository(jdbc), projects, source.readGateway(), transactions);
    }

    @AfterEach void close() throws Exception {
        if (source == null) return;
        for (UUID project : source.projectIds()) {
            // Only this fixture's never-executed intents; no unknown process or historical lease is settled.
            for (var lease : jdbc.queryForList("SELECT id,task_id FROM source_edit_leases WHERE project_id=? AND status<>'RELEASED' AND execution_id IS NULL", project))
                source.gateway().reconcileAbandonedEdit(project, (UUID) lease.get("id"), (UUID) lease.get("task_id"));
            jdbc.update("UPDATE dependency_preparations SET status='CANCELLED',cancel_requested=true,queue_slot=null,worker_slot=null,finished_at=now() WHERE project_id=? AND finished_at IS NULL AND NOT execution_pending", project);
            jdbc.update("UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,queue_slot=null,worker_slot=null,finished_at=now() WHERE project_id=? AND finished_at IS NULL AND NOT execution_pending", project);
        }
        source.close();
    }

    @Test void persistsApprovalAndLeaseAndReplaysWithoutReadingSourceOrPolicyAgain() throws Exception {
        var base = base();
        var read = spy(source.readGateway());
        var guarded = service(read);
        var task = guarded.submit(owner, base.projectId(), input(base), "retry");
        assertThat(task.status()).isEqualTo("QUEUED");
        assertThat(task.baseVersionId()).isEqualTo(base.versionId());
        assertThat(task.baseRevision()).isEqualTo(base.revision());
        assertThat(task.manifestSha256()).matches("[a-f0-9]{64}");
        assertThat(task.policySha256()).matches("[a-f0-9]{64}");
        assertThat(task.baseLockSha256()).isNull();
        assertThat(jdbc.queryForMap("SELECT task_id,base_version_id,base_revision,status FROM source_edit_leases WHERE id=?", task.editLeaseId()))
                .containsEntry("task_id", task.id()).containsEntry("base_version_id", base.versionId())
                .containsEntry("base_revision", base.revision()).containsEntry("status", "ACTIVE");
        clearInvocations(read);
        Files.writeString(policy, "invalid now");
        assertThat(guarded.submit(owner, base.projectId(), input(base), "retry")).isEqualTo(task);
        verifyNoInteractions(read);
        assertCode("EXECUTION_IDEMPOTENCY_CONFLICT", () -> guarded.submit(owner, base.projectId(),
                new DependencyPreparationService.Input(base.versionId(), base.revision() + 1), "retry"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dependency_preparations WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(1);
        assertThat(temporary.resolve("unused-root")).doesNotExist();
    }

    @Test void conflictsWithBuildAndEditorInBothDirectionsWithoutLeakingAnEdit() throws Exception {
        var a = base(); var b = base(); var c = base();
        var preparation = service.submit(owner, a.projectId(), input(a), "prepare");
        assertCode("EXECUTION_PROJECT_BUSY", () -> builds().submit(owner, a.projectId(), a.versionId(), "build"));
        assertCode("SOURCE_EDIT_BUSY", () -> source.gateway().claimEdit(owner, a.projectId(), a.versionId(), a.revision(), UUID.randomUUID()));
        builds().submit(owner, b.projectId(), b.versionId(), "build");
        assertCode("EXECUTION_PROJECT_BUSY", () -> service.submit(owner, b.projectId(), input(b), "prepare"));
        var edit = source.gateway().claimEdit(owner, c.projectId(), c.versionId(), c.revision(), UUID.randomUUID());
        try { assertCode("SOURCE_EDIT_BUSY", () -> service.submit(owner, c.projectId(), input(c), "prepare")); }
        finally { source.gateway().releaseEdit(edit); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dependency_preparations WHERE owner_id=?", Integer.class, owner)).isEqualTo(1);
        assertThat(service.get(owner, a.projectId(), preparation.id())).isEqualTo(preparation);
    }

    @Test void concurrentRetriesCreateExactlyOneTaskAndOneEditLease() throws Exception {
        var base = base();
        var barrier = new CyclicBarrier(2);
        var read = spy(source.readGateway());
        doAnswer(call -> { var snapshot = call.callRealMethod(); barrier.await(10, TimeUnit.SECONDS); return snapshot; })
                .when(read).read(eq(owner), eq(base.projectId()), eq(base.versionId()), any(SourceExecutionBudget.class));
        var firstService = service(read); var secondService = service(read);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var first = threads.submit(() -> firstService.submit(owner, base.projectId(), input(base), "concurrent"));
            var second = threads.submit(() -> secondService.submit(owner, base.projectId(), input(base), "concurrent"));
            var task = first.get(30, TimeUnit.SECONDS);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(task);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_edit_leases WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dependency_preparations WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(1);
            assertThat((firstService.handle(task.id()) != null) ^ (secondService.handle(task.id()) != null)).isTrue();
        } finally { threads.shutdownNow(); assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void buildAndPreparationCompeteForLastQueueSlotOnTwoRealConnections() throws Exception {
        config.withProperty("atoms.project-execution.queue-capacity", "1");
        var a = base(); var b = base();
        var connections = ConcurrentHashMap.<Integer>newKeySet();
        var barrier = new CyclicBarrier(2);
        Runnable rendezvous = () -> {
            connections.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            try { barrier.await(3, TimeUnit.SECONDS); } catch (Exception error) { throw new AssertionError(error); }
        };
        repository = new DependencyPreparationRepository(jdbc) {
            @Override Task insert(UUID id, UUID account, UUID project, UUID edit, Request request, String tree,
                                  ExecutionNpmPolicy.Preparation approved, ExecutionSettings.Configuration settings) {
                rendezvous.run(); return super.insert(id, account, project, edit, request, tree, approved, settings);
            }
        };
        var builds = new ExecutionBuildRepository(jdbc) {
            @Override ExecutionBuild insert(UUID account, UUID project, ExecutionBuild.Request request, String tree, ExecutionSettings.Configuration settings) {
                rendezvous.run(); return super.insert(account, project, request, tree, settings);
            }
        };
        var preparation = service(source.readGateway());
        var build = new ProjectExecutionService(new ExecutionSettings(config), builds, projects, source.readGateway(), transactions);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var first = threads.submit(() -> outcome(() -> preparation.submit(owner, a.projectId(), input(a), "last-slot")));
            var second = threads.submit(() -> outcome(() -> build.submit(owner, b.projectId(), b.versionId(), "last-slot")));
            String prepared = first.get(30, TimeUnit.SECONDS);
            assertThat(List.of(prepared, second.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder("QUEUED", "EXECUTION_QUEUE_FULL");
            assertThat(connections).hasSize(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_edit_leases WHERE project_id=? AND status<>'RELEASED'", Integer.class, a.projectId()))
                    .isEqualTo(prepared.equals("QUEUED") ? 1 : 0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE project_id IN (?,?)", Integer.class, a.projectId(), b.projectId())).isEqualTo(1);
        } finally { threads.shutdownNow(); assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void rollbackAfterInsertionRemovesIntentSlotLeaseAndInMemoryCapability() throws Exception {
        var base = base();
        var captured = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        repository = new DependencyPreparationRepository(jdbc) {
            @Override Task insert(UUID id, UUID account, UUID project, UUID edit, Request request, String tree,
                                  ExecutionNpmPolicy.Preparation approved, ExecutionSettings.Configuration settings) {
                var task = super.insert(id, account, project, edit, request, tree, approved, settings);
                captured.set(id);
                if (fail.get()) throw new org.springframework.dao.DataIntegrityViolationException("injected after insertion");
                return task;
            }
        };
        var guarded = service(source.readGateway());
        assertCode("EXECUTION_STATE_UNAVAILABLE", () -> guarded.submit(owner, base.projectId(), input(base), "rollback"));
        UUID rolledBack = captured.get();
        assertThat(guarded.handle(rolledBack)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_edit_leases WHERE project_id=?", Integer.class, base.projectId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dependency_preparations WHERE project_id=?", Integer.class, base.projectId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE project_id=?", Integer.class, base.projectId())).isZero();
        fail.set(false);
        var accepted = guarded.submit(owner, base.projectId(), input(base), "rollback");
        assertThat(accepted.id()).isNotEqualTo(rolledBack);
        assertThat(guarded.handle(accepted.id())).isNotNull();
    }

    @Test void sourceRevisionAndRecoveryFenceAreRecheckedAfterSnapshotRead() throws Exception {
        for (boolean fence : List.of(false, true)) {
            var base = base();
            var read = spy(source.readGateway());
            doAnswer(call -> {
                var snapshot = call.callRealMethod();
                if (fence) jdbc.update("UPDATE project_sources SET status='NEEDS_RECONCILIATION' WHERE project_id=?", base.projectId());
                else source.accept(owner, base, source.snapshot(owner, base.projectId(), base.versionId()));
                return snapshot;
            }).when(read).read(eq(owner), eq(base.projectId()), eq(base.versionId()), any(SourceExecutionBudget.class));
            try {
                assertCode(fence ? "SOURCE_NEEDS_RECONCILIATION" : "SOURCE_BASE_CONFLICT",
                        () -> service(read).submit(owner, base.projectId(), input(base), "changed-base"));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM source_edit_leases WHERE project_id=?", Integer.class, base.projectId())).isZero();
            } finally {
                // Undo only this test's artificial flag; no operation or process ever caused this fence.
                if (fence) jdbc.update("UPDATE project_sources SET status='READY' WHERE project_id=?", base.projectId());
            }
        }
    }

    @Test void rejectsRevokedOwnershipArchiveEnablementAndConfigurationAfterRead() throws Exception {
        for (String expected : List.of("PROJECT_NOT_FOUND", "EXECUTION_PROJECT_ARCHIVED", "EXECUTION_DISABLED", "EXECUTION_CONFIGURATION_INVALID")) {
            var base = base();
            var read = spy(source.readGateway());
            doAnswer(call -> {
                var snapshot = call.callRealMethod();
                switch (expected) {
                    case "PROJECT_NOT_FOUND" -> jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, base.projectId());
                    case "EXECUTION_PROJECT_ARCHIVED" -> projects.archive(owner, base.projectId().toString(), true);
                    case "EXECUTION_DISABLED" -> config.withProperty("atoms.project-execution.enabled", "false");
                    default -> config.withProperty("atoms.project-execution.queue-capacity", "7");
                }
                return snapshot;
            }).when(read).read(eq(owner), eq(base.projectId()), eq(base.versionId()), any(SourceExecutionBudget.class));
            assertCode(expected, () -> service(read).submit(owner, base.projectId(), input(base), "revoked"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_edit_leases WHERE project_id=?", Integer.class, base.projectId())).isZero();
            config.withProperty("atoms.project-execution.enabled", "true").withProperty("atoms.project-execution.queue-capacity", "8");
        }
    }

    @Test void rejectedApprovalInvalidInputAndAmbientTransactionDoNotClaimAnEdit() throws Exception {
        var base = base();
        assertCode("EXECUTION_FORBIDDEN", () -> service.submit(stranger, base.projectId(), input(base), "foreign"));
        assertCode("EXECUTION_INVALID_INPUT", () -> service.submit(owner, base.projectId(), new DependencyPreparationService.Input(base.versionId(), 0), "bad-revision"));
        new TransactionTemplate(transactions).executeWithoutResult(status -> assertThatThrownBy(
                () -> service.submit(owner, base.projectId(), input(base), "ambient")).isInstanceOf(IllegalStateException.class));
        Files.writeString(policy, "unapproved");
        assertCode("EXECUTION_DEPENDENCY_POLICY_INVALID", () -> service.submit(owner, base.projectId(), input(base), "policy"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_edit_leases WHERE project_id=?", Integer.class, base.projectId())).isZero();
    }

    @Test void sharedWorkerLimitAndLoweredQueueLimitIncludePreparations() throws Exception {
        var a = base(); var b = base(); var c = base();
        var first = builds().submit(owner, b.projectId(), b.versionId(), "first");
        var prepared = service.submit(owner, a.projectId(), input(a), "second");
        builds().cancel(owner, b.projectId(), first.id());
        assertThat(jdbc.queryForObject("SELECT queue_slot FROM execution_task_slots WHERE task_id=?", Integer.class, prepared.id())).isEqualTo(2);
        config.withProperty("atoms.project-execution.queue-capacity", "1");
        assertCode("EXECUTION_QUEUE_FULL", () -> builds().submit(owner, c.projectId(), c.versionId(), "third"));
        assertCode("EXECUTION_QUEUE_FULL", () -> service.submit(owner, c.projectId(), input(c), "third"));
        jdbc.update("UPDATE dependency_preparations SET status='PREPARING',queue_slot=null,worker_slot=1 WHERE id=?", prepared.id());
        var waiting = builds().submit(owner, c.projectId(), c.versionId(), "third");
        var claim = new ExecutionBuildClaim(new ExecutionSettings(config), projects, new ExecutionBuildRepository(jdbc), jdbc, transactions);
        assertCode("EXECUTION_WORKER_FULL", () -> claim.claim(owner, c.projectId(), waiting.id()));
        // Cross-kind uniqueness also holds for a writer bypassing the Java admission check.
        assertThatThrownBy(() -> jdbc.update("UPDATE execution_builds SET status='PREPARING',queue_slot=null,worker_slot=1 WHERE id=?", waiting.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(builds().get(owner, c.projectId(), waiting.id()).status()).isEqualTo("QUEUED");
    }

    @Test void databaseRetainsAuditAndImmutableBindingsAndUnreleasedLeaseBlocksSettlement() throws Exception {
        var base = base();
        var task = service.submit(owner, base.projectId(), input(base), "immutable");
        for (String mutation : List.of("manifest_sha256=repeat('a',64)", "policy_sha256=repeat('a',64)",
                "base_revision=base_revision+1", "edit_lease_id=gen_random_uuid()"))
            assertThatThrownBy(() -> jdbc.update("UPDATE dependency_preparations SET " + mutation + " WHERE id=?", task.id()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM dependency_preparations WHERE id=?", task.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE dependency_preparations SET status='CANCELLED',queue_slot=null,finished_at=now() WHERE id=?", task.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.get(owner, base.projectId(), task.id())).isEqualTo(task);
    }

    private String outcome(java.util.concurrent.Callable<?> call) throws Exception {
        try { call.call(); return "QUEUED"; } catch (ApiException error) { return error.code(); }
    }
    private void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
