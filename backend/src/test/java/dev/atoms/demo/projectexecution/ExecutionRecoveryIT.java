package dev.atoms.demo.projectexecution;

import com.zaxxer.hikari.HikariDataSource;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
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

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false",
        "atoms.generation.worker-enabled=false"})
class ExecutionRecoveryIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired HikariDataSource database;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID();
    final List<ExecutionBuild> fixtures = new ArrayList<>();
    final Map<UUID, OwnedProcess.Stopped> receipts = new HashMap<>();
    final Map<UUID, UUID> executions = new HashMap<>();
    final Map<UUID, Path> directories = new HashMap<>();
    final AtomicBoolean ready = new AtomicBoolean(true);
    MockEnvironment config;
    ExecutionSettings settings;
    ExecutionBuildRepository builds;
    ExecutionPaths paths;
    ExecutionCoordinatorLease lease;

    @BeforeEach void setup() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo("atoms_demo_test.atoms_verify_20261021_ex10");
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        for (String key : List.of("root", "node", "npm", "java", "maven", "policy"))
            config.withProperty("atoms.project-execution." + key, temporary.toRealPath().resolve(key).toString());
        settings = new ExecutionSettings(config); builds = new ExecutionBuildRepository(jdbc);
        paths = new ExecutionPaths(settings.load().root(), List.of());
        lease = new ExecutionCoordinatorLease(database);
    }

    @AfterEach void close() throws Exception {
        try {
            for (var build : fixtures) {
                if (Boolean.TRUE.equals(row(build).get("execution_pending"))) {
                    // Only this test's lost completion: keep the real original stop receipt, never adopt an old PID.
                    var receipt = receipts.get(build.id());
                    assertThat(receipt).isNotNull();
                    assertThat(receipt.matches(build.id(), executions.get(build.id()), directories.get(build.id()))).isTrue();
                    jdbc.update("UPDATE execution_builds SET execution_pending=false WHERE id=?", build.id());
                }
                jdbc.update("""
                        UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,queue_slot=null,
                            worker_slot=null,finished_at=clock_timestamp() WHERE id=? AND finished_at IS NULL AND NOT execution_pending
                        """, build.id());
            }
        } finally { try { if (paths != null) paths.close(); } finally { if (lease != null) lease.close(); } }
    }

    @Test void neverStartedWorkIsInterruptedAndRecoveryIsIdempotent() throws Exception {
        var build = claimed(); var recovery = recovery(jdbc);
        assertThat(recovery.reconcile(build).status()).isEqualTo("FAILED");
        assertThat(row(build).get("error_code")).isEqualTo("EXECUTION_INTERRUPTED");
        assertThat(row(build).get("worker_slot")).isNull();
        assertThat(row(build).get("finished_at")).isNotNull();
        var before = row(build); var events = events(build);
        recovery.reconcile(build);
        assertThat(row(build)).isEqualTo(before); assertThat(events(build)).isEqualTo(events);
        assertThat(paths.checkedRoot().resolve("builds/" + build.id())).doesNotExist();
    }

    @Test void cancellationIsHonoredOnlyWhenNoExecutionRemainsUnknown() throws Exception {
        var build = claimed(); jdbc.update("UPDATE execution_builds SET cancel_requested=true WHERE id=?", build.id());
        assertThat(recovery(jdbc).reconcile(build).status()).isEqualTo("CANCELLED");
        assertThat(row(build).get("error_code")).isEqualTo("EXECUTION_CANCELLED");
    }

    @Test void confirmedCompletedStageCanBeInterruptedButIsNeverReplayed() throws Exception {
        var build = claimed(); completeStage(build, true);
        int count = events(build).size();
        assertThat(recovery(jdbc).reconcile(build).status()).isEqualTo("FAILED");
        assertThat(row(build).get("execution_pending")).isEqualTo(false);
        assertThat(row(build).get("worker_slot")).isNull();
        assertThat(events(build)).hasSize(count + 1);
    }

    @Test void lostCompletionPreservesPendingCapacityAndNeverTreatsCancellationAsStop() throws Exception {
        var build = claimed(); completeStage(build, false);
        jdbc.update("UPDATE execution_builds SET cancel_requested=true WHERE id=?", build.id());
        var before = events(build);
        assertThat(recovery(jdbc).reconcile(build).status()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(row(build).get("execution_pending")).isEqualTo(true);
        assertThat(row(build).get("worker_slot")).isEqualTo(1);
        assertThat(row(build).get("finished_at")).isNull();
        assertThat(events(build).subList(0, before.size())).isEqualTo(before);
        var unknown = row(build); recovery(jdbc).reconcile(build);
        assertThat(row(build)).isEqualTo(unknown);
    }

    @Test void packagingAndExistingUnknownRowsNeverBecomeSuccessOrReleaseCapacity() throws Exception {
        var build = claimed();
        for (String state : List.of("INSTALLING", "CHECKING", "PACKAGING"))
            jdbc.update("UPDATE execution_builds SET status=? WHERE id=?", state, build.id());
        assertThat(recovery(jdbc).reconcile(build).status()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(row(build).get("execution_pending")).isEqualTo(false);
        assertThat(row(build).get("worker_slot")).isEqualTo(1);
        assertThat(row(build).get("jar_sha256")).isNull();
        var unknown = row(build); recovery(jdbc).reconcile(build);
        assertThat(row(build)).isEqualTo(unknown);
    }

    @Test void inconsistentEventHistoryDoesNotTurnMissingPendingFlagIntoAStopProof() throws Exception {
        var build = claimed(); completeStage(build, false);
        // Deliberately model inconsistent metadata; the real receipt is held by this test, not recovery.
        jdbc.update("UPDATE execution_builds SET execution_pending=false WHERE id=?", build.id());
        assertThat(recovery(jdbc).reconcile(build).status()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(row(build).get("worker_slot")).isEqualTo(1);
    }

    @Test void queuedWorkIsNotClaimedByRecovery() throws Exception {
        var build = enqueue(); var before = row(build);
        recovery(jdbc).reconcile(build);
        assertThat(row(build)).isEqualTo(before);
    }

    @Test void lostLeaseUnreadySourceAndAmbientTransactionsLeaveEvidenceUntouched() throws Exception {
        var build = claimed(); var before = row(build); var recovery = recovery(jdbc);
        ready.set(false);
        assertThatThrownBy(() -> recovery.reconcile(build)).hasMessage("EXECUTION_RECOVERY_UNAVAILABLE").hasNoCause();
        ready.set(true);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                assertThatThrownBy(() -> recovery.reconcile(build)).hasMessage("EXECUTION_RECOVERY_UNAVAILABLE"));
        lease.close();
        assertThatThrownBy(() -> recovery.reconcile(build)).hasMessage("EXECUTION_RECOVERY_UNAVAILABLE");
        assertThat(row(build)).isEqualTo(before);
    }

    @Test void configurationDriftAfterTheRowLockRollsBackRecovery() throws Exception {
        var build = claimed(); var before = row(build);
        var changed = new JdbcTemplate(database) {
            @Override public Map<String, Object> queryForMap(String sql, Object... args) {
                var row = super.queryForMap(sql, args);
                if (sql.contains("execution_builds") && sql.contains("FOR UPDATE"))
                    config.withProperty("atoms.project-execution.root", temporary.resolve("changed-root").toString());
                return row;
            }
        };
        assertThatThrownBy(() -> recovery(changed).reconcile(build)).hasMessage("EXECUTION_RECOVERY_UNAVAILABLE");
        assertThat(row(build)).isEqualTo(before);
    }

    private ExecutionRecovery recovery(JdbcTemplate db) {
        return new ExecutionRecovery(settings, builds, db, transactions, lease, ready::get);
    }
    private ExecutionBuild claimed() {
        var build = enqueue();
        return new ExecutionBuildClaim(settings, projects, builds, jdbc, transactions).claim(owner, build.projectId(), build.id());
    }
    private ExecutionBuild enqueue() {
        UUID project = UUID.randomUUID(), version = UUID.randomUUID(); String hash = "a".repeat(64);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'recovery-fixture')", project, owner);
        jdbc.update("INSERT INTO project_sources(project_id) VALUES (?)", project);
        jdbc.update("INSERT INTO source_versions(id,project_id,version_no,commit_id,tree_sha256,file_count,tree_bytes) VALUES (?,?,1,?,?,1,1)",
                version, project, "a".repeat(40), hash);
        var build = new TransactionTemplate(transactions).execute(tx -> {
            projects.lock(owner, project.toString());
            return builds.insert(owner, project, ExecutionBuild.request(version, UUID.randomUUID().toString()), hash, settings.load());
        });
        fixtures.add(build); return build;
    }
    private void completeStage(ExecutionBuild build, boolean record) throws Exception {
        var task = paths.createBuild(build.id());
        Path cwd = ExecutionRootLease.directory(paths.project(task).resolve("frontend"));
        var journal = new ExecutionBuildJournal(settings, projects, jdbc, transactions, build, paths, task);
        UUID execution = UUID.randomUUID(); executions.put(build.id(), execution); directories.put(build.id(), cwd);
        journal.begin(ExecutionToolchain.Operation.NPM_CI, execution);
        Path java = Path.of(System.getProperty("java.home"));
        var result = new ExecutionProcess().run(new ExecutionProcess.Invocation(java.resolve("bin/java"), List.of("-version"),
                cwd, cwd, cwd, java, cwd), new ExecutionProcess.Deadline(Duration.ofSeconds(20)), () -> false,
                build.id(), execution, () -> {});
        receipts.put(build.id(), result.stopped());
        if (record) journal.finish(new ExecutionFrontend.Stage(ExecutionToolchain.Operation.NPM_CI, execution,
                null, result.stopped(), result.summary(), null));
    }
    private Map<String, Object> row(ExecutionBuild build) { return jdbc.queryForMap("SELECT * FROM execution_builds WHERE id=?", build.id()); }
    private List<String> events(ExecutionBuild build) {
        return jdbc.queryForList("SELECT payload::text FROM execution_build_events WHERE build_id=? ORDER BY seq", String.class, build.id());
    }
}
