package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.SourceAgentFixture;
import java.nio.file.Path;
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
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false",
        "atoms.generation.worker-enabled=false"})
class ExecutionBuildClaimIT {
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
        for (String key : List.of("root", "node", "npm", "java", "maven", "policy"))
            configuration.withProperty("atoms.project-execution." + key, temporary.resolve("unused-" + key).toString());
        repository = new ExecutionBuildRepository(jdbc);
        service = new ProjectExecutionService(new ExecutionSettings(configuration), repository, projects, source.readGateway(), transactions);
    }

    @AfterEach void close() throws Exception {
        if (source == null) return;
        try {
            for (UUID project : source.projectIds()) {
                // These claim-only fixtures never launch an execution; preserve all rows and events.
                assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_builds WHERE project_id=? AND execution_pending",
                        Integer.class, project)).isZero();
                jdbc.update("""
                        UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,
                            queue_slot=null,worker_slot=null,finished_at=now()
                        WHERE project_id=? AND finished_at IS NULL AND NOT execution_pending
                        """, project);
            }
        } finally { source.close(); }
    }

    @Test void commitsOnePreparingTransitionWithFrozenIdentityAndNoExecutionSideEffects() {
        var queued = queue();
        var claimed = claim().claim(owner, queued.projectId(), queued.id());
        assertThat(claimed.status()).isEqualTo("PREPARING");
        assertThat(claimed.sourceVersionId()).isEqualTo(queued.sourceVersionId());
        assertThat(claimed.treeSha256()).isEqualTo(queued.treeSha256());
        assertThat(claimed.lastEventSeq()).isEqualTo(2);
        var state = row(queued);
        assertThat(state.get("queue_slot")).isNull(); assertThat(state.get("worker_slot")).isEqualTo(1);
        assertThat(state.get("execution_pending")).isEqualTo(false);
        assertThat(temporary.resolve("unused-root")).doesNotExist();
        assertCode("EXECUTION_BUILD_NOT_QUEUED", () -> claim().claim(owner, queued.projectId(), queued.id()));
        assertThat(row(queued)).isEqualTo(state);
    }

    @Test void concurrentClaimsForTheSameTaskOnlyAdmitOneWorker() throws Exception {
        var queued = queue();
        assertThat(race(() -> outcome(queued), () -> outcome(queued)))
                .containsExactlyInAnyOrder("PREPARING", "EXECUTION_BUILD_NOT_QUEUED");
        assertThat(repository.get(queued.projectId(), queued.id()).lastEventSeq()).isEqualTo(2);
    }

    @Test void twoDatabaseConnectionsCannotTakeTheLastWorkerSlotAcrossProjects() throws Exception {
        var first = queue(); var second = queue();
        var connections = ConcurrentHashMap.<Integer>newKeySet(); var barrier = new CyclicBarrier(2);
        repository = new ExecutionBuildRepository(jdbc) {
            @Override void lockCapacity() {
                connections.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                try { barrier.await(3, TimeUnit.SECONDS); }
                catch (Exception error) { throw new AssertionError("Both claims must reach the capacity boundary", error); }
                super.lockCapacity();
            }
        };
        assertThat(race(() -> outcome(first), () -> outcome(second)))
                .containsExactlyInAnyOrder("PREPARING", "EXECUTION_WORKER_FULL");
        assertThat(connections).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_builds WHERE owner_id=? AND worker_slot IS NOT NULL",
                Integer.class, owner)).isEqualTo(1);
    }

    @Test void capacityCountsOldHighSlotsAndUnsettledRowsAfterConfigurationShrinks() {
        configuration.withProperty("atoms.project-execution.max-concurrent-builds", "4");
        var first = queue(); var second = queue(); var third = queue();
        claim().claim(owner, first.projectId(), first.id()); claim().claim(owner, second.projectId(), second.id());
        jdbc.update("UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,worker_slot=null,finished_at=now() WHERE id=?", first.id());
        // No execution was started. This deliberately retained-capacity fixture is not a stop proof.
        jdbc.update("UPDATE execution_builds SET status='NEEDS_RECONCILIATION' WHERE id=?", second.id());
        assertThat(row(second).get("worker_slot")).isEqualTo(2);
        configuration.withProperty("atoms.project-execution.max-concurrent-builds", "1");
        assertCode("EXECUTION_WORKER_FULL", () -> claim().claim(owner, third.projectId(), third.id()));
        assertThat(row(third).get("status")).isEqualTo("QUEUED");
        configuration.withProperty("atoms.project-execution.max-concurrent-builds", "4");
        assertThat(claim().claim(owner, third.projectId(), third.id()).status()).isEqualTo("PREPARING");
        assertThat(row(third).get("worker_slot")).isEqualTo(1);
    }

    @Test void laterExpansionCannotOverrideTheTasksOriginalWorkerCapacity() {
        var first = queue(); var second = queue();
        configuration.withProperty("atoms.project-execution.max-concurrent-builds", "4");
        claim().claim(owner, first.projectId(), first.id());
        assertCode("EXECUTION_WORKER_FULL", () -> claim().claim(owner, second.projectId(), second.id()));
        assertThat(row(second).get("worker_capacity")).isEqualTo(1);
    }

    @Test void disabledRevokedArchivedForeignAndChangedOwnerRequestsLeaveTheQueueUntouched() {
        var queued = queue();
        var initial = row(queued);
        configuration.withProperty("atoms.project-execution.enabled", "false");
        assertCode("EXECUTION_DISABLED", () -> claim().claim(owner, queued.projectId(), queued.id()));
        configuration.withProperty("atoms.project-execution.enabled", "true");
        assertCode("EXECUTION_FORBIDDEN", () -> claim().claim(stranger, queued.projectId(), queued.id()));
        configuration.withProperty("atoms.project-execution.maintainer-ids", owner + "," + stranger);
        assertCode("PROJECT_NOT_FOUND", () -> claim().claim(stranger, queued.projectId(), queued.id()));
        projects.archive(owner, queued.projectId().toString(), true);
        assertCode("EXECUTION_PROJECT_ARCHIVED", () -> claim().claim(owner, queued.projectId(), queued.id()));
        projects.archive(owner, queued.projectId().toString(), false);
        assertCode("EXECUTION_BUILD_NOT_FOUND", () -> claim().claim(owner, queued.projectId(), UUID.randomUUID()));
        assertCode("EXECUTION_INVALID_INPUT", () -> claim().claim(owner, null, queued.id()));
        assertCode("EXECUTION_INVALID_INPUT", () -> claim().claim(owner, queued.projectId(), null));
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, queued.projectId());
        assertCode("PROJECT_NOT_FOUND", () -> claim().claim(owner, queued.projectId(), queued.id()));
        assertCode("EXECUTION_BUILD_OWNER_CHANGED", () -> claim().claim(stranger, queued.projectId(), queued.id()));
        assertThat(row(queued)).isEqualTo(initial);
    }

    @Test void cancellationAndAmbientTransactionsCannotClaim() {
        var queued = queue();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> assertThatThrownBy(
                () -> claim().claim(owner, queued.projectId(), queued.id())).isInstanceOf(IllegalStateException.class));
        jdbc.update("UPDATE execution_builds SET cancel_requested=true WHERE id=?", queued.id());
        var before = row(queued);
        assertCode("EXECUTION_CANCELLED", () -> claim().claim(owner, queued.projectId(), queued.id()));
        assertThat(row(queued)).isEqualTo(before);
    }

    @Test void expirationWhileWaitingForCapacityDoesNotStartOrResetTheQueuedTask() {
        configuration.withProperty("atoms.project-execution.queue-timeout-seconds", "2");
        var queued = queue(); var before = row(queued);
        repository = new ExecutionBuildRepository(jdbc) {
            @Override void lockCapacity() {
                super.lockCapacity();
                jdbc.queryForObject("SELECT 1 FROM pg_catalog.pg_sleep(2.1)", Integer.class);
            }
        };
        assertCode("EXECUTION_QUEUE_TIMEOUT", () -> claim().claim(owner, queued.projectId(), queued.id()));
        assertThat(row(queued)).isEqualTo(before);
    }

    @Test void failedFinalReadRollsBackStageEventAndCapacityWithoutExposingDatabaseDetails() {
        var queued = queue(); var before = row(queued);
        repository = new ExecutionBuildRepository(jdbc) {
            @Override ExecutionBuild get(UUID project, UUID build) {
                var value = super.get(project, build);
                if (value.status().equals("PREPARING"))
                    throw new org.springframework.dao.DataAccessResourceFailureException("PRIVATE_DATABASE_CANARY");
                return value;
            }
        };
        assertThatThrownBy(() -> claim().claim(owner, queued.projectId(), queued.id()))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo("EXECUTION_STATE_UNAVAILABLE");
                    assertThat(error.getMessage()).doesNotContain("CANARY"); assertThat(error.getCause()).isNull();
                });
        assertThat(row(queued)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_build_events WHERE build_id=?", Integer.class, queued.id())).isEqualTo(1);
    }

    @Test void agentCoordinatorLockIdentifierDoesNotBlockBuildAdmissionOrClaim() throws Exception {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
                // Transaction-scoped hold of the Agent key has the same cross-session conflict namespace.
                statement.execute("SELECT pg_catalog.pg_advisory_xact_lock(718395202)");
                var queued = queue();
                assertThat(claim().claim(owner, queued.projectId(), queued.id()).status()).isEqualTo("PREPARING");
            } finally { connection.rollback(); }
        }
    }

    private ExecutionBuild queue() {
        var base = source.create(owner);
        var queued = service.submit(owner, base.projectId(), base.versionId(), "claim");
        return repository.get(base.projectId(), queued.id());
    }
    private ExecutionBuildClaim claim() {
        return new ExecutionBuildClaim(new ExecutionSettings(configuration), projects, repository, jdbc, transactions);
    }
    private Map<String, Object> row(ExecutionBuild build) {
        return jdbc.queryForMap("SELECT * FROM execution_builds WHERE id=?", build.id());
    }
    private String outcome(ExecutionBuild build) {
        try { return claim().claim(owner, build.projectId(), build.id()).status(); }
        catch (ApiException error) { return error.code(); }
    }
    private List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { start.await(); return first.call(); });
            var b = pool.submit(() -> { start.await(); return second.call(); }); start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    private void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
