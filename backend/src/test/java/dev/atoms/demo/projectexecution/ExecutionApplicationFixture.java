package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.*;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.jar.JarFile;
import javax.sql.DataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Actual native worker and runtime. Uses only the existing approved A/B identities, files and loopback ports. */
final class ExecutionApplicationFixture implements AutoCloseable {
    static final UUID OWNER = ExecutionBindingContractIT.OWNER, A = ExecutionBindingContractIT.A, B = ExecutionBindingContractIT.B;
    private static final ObjectMapper JSON = new ObjectMapper();
    final Path root;
    final JdbcTemplate jdbc;
    final SourceAgentFixture source;
    final ExecutionCoordinator coordinator;
    final ExecutionDatabaseBinding business;
    final ProjectExecutionAccess access;
    private final DataSource database;
    private final AnnotationConfigApplicationContext runtime;
    private final ExecutionTestDatabase tests;
    private final ExecutionMigrationReview migrations;
    final ExecutionRunReconciliation reconciliation;
    private final Map<UUID, ExecutionTestDatabase.Verified> testIdentities = new HashMap<>();
    private final Map<UUID, SourceAgentFixture.Base> bases = new HashMap<>();
    private final Map<UUID, ExecutionBindingRepository.Target> targets = new HashMap<>();
    private final List<ExecutionRun> owned = new ArrayList<>();
    private final List<Path> protectedRoots;
    private final HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    ExecutionApplicationFixture(JdbcTemplate jdbc, ProjectService projects, PlatformTransactionManager transactions, DataSource database) throws Exception {
        this.jdbc = jdbc; this.database = database;
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class)).isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
        assertThat(new ExecutionRunRepository(jdbc).unfinished()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE status IN ('PREPARING','CANDIDATE_READY','NEEDS_RECONCILIATION')", Integer.class)).isZero();
        Path parent = directory("execution.contract-directory");
        root = directory("execution.application-directory");
        assertThat(root.getParent()).isEqualTo(parent); // Never adopt a daily runtime or another source root.
        assertThat(Files.getPosixFilePermissions(root)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        Path sourceRoot = directory("execution.runtime-fixture-root"), configuration = directory("execution.test-config-directory");
        protectedRoots = List.of(sourceRoot.resolve("source"), sourceRoot.resolve("work"));
        var testFiles = Map.of(A, configuration.resolve(ExecutionBindingContractIT.A_TEST + ".properties"),
                B, configuration.resolve("atoms_exec_b_test_20261020.properties"));
        var businessFiles = Map.of(A, configuration.resolve(ExecutionBindingContractIT.A_DB + ".properties"),
                B, configuration.resolve(ExecutionBindingContractIT.B_DB + ".properties"));
        tests = ExecutionTestDatabase.load(testFiles, protectedRoots, Set.of("atoms_demo", "atoms_demo_test"), Set.of("postgres", "atoms_test"));
        business = ExecutionDatabaseBinding.load(businessFiles, protectedRoots, Set.of("atoms_demo", "atoms_demo_test"), Set.of("postgres", "atoms_test"));
        business.requireSeparateFrom(tests);
        for (UUID project : List.of(A, B)) {
            targets.put(project, business.verify(project, budget()));
            assertThat(new ExecutionBindingRepository(jdbc).get(project).target()).isEqualTo(targets.get(project));
            testIdentities.put(project, tests.verify(project, budget()));
        }
        source = new SourceAgentFixture(jdbc, projects, transactions, sourceRoot);
        try {
            source.start(database);
            for (UUID project : List.of(A, B)) bases.put(project, source.initializeExisting(OWNER, project,
                    ExecutionContractSupport.retainedTree(jdbc, source, project)));
            Path policy = root.resolve("approval.json");
            byte[] expected = Files.readAllBytes(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json"));
            if (!Files.exists(policy)) {
                Files.write(Files.createFile(policy, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))), expected);
            } else { ExecutionRootLease.noLinks(policy); assertThat(Files.readAllBytes(policy)).isEqualTo(expected); }
            var config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                    .withProperty("atoms.project-execution.maintainer-ids", OWNER.toString())
                    .withProperty("atoms.project-execution.root", root.resolve("execution").toString())
                    .withProperty("atoms.project-execution.policy", policy.toString())
                    .withProperty("atoms.project-execution.test-databases", configuration(testFiles))
                    .withProperty("atoms.project-execution.runtime-databases", configuration(businessFiles));
            for (String tool : List.of("node", "npm", "java", "maven")) config.withProperty("atoms.project-execution." + tool, required("execution.contract-" + tool).toString());
            runtime = ExecutionTodoFixture.runtime(config, source, jdbc, database, projects, transactions);
            coordinator = runtime.getBean(ExecutionCoordinator.class);
            access = new ProjectExecutionAccess(runtime.getBean(ExecutionSettings.class), jdbc, projects, source.readGateway(), transactions,
                    (java.util.function.Supplier<ExecutionCoordinator>) () -> coordinator);
            migrations = new ExecutionMigrationReview(runtime.getBean(ExecutionSettings.class), new ExecutionMigrationReviewRepository(jdbc),
                    transactions, source.readGateway(), business);
            reconciliation = new ExecutionRunReconciliation(runtime.getBean(ExecutionSettings.class), jdbc, transactions,
                    source.readGateway(), business, coordinator);
            System.out.printf("execution_application_root path=%s%n", root);
        } catch (Exception | Error failure) { source.close(); throw failure; }
    }

    int compatibleVersion(UUID project) throws Exception {
        var state = history(project).state();
        if (!state.exists()) { assertThat(state.objects()).isZero(); assertThat(state.applied()).isEmpty(); return 1; }
        for (int version : new int[]{1, 2}) {
            var files = new TreeMap<String, byte[]>();
            for (var file : ExecutionTodoFixture.application(version).files()) if (file.path().startsWith(ExecutionMigrations.SOURCE))
                files.put(file.path().substring(ExecutionMigrations.SOURCE.length()), file.content());
            try {
                if (ExecutionMigrationPolicy.evaluate(ExecutionMigrations.catalog(files), true, state.objects(), state.applied())
                        == ExecutionMigrationPolicy.Decision.UNCHANGED) return version;
            } catch (java.io.IOException incompatible) { /* Try the other reviewed version, never modify history. */ }
        }
        throw new IllegalStateException("Business history is not a reviewed application version");
    }

    SourceAgentFixture.Base acceptSource(UUID project, SourceTree tree) throws Exception {
        var base = bases.get(project); assertThat(base).isNotNull();
        if (!source.snapshot(OWNER, project, base.versionId()).sha256().equals(tree.sha256())) {
            base = source.accept(OWNER, base, tree); bases.put(project, base);
        }
        return base;
    }
    ExecutionBuild build(UUID project, SourceTree tree) throws Exception {
        var base = acceptSource(project, tree);
        var accepted = runtime.getBean(ProjectExecutionService.class).submit(OWNER, project, base.versionId(), UUID.randomUUID().toString());
        System.out.printf("execution_application_build_started project=%s build=%s source=%s%n", project, accepted.id(), base.versionId());
        var repository = new ExecutionBuildRepository(jdbc);
        await(() -> repository.get(project, accepted.id()).finishedAt() != null || !coordinator.isRunning(), 1830);
        var build = repository.get(project, accepted.id());
        assertThat(build.status()).as("Native business build: %s", build.errorCode()).isEqualTo("SUCCEEDED");
        assertThat(build.treeSha256()).isEqualTo(tree.sha256()); assertThat(build.sourceVersionId()).isEqualTo(base.versionId());
        var artifact = artifact(build);
        assertThat(artifact.evidenceFor(build).frontend().tests()).isEqualTo(22);
        assertTestDatabasesUnchanged();
        System.out.printf("execution_application_build project=%s build=%s source=%s tree=%s jar=%s%n",
                project, build.id(), build.sourceVersionId(), build.treeSha256(), artifact.summary().jarSha256());
        return build;
    }

    ExecutionBuild retainedBuild(UUID project, String property) throws Exception { return retainedBuild(project, property, 1); }
    ExecutionBuild retainedBuild(UUID project, String property, int version) throws Exception {
        String raw = System.getProperty(property); assertThat(raw).as(property).isNotBlank();
        var build = new ExecutionBuildRepository(jdbc).get(project, UUID.fromString(raw));
        assertThat(build.status()).isEqualTo("SUCCEEDED"); assertThat(build.finishedAt()).isNotNull();
        assertThat(build.treeSha256()).isEqualTo(ExecutionTodoFixture.application(version).sha256());
        assertThat(source.snapshot(OWNER, project, build.sourceVersionId()).sha256()).isEqualTo(build.treeSha256());
        var artifact = artifact(build); // Reopens the exact retained artifact under this coordinator's execution root.
        assertThat(artifact.evidenceFor(build).frontend().tests()).isEqualTo(22);
        System.out.printf("execution_application_baseline project=%s build=%s source=%s tree=%s jar=%s%n",
                project, build.id(), build.sourceVersionId(), build.treeSha256(), artifact.summary().jarSha256());
        return build;
    }
    ExecutionMigrationReview.View migrationView(ExecutionBuild build) throws Exception {
        return migrations.inspect(OWNER, build.projectId(), build.id(), artifact(build), budget());
    }
    UUID approveMigration(ExecutionBuild build, ExecutionMigrationReview.View view) throws Exception {
        assertThat(view.projectId()).isEqualTo(build.projectId()); assertThat(view.buildId()).isEqualTo(build.id());
        return migrations.approve(OWNER, build.projectId(), build.id(), view.historySha256(), view.targetSha256(), artifact(build), budget());
    }
    void assertStartRejected(ExecutionBuild build, UUID review, String code) throws Exception {
        var before = jdbc.queryForObject("SELECT count(*) FROM execution_runs WHERE project_id=?", Long.class, build.projectId());
        Throwable failure = catchThrowable(() -> {
            var unexpected = coordinator.startApplication(OWNER, build.projectId(), build.id(), target(build.projectId()).id(), review, UUID.randomUUID().toString())
                    .toCompletableFuture().get(150, TimeUnit.SECONDS);
            owned.add(unexpected); // Even an unexpected admission must be closed by its original owner.
        });
        while (failure instanceof ExecutionException || failure instanceof CompletionException) failure = failure.getCause();
        assertThat(failure).isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_runs WHERE project_id=?", Long.class, build.projectId())).isEqualTo(before);
        System.out.printf("execution_application_refused project=%s build=%s code=%s%n", build.projectId(), build.id(), code);
    }

    ExecutionRun admit(ExecutionBuild build, UUID review) throws Exception {
        var run = coordinator.startApplication(OWNER, build.projectId(), build.id(), target(build.projectId()).id(), review, UUID.randomUUID().toString())
                .toCompletableFuture().get(150, TimeUnit.SECONDS);
        owned.add(run); return run;
    }
    ExecutionRun start(ExecutionBuild build, UUID review) throws Exception {
        var run = admit(build, review);
        await(() -> current(run).status().equals("READY") || current(run).finishedAt() != null, 140);
        var ready = current(run); assertThat(ready.status()).as("Actual app launch: %s", ready.errorCode()).isEqualTo("READY");
        System.out.printf("execution_application_ready project=%s run=%s execution=%s build=%s%n", ready.projectId(), ready.id(), ready.executionId(), ready.buildId());
        return ready;
    }
    void awaitStoppedFailure(ExecutionRun failed) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        for (;;) {
            try (var scope = coordinator.open(failed)) { scope.checkAuthority(); return; }
            catch (java.io.IOException | dev.atoms.demo.common.ApiException unavailable) {
                if (System.nanoTime() >= until) throw unavailable;
                Thread.sleep(100);
            }
        }
    }
    void stop(ExecutionRun run) throws Exception {
        var process = coordinator.accessScope().ownedProcess(run.id()); assertThat(process).isNotNull();
        coordinator.stopApplication(OWNER, run.projectId(), run.id());
        await(() -> current(run).finishedAt() != null, 30);
        var ended = current(run); assertThat(ended.status()).isEqualTo("STOPPED"); assertThat(process.isAlive()).isFalse();
        var receipt = process.await(Duration.ofSeconds(1)).stopped(); assertThat(receipt).isNotNull();
        assertThat(receipt.matches(run.id(), run.executionId(), root.resolve("execution/runs/" + run.id()))).isTrue();
        assertThat(ended.stoppedSha256()).isEqualTo(receipt.digest()); assertThat(ended.executionPending()).isFalse();
        System.out.printf("execution_application_stopped run=%s receipt=%s%n", run.id(), receipt.digest());
    }
    ExecutionRun current(ExecutionRun run) { return new ExecutionRunRepository(jdbc).get(run.projectId(), run.id()); }
    ExecutionBindingRepository.Target target(UUID project) { var target = targets.get(project); assertThat(target).isNotNull(); return target; }
    ExecutionMigrationHistory.Snapshot history(UUID project) throws Exception { return business.history(project, budget()); }
    ExecutionArtifact artifact(ExecutionBuild build) throws Exception {
        var row = jdbc.queryForMap("SELECT jar_sha256,manifest_sha256 FROM execution_builds WHERE id=?", build.id());
        return coordinator.accessScope().artifact(build, (String) row.get("jar_sha256"), (String) row.get("manifest_sha256"), budget());
    }
    long pid(ExecutionRun run) throws Exception {
        var process = coordinator.accessScope().ownedProcess(run.id()); assertThat(process.serves(run.id(), run.executionId())).isTrue();
        String config = "--spring.config.location=" + root.resolve("execution/runs/" + run.id() + "/config/application.properties").toUri().toASCIIString();
        // Read-only proof of distinct children. Stopping always uses the original worker capability, never these PIDs.
        try (var children = ProcessHandle.current().children()) {
            var matching = children.filter(child -> Arrays.asList(child.info().arguments().orElse(new String[0])).contains(config)).toList();
            assertThat(matching).hasSize(1); return matching.get(0).pid();
        }
    }
    void assertDelivered(ExecutionRun run, ExecutionBuild build) throws Exception {
        var handle = access.running(OWNER, run.projectId(), run.id());
        var identity = access.identity(OWNER, run.projectId(), handle);
        assertThat(identity.build().sourceVersionId()).isEqualTo(build.sourceVersionId());
        assertThat(identity.build().buildId()).isEqualTo(build.id());
        assertThat(access.source(OWNER, run.projectId(), handle).tree().sha256()).isEqualTo(build.treeSha256());
        try (var jar = new JarFile(artifact(build).jar().toFile())) {
            var entries = jar.stream().filter(entry -> entry.getName().startsWith("BOOT-INF/classes/static/") && !entry.isDirectory()).toList();
            assertThat(entries.size()).isGreaterThan(1);
            for (var entry : entries) {
                String name = entry.getName().substring("BOOT-INF/classes/static/".length());
                try (var input = jar.getInputStream(entry)) {
                    assertThat(request(run.projectId(), "GET", name.equals("index.html") ? "" : name, null, 200)).isEqualTo(input.readAllBytes());
                }
            }
        }
    }
    JsonNode json(UUID project, String method, String path, Object body, int status) throws Exception {
        return JSON.readTree(request(project, method, path, body, status));
    }
    List<JsonNode> allTodos(UUID project) throws Exception {
        var rows = new ArrayList<JsonNode>(); long after = 0;
        for (int page = 0; page < 100; page++) {
            var result = json(project, "GET", "api/todos?limit=100&after=" + after, null, 200);
            result.path("items").forEach(rows::add);
            if (result.path("nextAfter").isNull()) return rows;
            long next = result.path("nextAfter").asLong(); assertThat(next).isGreaterThan(after); after = next;
        }
        throw new AssertionError("Contract data exceeded the bounded page allowance");
    }
    private byte[] request(UUID project, String method, String path, Object body, int status) throws Exception {
        assertThat(path).doesNotContain("..", ":", "#");
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target(project).port() + "/" + path)).timeout(Duration.ofSeconds(10));
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(status);
        assertThat(response.body().length).isLessThan(1 << 20); return response.body();
    }
    void assertTestDatabasesUnchanged() throws Exception {
        for (UUID project : List.of(A, B)) assertThat(tests.verify(project, budget())).isEqualTo(testIdentities.get(project));
    }
    @Override public void close() throws Exception {
        runtime.close(); // Retains ownership on an unproven stop; never invent completion evidence.
        for (var run : owned) {
            var ended = current(run); assertThat(ended.finishedAt()).isNotNull(); assertThat(ended.executionPending()).isFalse();
            if (ended.executionId() != null) assertThat(ended.stoppedSha256()).matches("[a-f0-9]{64}");
        }
        source.close();
        try (var paths = new ExecutionPaths(root.resolve("execution"), protectedRoots); var lease = new ExecutionCoordinatorLease(database)) {
            paths.checkedRoot(); assertThat(lease.healthy()).isTrue();
        }
    }
    private static String configuration(Map<UUID, Path> files) throws Exception {
        var paths = new TreeMap<String, String>(); files.forEach((id, path) -> paths.put(id.toString(), path.toString())); return JSON.writeValueAsString(paths);
    }
    static void await(BooleanSupplier condition, int seconds) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).as("Contract reached its bounded deadline").isTrue();
    }
    static ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
    private static Path required(String key) throws Exception {
        String raw = System.getProperty(key); assertThat(raw).as(key).isNotBlank();
        Path path = Path.of(raw); assertThat(path.isAbsolute() && path.normalize().equals(path)).isTrue(); return path;
    }
    private static Path directory(String key) throws Exception {
        Path path = required(key); ExecutionRootLease.noLinks(path); return path.toRealPath();
    }
}
