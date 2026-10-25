package dev.atoms.demo.projectexecution;

import com.zaxxer.hikari.HikariDataSource;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.ProjectSourceReadGateway;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
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
import static org.mockito.Mockito.mock;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false",
        "atoms.generation.worker-enabled=false"})
class ExecutionSchedulerIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired HikariDataSource database;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID();
    final List<ExecutionBuild> fixtures = new ArrayList<>();
    final AtomicBoolean sourceReady = new AtomicBoolean(true);
    final AtomicInteger opens = new AtomicInteger();
    MockEnvironment config;
    ExecutionSettings settings;
    ExecutionBuildRepository builds;
    ProjectExecutionService service;
    ExecutionCoordinator coordinator;
    volatile ExecutionSchedulerFixture resources;
    volatile Connection connection;
    HikariDataSource pool;

    @BeforeEach void setup() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        for (String name : List.of("root", "node", "npm", "java", "maven", "policy"))
            config.withProperty("atoms.project-execution." + name, temporary.toRealPath().resolve("execution-" + name).toString());
        settings = new ExecutionSettings(config); builds = new ExecutionBuildRepository(jdbc);
        service = new ProjectExecutionService(settings, builds, projects, mock(ProjectSourceReadGateway.class), transactions);
        pool = new HikariDataSource() {
            @Override public Connection getConnection() throws java.sql.SQLException {
                var own = super.getConnection(); connection = own; return own;
            }
        };
        pool.setJdbcUrl(database.getJdbcUrl()); pool.setUsername(database.getUsername()); pool.setPassword(database.getPassword());
        pool.setMaximumPoolSize(1); pool.setMinimumIdle(0); pool.setConnectionTimeout(3000);
        pool.setPoolName("execution-scheduler-fixture");
    }

    @AfterEach void retainEvidence() throws Exception {
        try {
            // Only this test's deliberate lost result can be recorded, with its original real stop receipt.
            if (resources != null) for (var build : fixtures)
                if (Boolean.TRUE.equals(row(build).get("execution_pending")) && resources.stopped.containsKey(build.id()))
                    resources.recordHeldStop(build);
            if (coordinator != null) coordinator.close();
            for (var build : fixtures) {
                assertThat(row(build).get("execution_pending")).isEqualTo(false);
                jdbc.update("""
                        UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,queue_slot=null,
                            worker_slot=null,finished_at=clock_timestamp() WHERE id=? AND finished_at IS NULL AND NOT execution_pending
                        """, build.id());
            }
            if (resources != null) {
                assertThat(resources.closed).isTrue();
                for (var build : fixtures) if (Files.exists(marker(build))) {
                    long pid = Long.parseLong(Files.readString(marker(build)));
                    assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
                    assertThat(resources.stopped).containsKey(build.id());
                }
            }
        } finally { if (pool != null) pool.close(); }
    }

    @Test void disabledOrUnreadyNeverOpensResourcesOrStartsWork() {
        config.withProperty("atoms.project-execution.enabled", "false");
        coordinator = create(); coordinator.start(); assertThat(coordinator.isRunning()).isFalse(); coordinator.close();
        config.withProperty("atoms.project-execution.enabled", "true"); sourceReady.set(false);
        coordinator = create(); coordinator.start(); assertThat(coordinator.isRunning()).isFalse();
        assertThat(opens).hasValue(0); assertThat(connection).isNull();
    }

    @Test void defaultOneWorkerUsesPersistedCancellationAndClosesQueuedWork() throws Exception {
        var first = enqueue(); var second = enqueue(); var third = enqueue();
        start(); await(() -> Files.exists(marker(first)));
        assertThat(row(first).get("execution_pending")).isEqualTo(true);
        assertThat(builds.get(second.projectId(), second.id()).status()).isEqualTo("QUEUED");
        service.cancel(owner, first.projectId(), first.id());
        await(() -> Files.exists(marker(second)) && terminal(first));
        assertThat(builds.get(first.projectId(), first.id()).status()).isEqualTo("CANCELLED");
        coordinator.close();
        assertThat(resources.closed).isTrue(); assertThat(resources.launches).hasValue(2);
        for (var build : fixtures) {
            assertThat(builds.get(build.projectId(), build.id()).status()).isEqualTo("CANCELLED");
            assertThat(row(build).get("cancel_requested")).isEqualTo(true);
        }
        assertThat(marker(third)).doesNotExist();
        var before = row(second); coordinator.close(); assertThat(row(second)).isEqualTo(before);
    }

    @Test void fourWorkersMakeIndependentProgressWithoutExceedingTheLimit() throws Exception {
        config.withProperty("atoms.project-execution.max-concurrent-builds", "4");
        for (int i = 0; i < 5; i++) enqueue();
        start(); await(() -> fixtures.subList(0, 4).stream().allMatch(b -> Files.exists(marker(b))));
        assertThat(resources.launches).hasValue(4); assertThat(marker(fixtures.get(4))).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_builds WHERE worker_slot IS NOT NULL", Integer.class)).isEqualTo(4);
        var first = fixtures.get(0); service.cancel(owner, first.projectId(), first.id());
        await(() -> Files.exists(marker(fixtures.get(4))) && terminal(first));
        assertThat(resources.launches).hasValue(5);
        assertThat(fixtures.subList(1, 4)).allSatisfy(b -> assertThat(row(b).get("execution_pending")).isEqualTo(true));
        coordinator.close(); assertThat(fixtures).allSatisfy(b -> assertThat(terminal(b)).isTrue());
    }

    @Test void increasingConfigurationDoesNotOverAdmitTasksWithAnOlderCapacity() throws Exception {
        var first = enqueue(); var second = enqueue();
        config.withProperty("atoms.project-execution.max-concurrent-builds", "4");
        start(); await(() -> Files.exists(marker(first)));
        Thread.sleep(500);
        assertThat(resources.launches).hasValue(1); assertThat(coordinator.isRunning()).isTrue();
        service.cancel(owner, first.projectId(), first.id());
        await(() -> Files.exists(marker(second))); coordinator.close();
    }

    @Test void queueExpiryIsProcessedWhileEveryWorkerIsOccupied() throws Exception {
        var active = enqueue(); start(); await(() -> Files.exists(marker(active)));
        config.withProperty("atoms.project-execution.queue-timeout-seconds", "1");
        var expired = enqueue(); await(() -> terminal(expired));
        assertThat(builds.get(expired.projectId(), expired.id()).status()).isEqualTo("TIMED_OUT");
        assertThat(resources.launches).hasValue(1); assertThat(marker(expired)).doesNotExist();
        coordinator.close();
    }

    @Test void anotherCoordinatorCannotOpenItsRootOrTakeTheCurrentQueue() throws Exception {
        var active = enqueue(); start(); await(() -> Files.exists(marker(active)));
        var otherOpens = new AtomicInteger();
        try (var other = new ExecutionCoordinator(settings, builds, jdbc, transactions, database, sourceReady::get,
                () -> { otherOpens.incrementAndGet(); throw new java.io.IOException(); })) {
            other.start(); assertThat(other.isRunning()).isFalse(); assertThat(otherOpens).hasValue(0);
            assertThat(coordinator.isRunning()).isTrue(); assertThat(row(active).get("execution_pending")).isEqualTo(true);
        }
        coordinator.close();
        try (var next = new ExecutionCoordinatorLease(database)) { assertThat(next.healthy()).isTrue(); }
    }

    @Test void abandonedWorkWithoutIntentIsSettledBeforeTheNextQueueItemStarts() throws Exception {
        var build = enqueue();
        new ExecutionBuildClaim(settings, projects, builds, jdbc, transactions).claim(owner, build.projectId(), build.id());
        var waiting = enqueue(); start();
        assertThat(builds.get(build.projectId(), build.id()).status()).isEqualTo("FAILED");
        assertThat(row(build).get("error_code")).isEqualTo("EXECUTION_INTERRUPTED");
        await(() -> Files.exists(marker(waiting)));
        assertThat(resources.launches).hasValue(1); assertThat(marker(build)).doesNotExist();
        coordinator.close();
    }

    @Test void unresolvedWorkBlocksStartupAndLeavesTheQueueUntouched() throws Exception {
        var build = enqueue();
        new ExecutionBuildClaim(settings, projects, builds, jdbc, transactions).claim(owner, build.projectId(), build.id());
        jdbc.update("UPDATE execution_builds SET status='NEEDS_RECONCILIATION',error_code='EXECUTION_RECOVERY_UNCERTAIN' WHERE id=?", build.id());
        var waiting = enqueue(); var before = row(build); var queued = row(waiting);
        coordinator = create(); coordinator.start(); coordinator.close();
        assertThat(coordinator.isRunning()).isFalse(); assertThat(opens).hasValue(1);
        assertThat(resources.launches).hasValue(0); assertThat(resources.closed).isTrue();
        assertThat(row(build)).isEqualTo(before); assertThat(row(waiting)).isEqualTo(queued);
        try (var next = new ExecutionCoordinatorLease(database)) { assertThat(next.healthy()).isTrue(); }
    }

    @Test void unavailableExecutionRootCannotSettleOldWorkOrStartNewWork() {
        var build = enqueue();
        new ExecutionBuildClaim(settings, projects, builds, jdbc, transactions).claim(owner, build.projectId(), build.id());
        var before = row(build);
        coordinator = new ExecutionCoordinator(settings, builds, jdbc, transactions, pool, sourceReady::get,
                () -> { opens.incrementAndGet(); throw new java.io.IOException("EXECUTION_STORAGE_UNAVAILABLE"); });
        coordinator.start(); coordinator.close();
        assertThat(coordinator.isRunning()).isFalse(); assertThat(opens).hasValue(1);
        assertThat(row(build)).isEqualTo(before);
    }

    @Test void losingOwnDatabaseLeaseStopsTheChildButDoesNotCancelUnclaimedWork() throws Exception {
        var active = enqueue(); var waiting = enqueue(); start(); await(() -> Files.exists(marker(active)));
        var before = row(waiting);
        try (var sql = connection.createStatement()) { sql.execute("SELECT pg_advisory_unlock(718395204)"); }
        await(() -> resources.closed);
        assertThat(coordinator.isRunning()).isFalse(); assertThat(terminal(active)).isTrue();
        assertThat(row(waiting)).isEqualTo(before); assertThat(marker(waiting)).doesNotExist();
    }

    @Test void disablingOrLosingSourceReadinessStillStopsOwnedProcesses() throws Exception {
        var active = enqueue(); var waiting = enqueue(); start(); await(() -> Files.exists(marker(active)));
        config.withProperty("atoms.project-execution.enabled", "false"); sourceReady.set(false);
        await(() -> resources.closed);
        assertThat(terminal(active)).isTrue(); assertThat(terminal(waiting)).isTrue();
        assertThat(row(active).get("cancel_requested")).isEqualTo(true);
    }

    @Test void lostCompletionRetainsResourcesUntilTheOriginalStopReceiptIsRecorded() throws Exception {
        var active = enqueue(); start(); await(() -> Files.exists(marker(active)));
        resources.loseResult = true;
        Files.writeString(marker(active).resolveSibling("release"), "release");
        await(() -> !coordinator.isRunning() && resources.stopped.containsKey(active.id()));
        assertThatThrownBy(coordinator::close).hasMessageContaining("构建调度");
        assertThat(row(active).get("execution_pending")).isEqualTo(true);
        assertThat(resources.closed).isFalse();
        assertThatThrownBy(() -> new ExecutionPaths(settings.load().root(), List.of())).isInstanceOf(java.io.IOException.class);
        resources.recordHeldStop(active);
        coordinator.close(); assertThat(resources.closed).isTrue();
        assertThat(terminal(active)).isTrue();
    }

    private ExecutionCoordinator create() {
        return new ExecutionCoordinator(settings, builds, jdbc, transactions, pool, sourceReady::get, () -> {
            opens.incrementAndGet();
            resources = new ExecutionSchedulerFixture(settings.load().root(), settings, projects, jdbc, transactions);
            return resources;
        });
    }
    private void start() { coordinator = create(); coordinator.start(); assertThat(coordinator.isRunning()).isTrue(); }
    private ExecutionBuild enqueue() {
        UUID project = UUID.randomUUID(), version = UUID.randomUUID(); String hash = "a".repeat(64);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'scheduler-fixture')", project, owner);
        jdbc.update("INSERT INTO project_sources(project_id) VALUES (?)", project);
        jdbc.update("INSERT INTO source_versions(id,project_id,version_no,commit_id,tree_sha256,file_count,tree_bytes) VALUES (?,?,1,?,?,1,1)",
                version, project, "a".repeat(40), hash);
        var build = new TransactionTemplate(transactions).execute(tx -> {
            projects.lock(owner, project.toString());
            return builds.insert(owner, project, ExecutionBuild.request(version, UUID.randomUUID().toString()), hash, settings.load());
        });
        fixtures.add(build); return build;
    }
    private Path marker(ExecutionBuild b) { return Path.of(config.getProperty("atoms.project-execution.root")).resolve("builds/" + b.id() + "/project/frontend/started"); }
    private Map<String, Object> row(ExecutionBuild b) { return jdbc.queryForMap("SELECT * FROM execution_builds WHERE id=?", b.id()); }
    private boolean terminal(ExecutionBuild b) { return builds.get(b.projectId(), b.id()).finishedAt() != null; }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).as("Expected scheduler transition within its bounded test wait").isTrue();
    }
}
