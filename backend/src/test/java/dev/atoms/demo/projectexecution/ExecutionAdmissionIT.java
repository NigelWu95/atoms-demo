package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.SourceAgentFixture;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false",
        "atoms.generation.worker-enabled=false"})
class ExecutionAdmissionIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
    SourceAgentFixture source;
    MockEnvironment configuration;
    ExecutionBuildRepository repository;
    ProjectExecutionService service;

    @BeforeEach void fixture() throws Exception {
        source = new SourceAgentFixture(jdbc, projects, transactions, temporary.toRealPath());
        jdbc.update("INSERT INTO guest_users(id) VALUES (?),(?)", owner, stranger);
        configuration = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        for (String key : new String[] {"root", "node", "npm", "java", "maven", "policy"})
            configuration.withProperty("atoms.project-execution." + key, temporary.resolve("unused-" + key).toString());
        repository = new ExecutionBuildRepository(jdbc);
        service = service();
    }

    private ProjectExecutionService service() {
        return new ProjectExecutionService(new ExecutionSettings(configuration), repository, projects, source.readGateway(), transactions);
    }

    @AfterEach void close() throws Exception {
        if (source == null) return;
        for (UUID project : source.projectIds()) jdbc.update("""
                UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,queue_slot=null,finished_at=now()
                WHERE project_id=? AND status='QUEUED' AND NOT execution_pending
                """, project);
        source.close();
    }

    @Test void concurrentRetryReturnsOneDurableIntentWithoutStartingTools() throws Exception {
        var base = source.create(owner);
        var start = new CountDownLatch(1);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var first = threads.submit(() -> { start.await(); return service.submit(owner, base.projectId(), base.versionId(), "same-key"); });
            var second = threads.submit(() -> { start.await(); return service().submit(owner, base.projectId(), base.versionId(), "same-key"); });
            start.countDown();
            var build = first.get(30, TimeUnit.SECONDS);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(build);
            assertThat(build.status()).isEqualTo("QUEUED");
            assertThat(build.sourceVersionId()).isEqualTo(base.versionId());
            assertThat(service().get(owner, base.projectId(), build.id())).isEqualTo(build);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_builds WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_build_events WHERE build_id=?", Integer.class, build.id())).isEqualTo(1);
            assertCode("EXECUTION_IDEMPOTENCY_CONFLICT", () -> service.submit(owner, base.projectId(), UUID.randomUUID(), "same-key"));
            assertCode("EXECUTION_PROJECT_BUSY", () -> service.submit(owner, base.projectId(), base.versionId(), "new-key"));
            assertThat(temporary.resolve("unused-root")).doesNotExist();
        } finally { threads.shutdownNow(); assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void disabledAndInvalidRequestsHaveNoExecutionSideEffects() {
        configuration.withProperty("atoms.project-execution.enabled", "false");
        assertCode("EXECUTION_DISABLED", () -> service.submit(owner, null, null, null));
        configuration.withProperty("atoms.project-execution.enabled", "true");
        for (String key : new String[] {null, "", " ", "\nkey", "a".repeat(129), "\ud800", "\udfff"})
            assertCode("EXECUTION_INVALID_INPUT", () -> service.submit(owner, UUID.randomUUID(), UUID.randomUUID(), key));
        assertCode("EXECUTION_INVALID_INPUT", () -> service.submit(owner, UUID.randomUUID(), null, "key"));
        assertThat(temporary.resolve("unused-root")).doesNotExist();
    }

    @Test void maintainerAndCurrentOwnerAndArchiveAreIndependentEvenOnReplay() {
        var base = source.create(owner);
        assertCode("EXECUTION_FORBIDDEN", () -> service.submit(stranger, base.projectId(), base.versionId(), "key"));
        configuration.withProperty("atoms.project-execution.maintainer-ids", owner + "," + stranger);
        assertCode("PROJECT_NOT_FOUND", () -> service.submit(stranger, base.projectId(), base.versionId(), "key"));
        var build = service.submit(owner, base.projectId(), base.versionId(), "key");
        assertCode("PROJECT_NOT_FOUND", () -> service.get(stranger, base.projectId(), build.id()));
        UUID other = source.createEmpty(owner);
        assertCode("EXECUTION_BUILD_NOT_FOUND", () -> service.get(owner, other, build.id()));
        projects.archive(owner, base.projectId().toString(), true);
        assertCode("EXECUTION_PROJECT_ARCHIVED", () -> service.submit(owner, base.projectId(), base.versionId(), "key"));
        assertCode("EXECUTION_PROJECT_ARCHIVED", () -> service.get(owner, base.projectId(), build.id()));
        projects.archive(owner, base.projectId().toString(), false);
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, base.projectId());
        assertCode("PROJECT_NOT_FOUND", () -> service.submit(owner, base.projectId(), base.versionId(), "key"));
        assertCode("PROJECT_NOT_FOUND", () -> service.get(owner, base.projectId(), build.id()));
        configuration.withProperty("atoms.project-execution.maintainer-ids", stranger.toString());
        assertCode("EXECUTION_FORBIDDEN", () -> service.get(owner, base.projectId(), build.id()));
    }

    @Test void lastQueueSlotIsClaimedAtomicallyAcrossProjects() throws Exception {
        configuration.withProperty("atoms.project-execution.queue-capacity", "1");
        var connections = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
        var insideTransactions = new java.util.concurrent.CyclicBarrier(2);
        repository = new ExecutionBuildRepository(jdbc) {
            @Override ExecutionBuild insert(UUID account, UUID project, ExecutionBuild.Request request,
                                             String treeHash, ExecutionSettings.Configuration config) {
                connections.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                try { insideTransactions.await(3, TimeUnit.SECONDS); }
                catch (Exception error) { throw new AssertionError("Both transactions must reach admission", error); }
                return super.insert(account, project, request, treeHash, config);
            }
        };
        var a = source.create(owner);
        var b = source.create(owner);
        var start = new CountDownLatch(1);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var first = threads.submit(() -> { start.await(); return outcome(a); });
            var second = threads.submit(() -> { start.await(); return outcome(b); });
            start.countDown();
            assertThat(java.util.List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("QUEUED", "EXECUTION_QUEUE_FULL");
            assertThat(connections).hasSize(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_builds WHERE owner_id=? AND queue_slot IS NOT NULL", Integer.class, owner)).isEqualTo(1);
            var winner = jdbc.queryForObject("SELECT project_id FROM execution_builds WHERE owner_id=?", UUID.class, owner);
            var base = winner.equals(a.projectId()) ? a : b;
            assertThat(service().submit(owner, base.projectId(), base.versionId(), "capacity-key").status()).isEqualTo("QUEUED");
        } finally { threads.shutdownNow(); assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    private String outcome(SourceAgentFixture.Base base) {
        try { return service().submit(owner, base.projectId(), base.versionId(), "capacity-key").status(); }
        catch (ApiException error) { return error.code(); }
    }

    @Test void loweringQueueLimitCountsOlderTasksEvenWhenTheFirstSlotIsFree() {
        var a = source.create(owner);
        var b = source.create(owner);
        var c = source.create(owner);
        var first = service.submit(owner, a.projectId(), a.versionId(), "first");
        var second = service.submit(owner, b.projectId(), b.versionId(), "second");
        jdbc.update("""
                UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,queue_slot=null,finished_at=now()
                WHERE id=?
                """, first.id());
        assertThat(jdbc.queryForObject("SELECT queue_slot FROM execution_builds WHERE id=?", Integer.class, second.id())).isEqualTo(2);
        configuration.withProperty("atoms.project-execution.queue-capacity", "1");
        assertCode("EXECUTION_QUEUE_FULL", () -> service().submit(owner, c.projectId(), c.versionId(), "third"));
        assertThat(service().submit(owner, b.projectId(), b.versionId(), "second").id()).isEqualTo(second.id());
    }

    @Test void rollbackRetainsNeitherTaskNorEventNorSlotAndNeverReadsSourceInsideTransaction() {
        var base = source.create(owner);
        var snapshot = source.readGateway().read(owner, base.projectId(), base.versionId());
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> assertThatThrownBy(
                () -> service.submit(owner, base.projectId(), base.versionId(), "rollback")).isInstanceOf(IllegalStateException.class));
        var rolledBack = tx.execute(status -> {
            projects.lock(owner, base.projectId().toString());
            var task = repository.insert(owner, base.projectId(), ExecutionBuild.request(base.versionId(), "rollback"),
                    snapshot.version().treeSha256(), new ExecutionSettings(configuration).load());
            assertThat(task.lastEventSeq()).isEqualTo(1);
            status.setRollbackOnly();
            return task.id();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_build_events WHERE build_id=?", Integer.class, rolledBack)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_builds WHERE project_id=?", Integer.class, base.projectId())).isZero();
        assertThat(service.submit(owner, base.projectId(), base.versionId(), "rollback").id()).isNotEqualTo(rolledBack);
    }

    @Test void requestingOldVersionNeverSubstitutesNewHeadAndForeignSourceIsRejected() {
        var base = source.create(owner);
        var old = source.readGateway().read(owner, base.projectId(), base.versionId());
        var files = new java.util.ArrayList<>(old.tree().files());
        files.add(new dev.atoms.demo.source.SourceTree.File("CHANGES.md",
                "second version".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0100644));
        var next = source.accept(owner, base, new dev.atoms.demo.source.ProjectSourcePolicy().validate(files));
        var other = source.create(owner);
        assertCode("SOURCE_VERSION_MISSING", () -> service.submit(owner, base.projectId(), other.versionId(), "foreign"));
        var task = service.submit(owner, base.projectId(), base.versionId(), "old-version");
        assertThat(task.sourceVersionId()).isEqualTo(base.versionId()).isNotEqualTo(next.versionId());
        assertThat(task.treeSha256()).isEqualTo(old.version().treeSha256());
        // A terminal replay is still the same historical intent; it must not consume a new slot.
        jdbc.update("""
                UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,queue_slot=null,finished_at=now()
                WHERE id=?
                """, task.id());
        assertThat(service().submit(owner, base.projectId(), base.versionId(), "old-version").id()).isEqualTo(task.id());
        assertThat(service.get(owner, base.projectId(), task.id()).status()).isEqualTo("CANCELLED");
    }

    @Test void rechecksArchiveOwnershipAndEnablementAfterActualSourceRead() {
        for (String expected : new String[] {"EXECUTION_PROJECT_ARCHIVED", "PROJECT_NOT_FOUND", "EXECUTION_DISABLED"}) {
            var base = source.create(owner);
            var gateway = spy(source.readGateway());
            doAnswer(call -> {
                var actual = call.callRealMethod();
                if (expected.equals("EXECUTION_PROJECT_ARCHIVED")) projects.archive(owner, base.projectId().toString(), true);
                else if (expected.equals("PROJECT_NOT_FOUND")) jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, base.projectId());
                else configuration.withProperty("atoms.project-execution.enabled", "false");
                return actual;
            }).when(gateway).read(owner, base.projectId(), base.versionId());
            var guarded = new ProjectExecutionService(new ExecutionSettings(configuration), repository, projects, gateway, transactions);
            assertCode(expected, () -> guarded.submit(owner, base.projectId(), base.versionId(), "read-race"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_builds WHERE project_id=?", Integer.class, base.projectId())).isZero();
        }
    }

    private void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
