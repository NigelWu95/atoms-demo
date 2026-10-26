package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Git/PG, sealed fixture bytes and owned HTTP JVM; not an npm/Maven application acceptance claim. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionAccessIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource database;
    @Autowired ProjectExecutionAccess disabled;
    private static final UUID OWNER = ExecutionBindingContractIT.OWNER, A = ExecutionBindingContractIT.A;
    private Path root, parent;
    private SourceAgentFixture source;
    private ExecutionCoordinator coordinator;
    private ExecutionCoordinator.WorkerResources resources;
    private ExecutionSettings settings;
    private MockEnvironment environment;
    private ExecutionPaths paths;
    private ExecutionToolchain tools;

    @BeforeEach void setup() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class)).isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
        assertThat(new ExecutionRunRepository(jdbc).unfinished()).isEmpty();
        root = required("execution.runtime-fixture-root"); parent = required("execution.contract-directory");
        source = new SourceAgentFixture(jdbc, projects, transactions, root); source.start(database);
        tools = ExecutionToolchain.capture(ExecutionToolchainTest.installation(parent.resolve("access-tools-" + UUID.randomUUID())), List.of(root), budget());
        environment = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", OWNER.toString());
        for (String name : List.of("root", "node", "npm", "java", "maven", "policy"))
            environment.withProperty("atoms.project-execution." + name, parent.resolve("access-" + name + "-" + UUID.randomUUID()).toString());
        environment.withProperty("atoms.project-execution.java", tools.javaHome().resolve("bin/java").toString());
        settings = new ExecutionSettings(environment);
    }
    @AfterEach void close() throws Exception {
        try { if (coordinator != null) coordinator.close(); else if (resources != null) resources.close(); }
        finally { if (source != null) source.close(); }
    }

    @Test void sourceRemainsPinnedAcrossRealVersionRaceAndFileReplacementRevokesOldHandle() throws Exception {
        var base = source.create(OWNER); var tree = source.snapshot(OWNER, base.projectId(), base.versionId());
        buildResources(); var build = ExecutionRuntimeFixture.seal(paths, tools, jdbc, OWNER, base, tree); start();
        var read = source.readGateway(); var gateway = access(read);
        var handle = gateway.build(OWNER, base.projectId(), build.id());
        assertThat(gateway.identity(OWNER, base.projectId(), handle).sourceVersionId()).isEqualTo(base.versionId());
        var changed = new ArrayList<>(tree.files());
        changed.add(new SourceTree.File("NOTES.md", "version two".getBytes(StandardCharsets.UTF_8), 0100644));
        var nextTree = new ProjectSourcePolicy().validate(changed); var next = new AtomicReference<SourceAgentFixture.Base>();
        var racingRead = mock(ProjectSourceReadGateway.class, org.mockito.AdditionalAnswers.delegatesTo(read));
        doAnswer(call -> {
            var snapshot = read.read(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
            if (next.get() == null) next.set(source.accept(OWNER, base, nextTree));
            return snapshot;
        }).when(racingRead).read(eq(OWNER), eq(base.projectId()), eq(base.versionId()), any(SourceExecutionBudget.class));
        var racing = access(racingRead); var pinned = racing.build(OWNER, base.projectId(), build.id());
        assertThat(next.get().versionId()).isNotEqualTo(base.versionId());
        var downloaded = racing.source(OWNER, base.projectId(), pinned);
        assertThat(downloaded.version().id()).isEqualTo(base.versionId()); assertThat(downloaded.tree().sha256()).isEqualTo(tree.sha256());
        assertThat(source.snapshot(OWNER, base.projectId(), next.get().versionId()).sha256()).isEqualTo(nextTree.sha256());

        Path jar = settings.load().root().resolve("artifacts/" + build.id() + "/app.jar");
        Path replacement = jar.resolveSibling("replacement.jar");
        Files.copy(jar, replacement, StandardCopyOption.COPY_ATTRIBUTES);
        Files.move(replacement, jar, StandardCopyOption.REPLACE_EXISTING);
        code("EXECUTION_ACCESS_UNAVAILABLE", () -> gateway.source(OWNER, base.projectId(), handle));
        // A fresh authorization can accept the same verified bytes; an old inode-bound handle cannot.
        var refreshed = gateway.build(OWNER, base.projectId(), build.id());
        Files.write(jar, new byte[]{1}, StandardOpenOption.APPEND);
        code("EXECUTION_ACCESS_UNAVAILABLE", () -> gateway.identity(OWNER, base.projectId(), refreshed));
        code("EXECUTION_ACCESS_UNAVAILABLE", () -> gateway.build(OWNER, base.projectId(), build.id()));
    }

    @Test void reauthorizesEachReadIncludingChangesDuringSourceReadAndRejectsForeignHandles() throws Exception {
        UUID other = UUID.randomUUID(); jdbc.update("INSERT INTO guest_users(id) VALUES (?)", other);
        environment.withProperty("atoms.project-execution.maintainer-ids", OWNER + "," + other);
        var base = source.create(OWNER); var foreign = source.create(OWNER);
        buildResources(); var build = ExecutionRuntimeFixture.seal(paths, tools, jdbc, OWNER, base, source.snapshot(OWNER, base.projectId(), base.versionId())); start();
        var read = source.readGateway(); var gateway = access(read); var handle = gateway.build(OWNER, base.projectId(), build.id());
        code("EXECUTION_DISABLED", () -> disabled.build(OWNER, base.projectId(), build.id()));
        code("EXECUTION_ACCESS_FORBIDDEN", () -> gateway.identity(other, base.projectId(), handle));
        code("EXECUTION_ACCESS_FORBIDDEN", () -> gateway.identity(OWNER, foreign.projectId(), handle));
        code("EXECUTION_ACCESS_FORBIDDEN", () -> access(read).identity(OWNER, base.projectId(), handle));
        code("EXECUTION_BUILD_NOT_FOUND", () -> gateway.build(OWNER, foreign.projectId(), build.id()));
        code("EXECUTION_ACCESS_FORBIDDEN", () -> gateway.identity(OWNER, base.projectId(), (ProjectExecutionAccess.BuildHandle) null));
        for (var type : List.of(ProjectExecutionAccess.BuildHandle.class, ProjectExecutionAccess.RunningHandle.class)) {
            assertThat(type.getConstructors()).isEmpty();
            assertThat(Arrays.stream(type.getDeclaredFields()).allMatch(field -> Modifier.isPrivate(field.getModifiers()))).isTrue();
            assertThat(java.io.Serializable.class.isAssignableFrom(type)).isFalse();
        }
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                code("EXECUTION_ACCESS_TRANSACTION_ACTIVE", () -> gateway.identity(OWNER, base.projectId(), handle)));
        try {
            projects.archive(OWNER, base.projectId().toString(), true);
            code("EXECUTION_PROJECT_ARCHIVED", () -> gateway.source(OWNER, base.projectId(), handle));
        } finally { projects.archive(OWNER, base.projectId().toString(), false); }
        try {
            jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", other, base.projectId());
            code("PROJECT_NOT_FOUND", () -> gateway.identity(OWNER, base.projectId(), handle));
            code("EXECUTION_BUILD_OWNER_CHANGED", () -> gateway.build(other, base.projectId(), build.id()));
        } finally { jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", OWNER, base.projectId()); }
        environment.withProperty("atoms.project-execution.queue-capacity", "7");
        code("EXECUTION_ACCESS_REVOKED", () -> gateway.identity(OWNER, base.projectId(), handle));
        environment.withProperty("atoms.project-execution.queue-capacity", "8");
        var action = new AtomicReference<Runnable>(() -> {});
        var changingRead = mock(ProjectSourceReadGateway.class, org.mockito.AdditionalAnswers.delegatesTo(read));
        doAnswer(call -> {
            var snapshot = read.read(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
            action.get().run(); return snapshot;
        }).when(changingRead).read(eq(OWNER), eq(base.projectId()), eq(base.versionId()), any(SourceExecutionBudget.class));
        var changing = access(changingRead); var issued = changing.build(OWNER, base.projectId(), build.id());
        try {
            action.set(() -> projects.archive(OWNER, base.projectId().toString(), true));
            code("EXECUTION_PROJECT_ARCHIVED", () -> changing.source(OWNER, base.projectId(), issued));
        } finally { projects.archive(OWNER, base.projectId().toString(), false); }
        action.set(() -> coordinator.close());
        code("EXECUTION_ACCESS_UNAVAILABLE", () -> changing.source(OWNER, base.projectId(), issued));
        code("EXECUTION_ACCESS_UNAVAILABLE", () -> gateway.identity(OWNER, base.projectId(), handle));
    }

    @Test void runningHandleRequiresOriginalLiveProcessAndStopsWorkingBeforeStopSettlement() throws Exception {
        Path configuration = required("execution.test-config-directory");
        var registry = ExecutionDatabaseBinding.load(Map.of(A, configuration.resolve(ExecutionBindingContractIT.A_DB + ".properties")),
                List.of(root, parent), Set.of("atoms_demo", "atoms_demo_test"), Set.of("postgres", "atoms_test"));
        var target = registry.verify(A, budget()); var history = registry.history(A, budget());
        var tree = ExecutionContractSupport.retainedTree(jdbc, source, A);
        var base = source.initializeExisting(OWNER, A, tree);
        var fixture = new ExecutionRuntimeFixture(settings, projects, jdbc, transactions, source.readGateway(), tools, registry, target);
        resources = fixture; var build = fixture.seal(base, tree); start();
        var gateway = access(source.readGateway());
        var admitted = coordinator.startApplication(OWNER, A, build.id(), target.id(), null, "access-" + UUID.randomUUID())
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        var runs = new ExecutionRunRepository(jdbc); await(() -> runs.get(A, admitted.id()).status().equals("READY"));
        var handle = gateway.running(OWNER, A, admitted.id());
        var identity = gateway.identity(OWNER, A, handle);
        assertThat(identity.runId()).isEqualTo(admitted.id());
        assertThat(identity.executionId()).isEqualTo(runs.get(A, admitted.id()).executionId());
        assertThat(identity.build().buildId()).isEqualTo(build.id());
        assertThat(gateway.source(OWNER, A, handle).version().id()).isEqualTo(base.versionId());
        assertThat(fixture.launches).hasValue(1); assertThat(fixture.process.get().isServing()).isTrue();
        code("EXECUTION_ACCESS_FORBIDDEN", () -> access(source.readGateway()).identity(OWNER, A, handle));
        coordinator.stopApplication(OWNER, A, admitted.id());
        code("EXECUTION_ACCESS_REVOKED", () -> gateway.identity(OWNER, A, handle));
        await(() -> runs.get(A, admitted.id()).finishedAt() != null);
        code("EXECUTION_ACCESS_REVOKED", () -> gateway.running(OWNER, A, admitted.id()));
        assertThat(runs.get(A, admitted.id()).stoppedSha256()).isEqualTo(fixture.process.get().await(Duration.ofSeconds(1)).stopped().digest());
        assertThat(registry.history(A, budget())).isEqualTo(history);
        assertThat(runs.unfinished()).isEmpty();
    }

    private void buildResources() throws Exception {
        paths = new ExecutionPaths(settings.load().root(), List.of(root));
        resources = new ExecutionCoordinator.WorkerResources() {
            public void check() throws IOException { paths.checkedRoot(); }
            public ExecutionBuild run(ExecutionBuild build, BooleanSupplier cancel, OwnedProcess.Preflight control) { throw new AssertionError("No build enqueued"); }
            public ExecutionArtifact artifact(ExecutionBuild build, String jar, String manifest, OwnedProcess.Budget budget) throws IOException {
                return ExecutionArtifact.openSuccessful(paths, build, jar, manifest, budget);
            }
            public void close() throws IOException { paths.close(); }
        };
    }
    private void start() {
        coordinator = new ExecutionCoordinator(settings, new ExecutionBuildRepository(jdbc), jdbc, transactions, database,
                source.readGateway()::readyForExecution, () -> resources);
        coordinator.start(); assertThat(coordinator.isRunning()).isTrue();
    }
    private ProjectExecutionAccess access(ProjectSourceReadGateway read) {
        return new ProjectExecutionAccess(settings, jdbc, projects, read, transactions,
                (java.util.function.Supplier<ExecutionCoordinator>) () -> coordinator);
    }
    private void code(String expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo(expected); assertThat(error).hasNoCause();
            assertThat(error.getMessage()).doesNotContain(root.toString(), parent.toString(), "jdbc:");
        });
    }
    private static void await(BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(25);
        assertThat(ready.getAsBoolean()).isTrue();
    }
    private Path required(String key) throws Exception {
        String raw = System.getProperty(key); assertThat(raw).as(key).isNotBlank();
        Path path = Path.of(raw); assertThat(path.isAbsolute()).isTrue(); ExecutionRootLease.noLinks(path); return path.toRealPath();
    }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
}
