package dev.atoms.demo.projectexecution;

import com.zaxxer.hikari.HikariDataSource;
import dev.atoms.demo.project.ProjectService;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
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
class ExecutionBuildQueueIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired HikariDataSource database;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
    final List<ExecutionBuild> fixtures = new ArrayList<>();
    MockEnvironment config;
    ExecutionSettings settings;
    ExecutionBuildRepository repository;
    Connection connection;
    ExecutionCoordinatorLease lease;

    @BeforeEach void setup() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("atoms_demo_test");
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo("atoms_verify_20261021_ex10");
        jdbc.update("INSERT INTO guest_users(id) VALUES (?),(?)", owner, stranger);
        config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        for (String key : List.of("root", "node", "npm", "java", "maven", "policy"))
            config.withProperty("atoms.project-execution." + key, temporary.resolve("unused-" + key).toString());
        settings = new ExecutionSettings(config); repository = new ExecutionBuildRepository(jdbc);
        connection = database.getConnection(); lease = new ExecutionCoordinatorLease(connection, database::evictConnection);
    }

    @AfterEach void retainEvidence() throws Exception {
        try {
            for (var build : fixtures) {
                // Queue fixtures never launch tools; retain all metadata/events and settle only their known idle rows.
                assertThat(row(build).get("execution_pending")).isEqualTo(false);
                jdbc.update("""
                        UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,queue_slot=null,
                            worker_slot=null,finished_at=clock_timestamp()
                        WHERE id=? AND finished_at IS NULL AND NOT execution_pending
                        """, build.id());
            }
        } finally { if (lease != null) lease.close(); }
    }

    @Test void waitingIsOrderedBoundedAndNeverReturnsClaimedOrTerminalWork() throws Exception {
        var claimed = enqueue(); claim(claimed);
        var first = enqueue(); var second = enqueue(); var cancelled = enqueue();
        queue().cancelWaiting(cancelled);
        assertThat(queue().waiting()).extracting(ExecutionBuild::id).containsExactly(first.id(), second.id());
        assertThat(queue().waiting()).hasSizeLessThanOrEqualTo(8);
        var before = row(first);
        assertThat(queue().beforeDispatch(first)).isEqualTo(first);
        assertThat(row(first)).isEqualTo(before);
        assertThat(temporary.resolve("unused-root")).doesNotExist();
    }

    @Test void originalDatabaseDeadlineExpiresAfterLockDelayAndReturnsTheQueueSlot() throws Exception {
        config.withProperty("atoms.project-execution.queue-timeout-seconds", "1")
                .withProperty("atoms.project-execution.queue-capacity", "1");
        var build = enqueue();
        config.withProperty("atoms.project-execution.queue-timeout-seconds", "300");
        repository = new ExecutionBuildRepository(jdbc) {
            @Override ExecutionBuild lock(UUID project, UUID id) {
                var current = super.lock(project, id);
                jdbc.queryForObject("SELECT 1 FROM pg_catalog.pg_sleep(1.1)", Integer.class);
                return current;
            }
        };
        var result = queue().beforeDispatch(build);
        assertThat(result.status()).isEqualTo("TIMED_OUT");
        assertThat(result.errorCode()).isEqualTo("EXECUTION_QUEUE_TIMEOUT");
        assertThat(result.finishedAt()).isNotNull(); assertThat(row(build).get("queue_slot")).isNull();
        assertThat(result.lastEventSeq()).isEqualTo(2);
        var saved = row(build); assertThat(queue().beforeDispatch(build)).isEqualTo(result);
        assertThat(row(build)).isEqualTo(saved);
        assertThat(enqueue().status()).isEqualTo("QUEUED");
    }

    @Test void revokedArchivedAndTransferredProjectsCannotRemainDispatchable() throws Exception {
        for (String reason : List.of("EXECUTION_FORBIDDEN", "EXECUTION_PROJECT_ARCHIVED", "EXECUTION_BUILD_OWNER_CHANGED")) {
            var build = enqueue();
            if (reason.equals("EXECUTION_FORBIDDEN")) config.withProperty("atoms.project-execution.maintainer-ids", stranger.toString());
            else if (reason.equals("EXECUTION_PROJECT_ARCHIVED")) projects.archive(owner, build.projectId().toString(), true);
            else jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, build.projectId());
            var result = queue().beforeDispatch(build);
            assertThat(result.status()).isEqualTo("FAILED"); assertThat(result.errorCode()).isEqualTo(reason);
            assertThat(row(build).get("queue_slot")).isNull();
            config.withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        }
    }

    @Test void cancellationWinsOverExpiryAndShutdownCanSettleAfterDisablement() throws Exception {
        config.withProperty("atoms.project-execution.queue-timeout-seconds", "1");
        var cancelled = enqueue(); var shutdown = enqueue();
        jdbc.update("UPDATE execution_builds SET cancel_requested=true WHERE id=?", cancelled.id());
        jdbc.queryForObject("SELECT 1 FROM pg_catalog.pg_sleep(1.1)", Integer.class);
        assertThat(queue().beforeDispatch(cancelled).status()).isEqualTo("CANCELLED");
        config.withProperty("atoms.project-execution.enabled", "false");
        var before = row(shutdown);
        unavailable(() -> queue().beforeDispatch(shutdown)); assertThat(row(shutdown)).isEqualTo(before);
        assertThat(queue().cancelWaiting(shutdown).status()).isEqualTo("CANCELLED");
        assertThat(queue().cancelWaiting(shutdown).lastEventSeq()).isEqualTo(2);
    }

    @Test void staleQueueCandidatesCannotSettleClaimedOrUnresolvedWork() throws Exception {
        var build = enqueue(); claim(build);
        for (String status : List.of("PREPARING", "NEEDS_RECONCILIATION")) {
            if (status.equals("NEEDS_RECONCILIATION")) jdbc.update("UPDATE execution_builds SET status='NEEDS_RECONCILIATION' WHERE id=?", build.id());
            var before = row(build);
            assertThat(queue().beforeDispatch(build).status()).isEqualTo(status);
            assertThat(queue().cancelWaiting(build).status()).isEqualTo(status);
            assertThat(row(build)).isEqualTo(before); assertThat(row(build).get("worker_slot")).isEqualTo(1);
        }
    }

    @Test void mismatchedCandidateIdentityCannotCancelTheStoredTask() {
        var build = enqueue(); var before = row(build);
        var wrong = new ExecutionBuild(build.id(), build.projectId(), stranger, build.sourceVersionId(), build.treeSha256(),
                build.keySha256(), build.requestSha256(), build.status(), false, null, build.createdAt(), null, build.lastEventSeq());
        unavailable(() -> queue().cancelWaiting(wrong));
        assertThat(row(build)).isEqualTo(before);
    }

    @Test void leaseLossDuringSettlementRollsBackTheEventAndCapacityChange() throws Exception {
        var build = enqueue(); var before = row(build);
        repository = new ExecutionBuildRepository(jdbc) {
            @Override ExecutionBuild get(UUID project, UUID id) {
                var current = super.get(project, id);
                if (current.finishedAt() != null) loseLock();
                return current;
            }
        };
        unavailable(() -> queue().cancelWaiting(build));
        assertThat(row(build)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_build_events WHERE build_id=?", Integer.class, build.id())).isEqualTo(1);
        unavailable(() -> queue().waiting());
    }

    @Test void ambientTransactionsAndAlreadyLostAuthorityHaveNoQueueEffects() throws Exception {
        var build = enqueue(); var before = row(build);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> unavailable(() -> queue().cancelWaiting(build)));
        loseLock();
        unavailable(() -> queue().beforeDispatch(build)); unavailable(() -> queue().cancelWaiting(build));
        assertThat(row(build)).isEqualTo(before);
    }

    private ExecutionBuildQueue queue() {
        return new ExecutionBuildQueue(settings, repository, jdbc, transactions, lease);
    }
    private ExecutionBuild enqueue() {
        UUID project = UUID.randomUUID(), version = UUID.randomUUID(); String hash = "a".repeat(64);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'queue-metadata-fixture')", project, owner);
        jdbc.update("INSERT INTO project_sources(project_id) VALUES (?)", project);
        jdbc.update("INSERT INTO source_versions(id,project_id,version_no,commit_id,tree_sha256,file_count,tree_bytes) VALUES (?,?,1,?,?,1,1)",
                version, project, "a".repeat(40), hash);
        var build = new TransactionTemplate(transactions).execute(tx -> {
            projects.lock(owner, project.toString());
            return repository.insert(owner, project, ExecutionBuild.request(version, UUID.randomUUID().toString()), hash, settings.load());
        });
        fixtures.add(build); return build;
    }
    private void claim(ExecutionBuild build) {
        new ExecutionBuildClaim(settings, projects, repository, jdbc, transactions).claim(owner, build.projectId(), build.id());
    }
    private Map<String, Object> row(ExecutionBuild build) {
        return jdbc.queryForMap("SELECT * FROM execution_builds WHERE id=?", build.id());
    }
    private void loseLock() {
        try (var statement = connection.createStatement()) { statement.execute("SELECT pg_catalog.pg_advisory_unlock(718395204)"); }
        catch (java.sql.SQLException error) { throw new IllegalStateException("Fixture cannot release its own lock"); }
    }
    private void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(java.io.IOException.class).hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
    }
}
