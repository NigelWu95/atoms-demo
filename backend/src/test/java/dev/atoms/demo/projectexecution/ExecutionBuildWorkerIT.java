package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real PG/Git/owned children, but controlled tool output: not EX11 npm/Maven acceptance. */
@Tag("project-execution-contract")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false",
        "atoms.generation.worker-enabled=false"})
class ExecutionBuildWorkerIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID();
    final List<ExecutionBuild> builds = new ArrayList<>();
    final List<String> executed = new ArrayList<>();
    final Map<UUID, OwnedProcess.Stopped> proofs = new HashMap<>();
    final AtomicLong clock = new AtomicLong();
    final AtomicBoolean cancel = new AtomicBoolean();
    final AtomicBoolean coordinated = new AtomicBoolean(true);
    SourceAgentFixture source;
    SourceAgentFixture.Base base;
    ExecutionPaths paths;
    ExecutionTestDatabase databases;
    MockEnvironment config;
    Path root, policy;
    String mode = "success";
    ExecutionProcess.Deadline shared;
    OwnedProcess.Preflight afterFirstProbe = () -> {};

    @BeforeEach void setup() throws Exception {
        root = temporary.toRealPath();
        source = new SourceAgentFixture(jdbc, projects, transactions, root);
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        var initial = source.create(owner);
        var files = new ArrayList<SourceTree.File>();
        for (String name : Files.readAllLines(Path.of("../templates/full-project-v1.files"))) {
            // The platform supplies its private npmrc. Project-authored npmrc remains forbidden.
            if (name.equals("frontend/.npmrc")) continue;
            files.add(new SourceTree.File(name, Files.readAllBytes(Path.of("../templates/full-project-v1").resolve(name)),
                    name.equals("backend/mvnw") ? 0100755 : 0100644));
        }
        base = source.accept(owner, initial, new ProjectSourcePolicy().validate(files));
        paths = new ExecutionPaths(root.resolve("execution"), protectedRoots());
        var tools = ExecutionToolchainTest.installation(root.resolve("tools"));
        policy = root.resolve("approval.json");
        Files.copy(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json"), policy);
        Files.setPosixFilePermissions(policy, PosixFilePermissions.fromString("rw-------"));
        config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString())
                .withProperty("atoms.project-execution.root", paths.checkedRoot().toString())
                .withProperty("atoms.project-execution.policy", policy.toString());
        for (var entry : Map.of("node", tools.node(), "npm", tools.npm(), "java", tools.java(), "maven", tools.maven()).entrySet())
            config.withProperty("atoms.project-execution." + entry.getKey(), entry.getValue().toString());
        String directory = System.getProperty("execution.test-config-directory");
        assertThat(directory).as("Explicit existing private test DB config required").isNotBlank();
        databases = ExecutionTestDatabase.load(Map.of(base.projectId(), Path.of(directory).resolve("atoms_exec_a_test_20261020.properties")),
                protectedRoots(), Set.of("atoms_demo", "atoms_demo_test"), Set.of("atoms", "atoms_test"));
    }

    @AfterEach void close() throws Exception {
        try {
            for (var build : builds) {
                var state = row(build);
                if (Boolean.TRUE.equals(state.get("execution_pending"))) {
                    var last = jdbc.queryForMap("""
                            SELECT payload->>'executionId' AS id,payload->>'operation' AS operation,payload->>'probe' AS probe
                            FROM execution_build_events WHERE build_id=? AND payload->>'executionId' IS NOT NULL ORDER BY seq DESC LIMIT 1
                            """, build.id());
                    Path task = root.resolve("execution/builds/" + build.id());
                    Path cwd = last.get("probe") != null ? task.resolve("tool-home")
                            : task.resolve("project/" + ("MAVEN_PACKAGE".equals(last.get("operation")) ? "backend" : "frontend"));
                    assertThat(proofs.get(build.id())).as("Only observed stopped fixture children may be settled").isNotNull();
                    assertThat(proofs.get(build.id()).matches(build.id(), UUID.fromString((String) last.get("id")), cwd)).isTrue();
                    jdbc.update("UPDATE execution_builds SET execution_pending=false WHERE id=?", build.id());
                }
                jdbc.update("""
                        UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,queue_slot=null,worker_slot=null,finished_at=now()
                        WHERE id=? AND finished_at IS NULL AND NOT execution_pending
                        """, build.id());
            }
        } finally { try { if (paths != null) paths.close(); } finally { if (source != null) source.close(); } }
    }

    @Test void runsFrozenVersionThroughAllDurablePhasesAndSealsBeforeSuccess() throws Exception {
        var build = queued();
        var previous = source.snapshot(owner, base.projectId(), base.versionId());
        var files = new ArrayList<>(previous.files());
        files.add(new SourceTree.File("later.txt", "not this build".getBytes(), 0100644));
        var later = source.accept(owner, base, new ProjectSourcePolicy().validate(files));
        var database = databases.verify(base.projectId(), deadline());
        var result = run(build, jdbc);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.sourceVersionId()).isEqualTo(base.versionId()).isNotEqualTo(later.versionId());
        assertThat(executed).containsExactly("NODE", "NPM", "JAVA", "MAVEN", "NPM_CI", "TYPECHECK", "LINT", "UNIT_TEST", "FRONTEND_BUILD", "MAVEN_PACKAGE");
        var state = row(build);
        assertThat(state.get("worker_slot")).isNull(); assertThat(state.get("execution_pending")).isEqualTo(false);
        assertThat(state.get("jar_sha256").toString()).matches("[a-f0-9]{64}");
        assertThat(root.resolve("execution/artifacts/" + build.id() + "/app.jar")).exists();
        assertThat(root.resolve("execution/builds/" + build.id() + "/project/later.txt")).doesNotExist();
        assertThat(source.snapshot(owner, base.projectId(), base.versionId()).sha256()).isEqualTo(previous.sha256());
        assertThat(databases.verify(base.projectId(), deadline())).isEqualTo(database);
        String events = jdbc.queryForList("SELECT payload::text FROM execution_build_events WHERE build_id=? ORDER BY seq", String.class, build.id()).toString();
        assertThat(events).doesNotContain("CANARY", root.toString(), "APP_DB_PASSWORD", "jdbc:");
        assertThatThrownBy(() -> run(build, jdbc)).hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
        assertThat(row(build)).isEqualTo(state);
        assertThat(executed).hasSize(10);
    }

    @Test void failedNewBuildKeepsOldArtifactAndNeverReachesJava() throws Exception {
        var first = queued(); run(first, jdbc); var saved = row(first);
        executed.clear(); shared = null; mode = "lint-failure";
        var second = queued(); var result = run(second, jdbc);
        assertThat(result.status()).isEqualTo("FAILED"); assertThat(result.errorCode()).isEqualTo("EXECUTION_PROCESS_FAILED");
        assertThat(executed).endsWith("LINT").doesNotContain("UNIT_TEST", "MAVEN_PACKAGE");
        assertThat(row(first)).isEqualTo(saved);
        assertThat(row(second).get("jar_sha256")).isNull();
        assertThat(row(second).get("worker_slot")).isNull();
    }

    @Test void sharedDeadlineCannotRestartAfterProbes() throws Exception {
        config.withProperty("atoms.project-execution.task-timeout-seconds", "60");
        var build = queued(); mode = "expire-after-probes";
        config.withProperty("atoms.project-execution.task-timeout-seconds", "600");
        var result = run(build, jdbc);
        assertThat(result.status()).isEqualTo("TIMED_OUT");
        assertThat(executed).containsExactly("NODE", "NPM", "JAVA", "MAVEN");
        assertThat(row(build).get("worker_slot")).isNull();
    }

    @Test void cancellationAndPolicyDriftAfterProbePreventInstall() throws Exception {
        for (String scenario : List.of("cancel-after-probes", "policy-drift")) {
            mode = scenario; var build = queued();
            var result = run(build, jdbc);
            assertThat(result.status()).isEqualTo(scenario.startsWith("cancel") ? "CANCELLED" : "FAILED");
            assertThat(executed).containsExactly("NODE", "NPM", "JAVA", "MAVEN");
            assertThat(row(build).get("worker_slot")).isNull();
            executed.clear(); shared = null; cancel.set(false);
        }
    }

    @Test void lostProbeCompletionIsNotRetriedOrConvertedToTerminalFailure() throws Exception {
        var build = queued(); var writes = new AtomicInteger();
        var failing = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public int update(String sql, Object... args) {
                int count = super.update(sql, args);
                if (sql.contains("SET execution_pending=false") && writes.incrementAndGet() == 1)
                    throw new IllegalStateException("PRIVATE_SQL_CANARY");
                return count;
            }
        };
        assertThatThrownBy(() -> run(build, failing)).hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
        assertThat(writes).hasValue(1); assertThat(executed).containsExactly("NODE");
        assertThat(row(build).get("execution_pending")).isEqualTo(true);
        assertThat(row(build).get("worker_slot")).isEqualTo(1);
        assertThat(row(build).get("finished_at")).isNull();
    }

    @Test void noAuthorityWrongRootAndAmbientTransactionCannotClaimOrAllocate() throws Exception {
        var build = queued(); var before = row(build);
        for (String scenario : List.of("disabled", "root", "coordination")) {
            config.withProperty("atoms.project-execution.enabled", scenario.equals("disabled") ? "false" : "true");
            config.withProperty("atoms.project-execution.root", root.resolve(scenario.equals("root") ? "other" : "execution").toString());
            coordinated.set(!scenario.equals("coordination"));
            assertThatThrownBy(() -> run(build, jdbc)).as(scenario).hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
            assertThat(row(build)).isEqualTo(before);
        }
        coordinated.set(true);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                assertThatThrownBy(() -> run(build, jdbc)).hasMessage("EXECUTION_TRANSACTION_ACTIVE"));
        assertThat(executed).isEmpty();
        assertThat(root.resolve("execution/builds/" + build.id())).doesNotExist();
    }

    @Test void launchTimeControlChangeRefusesNextStageAndRetainsStoppedEvidence() throws Exception {
        mode = "configuration-drift"; var build = queued();
        var result = run(build, jdbc);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("EXECUTION_PREFLIGHT_FAILED");
        assertThat(executed).containsExactly("NODE");
        assertThat(row(build).get("execution_pending")).isEqualTo(false);
        assertThat(row(build).get("worker_slot")).isNull();
    }

    @Test void lostStopReceiptKeepsIntentAndCapacityWithoutAnotherLaunch() throws Exception {
        mode = "lost-receipt"; var build = queued();
        var result = run(build, jdbc);
        assertThat(result.status()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(result.errorCode()).isEqualTo("EXECUTION_PROCESS_UNSETTLED");
        assertThat(executed).containsExactly("NODE");
        assertThat(row(build).get("execution_pending")).isEqualTo(true);
        assertThat(row(build).get("worker_slot")).isEqualTo(1);
        assertThat(row(build).get("finished_at")).isNull();
    }

    ExecutionBuild queued() {
        return queued(source.snapshot(owner, base.projectId(), base.versionId()).sha256());
    }
    ExecutionBuild queued(String hash) {
        var repository = new ExecutionBuildRepository(jdbc);
        var build = new TransactionTemplate(transactions).execute(tx -> repository.insert(owner, base.projectId(),
                ExecutionBuild.request(base.versionId(), UUID.randomUUID().toString()), hash, new ExecutionSettings(config).load()));
        builds.add(build); return build;
    }
    ExecutionBuild run(ExecutionBuild build, JdbcTemplate database) throws IOException {
        return new ExecutionBuildWorker(new ExecutionSettings(config), projects, database, transactions, paths,
                source.readGateway(), databases, protectedRoots(), new ControlledTools(), clock::get)
                .run(owner, base.projectId(), build.id(), cancel::get, () -> {
                    if (!coordinated.get()) throw new IOException("EXECUTION_STATE_UNAVAILABLE");
                });
    }
    private List<Path> protectedRoots() { return List.of(root.resolve("source"), root.resolve("work")); }
    Map<String, Object> row(ExecutionBuild build) { return jdbc.queryForMap("SELECT * FROM execution_builds WHERE id=?", build.id()); }
    private ExecutionProcess.Deadline deadline() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }

    private final class ControlledTools implements ExecutionBuildWorker.Tools {
        @Override public OwnedProcess.Result<ExecutionToolProbe.Version> probe(ExecutionToolchain tools, ExecutionToolchain.Kind kind,
                ExecutionToolHome home, ExecutionProcess.Deadline deadline, BooleanSupplier cancelled,
                UUID build, UUID execution, OwnedProcess.Preflight preflight) throws IOException {
            assertPending(build, execution); sameDeadline(deadline); executed.add(kind.name());
            var result = child("noop", home.home(), home.home(), home.temporary(), deadline, cancelled, build, execution, preflight, null);
            if (kind == ExecutionToolchain.Kind.NODE) afterFirstProbe.check();
            if (mode.equals("configuration-drift"))
                config.withProperty("atoms.project-execution.max-concurrent-builds", "2");
            if (kind == ExecutionToolchain.Kind.MAVEN) {
                if (mode.equals("expire-after-probes")) clock.set(Duration.ofSeconds(60).toNanos());
                if (mode.equals("cancel-after-probes")) cancel.set(true);
                if (mode.equals("policy-drift")) Files.writeString(policy, "changed PRIVATE_POLICY_CANARY");
            }
            String version = switch (kind) { case NODE -> "24.20.0"; case NPM -> "11.19.0"; case JAVA -> "17.0.14"; case MAVEN -> "3.9.11"; };
            return new OwnedProcess.Result<>(new ExecutionToolProbe.Version(kind, version, tools.identity(kind)),
                    mode.equals("lost-receipt") ? null : result.stopped());
        }
        @Override public OwnedProcess.Result<ExecutionProcess.Output> command(ExecutionProcess.Invocation invocation,
                ExecutionProcess.Deadline deadline, BooleanSupplier cancelled, UUID build, UUID execution,
                OwnedProcess.Preflight preflight, ExecutionTestDatabase.Launch database) throws IOException {
            assertPending(build, execution); sameDeadline(deadline);
            String operation = jdbc.queryForObject("SELECT payload->>'operation' FROM execution_build_events WHERE build_id=? AND payload->>'executionId'=? ORDER BY seq DESC LIMIT 1",
                    String.class, build, execution.toString());
            executed.add(operation);
            var builder = invocation.builder();
            assertThat(builder.environment()).doesNotContainKeys("DEEPSEEK_API_KEY", "SMTP_PASSWORD", "APP_DB_PASSWORD");
            assertThat(database != null).isEqualTo(operation.equals("MAVEN_PACKAGE"));
            String action = mode.equals("lint-failure") && operation.equals("LINT") ? "fail" : operation;
            return child(action, builder.directory().toPath(), Path.of(builder.environment().get("HOME")),
                    Path.of(builder.environment().get("TMPDIR")), deadline, cancelled, build, execution, preflight, database);
        }
        private void sameDeadline(ExecutionProcess.Deadline value) {
            if (shared == null) shared = value;
            assertThat(value).isSameAs(shared);
        }
        private void assertPending(UUID build, UUID execution) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT execution_pending FROM execution_builds WHERE id=?", Boolean.class, build)).isTrue();
            assertThat(jdbc.queryForObject("SELECT payload->>'executionId' FROM execution_build_events WHERE build_id=? ORDER BY seq DESC LIMIT 1", String.class, build))
                    .isEqualTo(execution.toString());
        }
    }

    private OwnedProcess.Result<ExecutionProcess.Output> child(String action, Path cwd, Path home, Path temp,
            ExecutionProcess.Deadline deadline, BooleanSupplier cancelled, UUID build, UUID execution,
            OwnedProcess.Preflight preflight, ExecutionTestDatabase.Launch database) throws IOException {
        Path java = Path.of(System.getProperty("java.home"));
        var invocation = new ExecutionProcess.Invocation(java.resolve("bin/java"), List.of("-cp",
                Path.of("target/test-classes").toAbsolutePath().toString(), Fixture.class.getName(), action), cwd, home, temp, java, java.resolve("bin"));
        try {
            var result = new ExecutionProcess().run(invocation, deadline, cancelled, build, execution, preflight, database);
            proofs.put(build, result.stopped()); return result;
        } catch (OwnedProcess.Failure failure) { if (failure.stopped() != null) proofs.put(build, failure.stopped()); throw failure; }
    }
    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            System.in.readAllBytes(); System.out.print("PRIVATE_OUTPUT_CANARY");
            switch (args[0]) {
                case "fail" -> System.exit(4);
                case "UNIT_TEST" -> Files.writeString(Path.of("test-results/unit.json"), ExecutionDiagnosticsTest.report());
                case "FRONTEND_BUILD" -> { Files.createDirectory(Path.of("dist")); Files.writeString(Path.of("dist/index.html"), "<h1>controlled frontend</h1>"); }
                case "MAVEN_PACKAGE" -> {
                    if (System.getenv("APP_DB_PASSWORD") == null || !"0".equals(System.getenv("APP_PORT"))) System.exit(5);
                    Files.writeString(Path.of("target/app.jar"), "opaque fixture, not a real JAR");
                    Files.writeString(Path.of("target/surefire-reports/TEST-Example.xml"), ExecutionJavaReportsTest.xml());
                }
                default -> { }
            }
        }
    }
}
