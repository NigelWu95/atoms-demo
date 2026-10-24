package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Node/JVM processes and source publication; the controlled CLI/build fixture is not real npm/Maven acceptance. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionPreparationSchedulerIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired HikariDataSource database;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID();
    SourceAgentFixture source;
    ProjectSourceEditGateway edits;
    MockEnvironment config;
    ExecutionSettings settings;
    DependencyPreparationRepository preparations;
    DependencyPreparationService service;
    ExecutionBuildRepository builds;
    ProjectExecutionService buildService;
    HikariDataSource pool;
    volatile Connection connection;
    ExecutionCoordinator coordinator;
    ExecutionSchedulerFixture buildResources;
    Path policy, markers;
    ExecutionToolchain.Locations tools;
    final AtomicBoolean closed = new AtomicBoolean();
    final List<UUID> launches = new CopyOnWriteArrayList<>();
    final List<DependencyPreparationRepository.Task> tasks = new ArrayList<>();
    final List<ExecutionBuild> buildTasks = new ArrayList<>();
    final Map<UUID, OwnedProcess.Stopped> stops = new ConcurrentHashMap<>();

    @BeforeEach void fixture() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo("atoms_demo_test.atoms_verify_20261024_ex16");
        temporary = temporary.toRealPath();
        source = new SourceAgentFixture(jdbc, projects, transactions, temporary);
        source.start(database); edits = spy(source.gateway());
        doAnswer(call -> {
            var edit = (ProjectSourceEditGateway.EditHandle) call.getArgument(0);
            stops.put(edit.taskId(), call.getArgument(1));
            return call.callRealMethod();
        }).when(edits).confirmExecutionStopped(any(), any());
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        tools = ExecutionToolchainTest.installation(temporary.resolve("tools"));
        Path node = Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator)).map(p -> Path.of(p).resolve("node"))
                .filter(Files::isExecutable).findFirst().orElseThrow().toRealPath();
        Files.delete(tools.node()); Files.createSymbolicLink(tools.node(), node);
        Files.copy(Path.of("../templates/full-project-v1/frontend/package-lock.json"), tools.npm().toRealPath().resolveSibling("fixture-lock.json"));
        markers = Files.createDirectory(temporary.resolve("markers")); cli(true);
        policy = temporary.resolve("policy.json"); var json = new ObjectMapper();
        var approved = (ObjectNode) json.readTree(Files.readAllBytes(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json")));
        ((ObjectNode) approved.path("npm")).remove("lockSha256");
        Files.write(policy, json.writeValueAsBytes(approved));
        Files.setPosixFilePermissions(policy, PosixFilePermissions.fromString("rw-------"));
        config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString())
                .withProperty("atoms.project-execution.root", temporary.resolve("execution").toString())
                .withProperty("atoms.project-execution.node", tools.node().toString())
                .withProperty("atoms.project-execution.npm", tools.npm().toString())
                .withProperty("atoms.project-execution.java", tools.java().toString())
                .withProperty("atoms.project-execution.maven", tools.maven().toString())
                .withProperty("atoms.project-execution.policy", policy.toString());
        settings = new ExecutionSettings(config); preparations = new DependencyPreparationRepository(jdbc);
        service = newService(); builds = new ExecutionBuildRepository(jdbc);
        buildService = new ProjectExecutionService(settings, builds, projects, source.readGateway(), transactions);
        pool = new HikariDataSource() {
            @Override public Connection getConnection() throws java.sql.SQLException { var own = super.getConnection(); connection = own; return own; }
        };
        pool.setJdbcUrl(database.getJdbcUrl()); pool.setUsername(database.getUsername()); pool.setPassword(database.getPassword());
        pool.setMaximumPoolSize(1); pool.setMinimumIdle(0); pool.setConnectionTimeout(3000);
        pool.setPoolName("preparation-scheduler-fixture");
    }

    @AfterEach void close() throws Exception {
        RuntimeException failure = null;
        try {
            if (coordinator != null) try { coordinator.close(); } catch (RuntimeException error) { failure = error; }
            for (var task : tasks) if (!terminal(task)) {
                var edit = service.handle(task.id()); var stop = stops.get(task.id());
                // Cleanup only this fixture's known never-started work or its retained original receipt.
                if (stop != null) edits.confirmExecutionStopped(edit, stop);
                assertThat(jdbc.queryForObject("SELECT execution_pending FROM source_edit_leases WHERE id=?", Boolean.class, task.editLeaseId())).isFalse();
                jdbc.update("UPDATE dependency_preparations SET execution_pending=false WHERE id=?", task.id());
                var operation = edits.operationFact(edit);
                if (operation != null && operation.status().equals("COMMITTED"))
                    new DependencyPreparationLifecycle(settings, projects, jdbc, transactions, preparations, service, edits, task, edit).settle(null);
                else {
                    edits.releaseSettledEdit(edit); // Refuses an unfinished publication; never force-clear it.
                    jdbc.update("UPDATE dependency_preparations SET status='CANCELLED',cancel_requested=true,queue_slot=null,worker_slot=null,finished_at=now() WHERE id=?", task.id());
                }
            }
            for (var build : buildTasks) {
                if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT execution_pending FROM execution_builds WHERE id=?", Boolean.class, build.id()))) {
                    assertThat(buildResources).isNotNull(); buildResources.recordHeldStop(build);
                }
                jdbc.update("UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,queue_slot=null,worker_slot=null,finished_at=now() WHERE id=? AND finished_at IS NULL AND NOT execution_pending", build.id());
            }
            if (coordinator != null) coordinator.close();
            for (var task : tasks) if (Files.exists(marker(task))) {
                long pid = Long.parseLong(Files.readString(marker(task)));
                assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
                assertThat(stops).containsKey(task.id());
            }
            if (failure != null) throw failure; // Known-stop cleanup does not turn a broken shutdown into a passing test.
        } finally {
            try { if (pool != null) pool.close(); } finally { if (source != null) source.close(); }
        }
    }

    @Test void nativeSpringFactoryDispatchesTwoRoundsAndRejectsAnObsoleteBaseline() throws Exception {
        cli(false); var base = base(); var first = submit(base);
        var fixture = new ExecutionTestDatabaseTest(); fixture.temporary = temporary;
        config.withProperty("atoms.project-execution.test-databases", new ObjectMapper().writeValueAsString(
                Map.of(base.projectId().toString(), fixture.config(Map.of()).toString())));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(config);
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(PlatformTransactionManager.class, () -> transactions);
            context.registerBean(DataSource.class, () -> pool, d -> d.setDestroyMethodName(""));
            context.registerBean(ProjectSourceReadGateway.class, source::readGateway);
            context.registerBean(ProjectSourceEditGateway.class, () -> edits);
            context.registerBean(DependencyPreparationService.class, () -> service);
            context.registerBean(ExecutionEnvironment.class, () -> new ExecutionEnvironment(settings, config, source.readGateway(),
                    source.environment(), projects, jdbc, transactions, edits, service));
            context.register(ExecutionSettings.class, ExecutionBuildRepository.class, ExecutionCoordinator.class);
            context.refresh(); coordinator = context.getBean(ExecutionCoordinator.class);
            await(() -> terminal(first)); var result = preparations.get(first.projectId(), first.id());
            assertThat(result.status()).isEqualTo("SOURCE_SAVED"); assertThat(coordinator.isRunning()).isTrue();
            assertThat(source.changedPaths(owner, base.projectId(), base.versionId(), result.resultVersionId())).containsOnlyKeys("frontend/package-lock.json");
            assertThatThrownBy(() -> service.submit(owner, base.projectId(),
                    new DependencyPreparationService.Input(base.versionId(), base.revision()), "stale"))
                    .isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class, e -> assertThat(e.code()).isEqualTo("SOURCE_BASE_CONFLICT"));
            Files.write(policy, Files.readAllBytes(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json")));
            var second = submit(new SourceAgentFixture.Base(base.projectId(), result.resultVersionId(), base.revision() + 1));
            await(() -> terminal(second)); assertThat(preparations.get(second.projectId(), second.id()).status()).isEqualTo("NO_CHANGES");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(3);
            assertReleased(first); assertReleased(second);
        }
        try (var root = new ExecutionPaths(settings.load().root(), protectedRoots()); var lease = new ExecutionCoordinatorLease(database)) {
            assertThat(root.checkedRoot()).isEqualTo(settings.load().root()); assertThat(lease.healthy()).isTrue();
        }
    }

    @Test void oneWorkerPreservesFifoAcrossBuildAndPreparationQueues() throws Exception {
        var first = build(); var second = submit(base()); var third = build(); start();
        await(() -> Files.exists(buildMarker(first)));
        assertThat(launches).containsExactly(first.id());
        buildService.cancel(owner, first.projectId(), first.id());
        await(() -> Files.exists(marker(second)));
        assertThat(Files.exists(buildMarker(third))).isFalse();
        assertThat(launches).containsExactly(first.id(), second.id());
        Files.writeString(markers.resolve(second.id() + ".release"), "release");
        await(() -> terminal(second) && Files.exists(buildMarker(third)));
        assertThat(preparations.get(second.projectId(), second.id()).status()).isEqualTo("SOURCE_SAVED");
        assertThat(launches).containsExactly(first.id(), second.id(), third.id());
        coordinator.close(); assertThat(closed).isTrue(); assertReleased(second);
    }

    @Test void fourSharedWorkersDoNotDispatchAFifthUntilOneActuallyStops() throws Exception {
        config.withProperty("atoms.project-execution.max-concurrent-builds", "4");
        var a = build(); var b = submit(base()); var c = build(); var d = submit(base()); var fifth = submit(base());
        start(); await(() -> Files.exists(buildMarker(a)) && Files.exists(marker(b)) && Files.exists(buildMarker(c)) && Files.exists(marker(d)));
        assertThat(launches).hasSize(4); assertThat(marker(fifth)).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE worker_slot IS NOT NULL", Integer.class)).isEqualTo(4);
        service.cancel(owner, b.projectId(), b.id());
        await(() -> terminal(b) && Files.exists(marker(fifth)));
        assertThat(launches).hasSize(5); assertThat(preparations.get(d.projectId(), d.id()).executionPending()).isTrue();
        coordinator.close(); assertThat(closed).isTrue();
        for (var task : tasks) { assertThat(preparations.get(task.projectId(), task.id()).status()).isEqualTo("CANCELLED"); assertReleased(task); }
    }

    @Test void queueExpiryStillSettlesWhileAPreparationOccupiesTheOnlyWorker() throws Exception {
        var active = submit(base()); start(); await(() -> Files.exists(marker(active)));
        config.withProperty("atoms.project-execution.queue-timeout-seconds", "1");
        var expired = submit(base()); await(() -> terminal(expired));
        assertThat(preparations.get(expired.projectId(), expired.id()).status()).isEqualTo("TIMED_OUT");
        assertThat(marker(expired)).doesNotExist(); assertThat(launches).containsExactly(active.id());
        coordinator.close(); assertReleased(active); assertReleased(expired);
    }

    @Test void startupRecoversLostQueueWithoutCallingItsWorkerThenStartsFreshWork() throws Exception {
        var abandoned = submit(base());
        var restarted = newService(); service = restarted; // Deliberately lose only the service's capability map.
        var fresh = submit(base()); start();
        assertThat(preparations.get(abandoned.projectId(), abandoned.id()).status()).isEqualTo("FAILED");
        await(() -> Files.exists(marker(fresh)));
        assertThat(launches).containsExactly(fresh.id()); assertThat(marker(abandoned)).doesNotExist();
        coordinator.close(); assertReleased(abandoned); assertReleased(fresh);
    }

    @Test void lossOfTheOwnedLeaseStopsItsChildAndLeavesUnclaimedWorkUntouched() throws Exception {
        var active = submit(base()); var waiting = submit(base()); start(); await(() -> Files.exists(marker(active)));
        var before = preparations.get(waiting.projectId(), waiting.id());
        try (var sql = connection.createStatement()) { sql.execute("SELECT pg_advisory_unlock(718395204)"); }
        await(closed::get);
        assertThat(coordinator.isRunning()).isFalse(); assertThat(terminal(active)).isTrue();
        assertThat(preparations.get(waiting.projectId(), waiting.id())).isEqualTo(before);
        assertThat(marker(waiting)).doesNotExist(); assertReleased(active);
    }

    @Test void disablingExecutionStopsItsRealPreparationAndCancelsItsOwnedQueue() throws Exception {
        var active = submit(base()); var waiting = submit(base()); start(); await(() -> Files.exists(marker(active)));
        config.withProperty("atoms.project-execution.enabled", "false");
        await(closed::get);
        assertThat(coordinator.isRunning()).isFalse(); assertThat(marker(waiting)).doesNotExist();
        for (var task : tasks) {
            var result = preparations.get(task.projectId(), task.id());
            assertThat(result.status()).isEqualTo("CANCELLED"); assertThat(result.cancelRequested()).isTrue(); assertReleased(task);
        }
    }

    @Test void unresolvedPreparationBlocksStartupWithoutDispatchingOrChangingTheFreshQueue() throws Exception {
        var unknown = submit(base());
        new DependencyPreparationClaim(settings, projects, preparations, builds, edits, jdbc, transactions)
                .claim(owner, unknown.projectId(), unknown.id(), service.handle(unknown.id()));
        jdbc.update("UPDATE dependency_preparations SET status='NEEDS_RECONCILIATION',error_code='EXECUTION_RECOVERY_UNCERTAIN' WHERE id=?", unknown.id());
        var waiting = submit(base()); var before = preparations.get(unknown.projectId(), unknown.id());
        coordinator = createCoordinator(); coordinator.start();
        assertThat(coordinator.isRunning()).isFalse(); assertThat(closed).isTrue(); assertThat(launches).isEmpty();
        assertThat(preparations.get(unknown.projectId(), unknown.id())).isEqualTo(before);
        assertThat(preparations.get(waiting.projectId(), waiting.id())).isEqualTo(waiting);
    }

    private void start() {
        coordinator = createCoordinator(); coordinator.start(); assertThat(coordinator.isRunning()).isTrue();
    }
    private ExecutionCoordinator createCoordinator() {
        return new ExecutionCoordinator(settings, builds, jdbc, transactions, pool, source.readGateway()::readyForExecution,
                () -> {
                    buildResources = new ExecutionSchedulerFixture(settings.load().root(), settings, projects, jdbc, transactions);
                    var worker = new DependencyPreparationWorker(settings, projects, jdbc, transactions, buildResources.paths,
                            source.readGateway(), edits, service, protectedRoots());
                    return new ExecutionCoordinator.WorkerResources() {
                        public void check() throws IOException { buildResources.check(); }
                        public ExecutionBuild run(ExecutionBuild build, BooleanSupplier cancel, OwnedProcess.Preflight control) throws IOException {
                            launches.add(build.id()); return buildResources.run(build, cancel, control);
                        }
                        public DependencyPreparationRepository.Task runPreparation(DependencyPreparationRepository.Task task,
                                BooleanSupplier cancel, OwnedProcess.Preflight control) throws IOException {
                            launches.add(task.id()); return worker.run(task.ownerId(), task.projectId(), task.id(), cancel, control);
                        }
                        public void close() throws IOException { buildResources.close(); closed.set(true); }
                    };
                }, service, edits);
    }
    private DependencyPreparationService newService() {
        return new DependencyPreparationService(settings, preparations, projects, source.readGateway(), edits, source.environment(), config, transactions);
    }
    private SourceAgentFixture.Base base() throws Exception {
        var initial = source.create(owner); var files = new ArrayList<SourceTree.File>();
        for (String name : Files.readAllLines(Path.of("../templates/full-project-v1.files")))
            if (!name.equals("frontend/package-lock.json")) files.add(new SourceTree.File(name,
                    Files.readAllBytes(Path.of("../templates/full-project-v1").resolve(name)), name.equals("backend/mvnw") ? 0100755 : 0100644));
        return source.accept(owner, initial, new ProjectSourcePolicy().validate(files));
    }
    private DependencyPreparationRepository.Task submit(SourceAgentFixture.Base base) {
        var result = service.submit(owner, base.projectId(), new DependencyPreparationService.Input(base.versionId(), base.revision()), UUID.randomUUID().toString());
        var task = preparations.get(base.projectId(), result.id()); tasks.add(task); return task;
    }
    private ExecutionBuild build() {
        var base = source.create(owner); var result = buildService.submit(owner, base.projectId(), base.versionId(), UUID.randomUUID().toString());
        var build = builds.get(base.projectId(), result.id()); buildTasks.add(build); return build;
    }
    private List<Path> protectedRoots() { return List.of(temporary.resolve("source"), temporary.resolve("work")); }
    private Path marker(DependencyPreparationRepository.Task task) { return markers.resolve(task.id() + ".started"); }
    private Path buildMarker(ExecutionBuild build) { return settings.load().root().resolve("builds/" + build.id() + "/project/frontend/started"); }
    private boolean terminal(DependencyPreparationRepository.Task task) { return preparations.get(task.projectId(), task.id()).finishedAt() != null; }
    private void assertReleased(DependencyPreparationRepository.Task task) {
        assertThat(service.handle(task.id())).isNull();
        assertThat(jdbc.queryForMap("SELECT status,execution_pending FROM source_edit_leases WHERE id=?", task.editLeaseId()))
                .containsEntry("status", "RELEASED").containsEntry("execution_pending", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE task_id=?", Integer.class, task.id())).isZero();
    }
    private void cli(boolean block) throws Exception {
        Files.writeString(tools.npm().toRealPath(), """
                const fs=require('node:fs'), path=require('node:path');
                if(process.argv.includes('--version')) console.log('11.19.0');
                else {
                  const frontend=process.argv[process.argv.indexOf('--prefix')+1];
                  fs.copyFileSync(path.join(__dirname,'fixture-lock.json'),frontend+'/package-lock.json');
                  const task=path.basename(path.dirname(process.cwd()));
                """ + "const marker=path.join(" + new ObjectMapper().writeValueAsString(markers.toString()) + ",task);\n"
                + "fs.writeFileSync(marker+'.started',String(process.pid));\n"
                + (block ? "const timer=setInterval(()=>{if(fs.existsSync(marker+'.release'))clearInterval(timer);},25);\n" : "") + "}\n");
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).as("Expected owned execution progress within 60 seconds").isTrue();
    }
}
