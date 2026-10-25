package dev.atoms.demo.projectexecution;

import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

/** Actual committed PG/lease/source/history and owned HTTP JVM. Final executable is an explicit fixture, not a generated Spring application. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionRunHealthIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource database;
    static final UUID OWNER = ExecutionBindingContractIT.OWNER, PROJECT = ExecutionBindingContractIT.A;
    static final String HASH = "a".repeat(64);

    @Test void committedIntentPrecedesOneActualLaunchAndStopEvidenceSettlesItWhileForeignPortsRemainUntouched() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
        Path root = required("execution.runtime-fixture-root"), parent = required("execution.contract-directory");
        Path configuration = required("execution.test-config-directory");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_runs WHERE project_id=? AND finished_at IS NULL", Integer.class, PROJECT)).isZero();
        var registry = ExecutionDatabaseBinding.load(Map.of(PROJECT, configuration.resolve(ExecutionBindingContractIT.A_DB + ".properties")),
                List.of(root, parent), Set.of("atoms_demo", "atoms_demo_test"), Set.of("postgres", "atoms_test"));
        var target = registry.verify(PROJECT, budget()); var history = registry.history(PROJECT, budget());
        var tree = new ProjectSourcePolicy().validate(List.of(new SourceTree.File(
                ExecutionMigrations.SOURCE + "V001__execution_contract_fixture.sql", ExecutionRunAdmissionIT.SQL.getBytes(StandardCharsets.UTF_8), 0100644)));
        try (var source = new SourceAgentFixture(jdbc, projects, transactions, root)) {
            source.start(database); var base = source.initializeExisting(OWNER, PROJECT, tree);
            var lease = new ExecutionCoordinatorLease(database);
            ExecutionPaths paths = null; var owned = new AtomicReference<ExecutionRunProcess>();
            var threads = Executors.newSingleThreadExecutor(); Future<ExecutionRun> future = null;
            try {
                Path executionRoot = parent.resolve("health-" + UUID.randomUUID());
                paths = new ExecutionPaths(executionRoot, List.of(root.resolve("source"), root.resolve("work")));
                var tools = ExecutionToolchain.capture(ExecutionToolchainTest.installation(parent.resolve("tools-" + UUID.randomUUID())), List.of(root), budget());
                var build = sealedMetadataFixture(paths, tools, base, tree);
                var environment = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                        .withProperty("atoms.project-execution.maintainer-ids", OWNER.toString())
                        .withProperty("atoms.project-execution.root", executionRoot.toString());
                for (String key : List.of("node", "npm", "java", "maven", "policy")) environment.withProperty("atoms.project-execution." + key, parent.resolve(key).toString());
                environment.withProperty("atoms.project-execution.java", tools.javaHome().resolve("bin/java").toString());
                var settings = new ExecutionSettings(environment); var runs = new ExecutionRunRepository(jdbc);
                var identities = new ExecutionMigrationReviewRepository(jdbc);
                var reviews = new ExecutionMigrationReview(settings, identities, transactions, source.readGateway(), registry);
                var service = new ExecutionRunService(settings, runs, identities, transactions, paths, registry, reviews);
                var launches = new AtomicInteger();
                var worker = new ExecutionRunWorker(settings, service, runs, transactions, paths, tools, registry,
                        () -> { if (!lease.healthy()) throw new IOException("RUN_COORDINATOR_UNAVAILABLE"); },
                        (prepared, run, execution, startup, preflight) -> {
                            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                            if (owned.get() != null) {
                                assertThat(owned.get().isAlive()).as("Previous process must exit before the next launch").isFalse();
                            }
                            // A separate transaction/connection can already see the committed intent before Process.start.
                            var committed = runs.get(PROJECT, run);
                            assertThat(committed.executionPending()).isTrue(); assertThat(committed.executionId()).isEqualTo(execution);
                            launches.incrementAndGet();
                            var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                    "--add-modules", "jdk.httpserver", "-cp", Path.of("target/test-classes").toAbsolutePath().toString(),
                                    ExecutionHealthTest.Child.class.getName(), Integer.toString(target.port()), run.toString(), execution.toString(), "valid")
                                    .directory(prepared.directory());
                            // Controlled HTTP fixture never receives business credentials and cannot run the SQL sample.
                            command.environment().clear();
                            var process = ExecutionRunProcess.start(command, run, execution, startup, preflight); owned.set(process); return process;
                        });
                String key = "health-" + UUID.randomUUID();
                var admission = service.submit(OWNER, PROJECT, build.id(), target.id(), null, key);
                future = threads.submit(() -> worker.run(admission.ticket()));
                var ready = awaitReady(runs, admission.run().id(), future);
                assertThat(owned.get().isServing()).isTrue(); assertThat(ready.readyAt()).isNotNull();
                assertThat(service.submit(OWNER, PROJECT, build.id(), target.id(), null, key).ticket()).isNull();
                assertThatThrownBy(() -> worker.run(admission.ticket())).hasMessage("RUN_LAUNCH_ALREADY_CLAIMED");
                assertThat(launches).hasValue(1);
                var nextBuild = sealedMetadataFixture(paths, tools, base, tree);
                String replacementKey = key + "-replace";
                var switching = service.replace(OWNER, PROJECT, ready.id(), nextBuild.id(), target.id(), null, replacementKey);
                assertThat(switching.stopping().status()).isEqualTo("STOPPING"); assertThat(switching.admission()).isNull();
                var stopped = future.get(20, TimeUnit.SECONDS);
                assertThat(stopped.status()).isEqualTo("STOPPED"); assertThat(stopped.executionPending()).isFalse();
                var receipt = owned.get().await(Duration.ofSeconds(1)).stopped();
                assertThat(receipt.matches(stopped.id(), stopped.executionId(), executionRoot.resolve("runs/" + stopped.id()))).isTrue();
                assertThat(stopped.stoppedSha256()).isEqualTo(receipt.digest());
                assertThat(service.submit(OWNER, PROJECT, build.id(), target.id(), null, key).run()).isEqualTo(stopped);
                var replacement = concurrentReplacement(service, stopped.id(), nextBuild.id(), target.id(), replacementKey);
                assertThat(replacement.stopping()).isNull(); assertThat(replacement.admission().ticket()).isNotNull();
                future = threads.submit(() -> worker.run(replacement.admission().ticket()));
                var nextReady = awaitReady(runs, replacement.admission().run().id(), future);
                assertThat(nextReady.buildId()).isEqualTo(nextBuild.id()); assertThat(launches).hasValue(2);
                assertThat(runs.get(PROJECT, stopped.id())).isEqualTo(stopped);
                var replay = service.replace(OWNER, PROJECT, stopped.id(), nextBuild.id(), target.id(), null, replacementKey);
                assertThat(replay.admission().ticket()).isNull(); assertThat(replay.admission().run()).isEqualTo(nextReady);
                assertThat(runs.get(PROJECT, nextReady.id()).stopRequested()).isFalse();
                assertThatThrownBy(() -> service.replace(OWNER, PROJECT, nextReady.id(), nextBuild.id(), target.id(), null, replacementKey))
                        .isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class, error -> assertThat(error.code()).isEqualTo("RUN_IDEMPOTENCY_CONFLICT"));
                assertThat(service.stop(OWNER, PROJECT, nextReady.id()).status()).isEqualTo("STOPPING");
                var nextStopped = future.get(20, TimeUnit.SECONDS);
                assertThat(nextStopped.status()).isEqualTo("STOPPED");
                assertThat(service.stop(OWNER, PROJECT, nextReady.id())).isEqualTo(nextStopped);
                try (var foreign = new ServerSocket()) {
                    foreign.setReuseAddress(true);
                    foreign.bind(new InetSocketAddress("127.0.0.1", target.port()));
                    var next = service.replace(OWNER, PROJECT, nextStopped.id(), build.id(), target.id(), null, key + "-occupied").admission();
                    var rejected = worker.run(next.ticket());
                    assertThat(rejected.status()).isEqualTo("FAILED"); assertThat(rejected.errorCode()).isEqualTo("RUN_PORT_IN_USE");
                    assertThat(rejected.executionId()).isNull(); assertThat(foreign.isClosed()).isFalse(); assertThat(launches).hasValue(2);
                    assertThat(runs.get(PROJECT, nextStopped.id())).isEqualTo(nextStopped);
                    assertThat(new ExecutionBuildRepository(jdbc).get(PROJECT, nextBuild.id()).status()).isEqualTo("SUCCEEDED");
                }
                var cancelledAdmission = service.submit(OWNER, PROJECT, build.id(), target.id(), null, key + "-cancel-before-launch");
                assertThat(service.stop(OWNER, PROJECT, cancelledAdmission.run().id()).executionId()).isNull();
                var cancelled = worker.run(cancelledAdmission.ticket());
                assertThat(cancelled.status()).isEqualTo("CANCELLED"); assertThat(cancelled.executionId()).isNull();
                assertThat(launches).hasValue(2);
            } finally {
                boolean settled = owned.get() == null;
                if (owned.get() != null) {
                    owned.get().requestStop(); settled = owned.get().await(Duration.ofSeconds(12)).stopped() != null;
                }
                threads.shutdownNow(); assertThat(threads.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
                // An unknown process retains its coordination and root capabilities; do not optimistically release them.
                if (settled) { if (paths != null) paths.close(); lease.close(); }
                assertThat(settled).isTrue();
            }
        }
        assertThat(registry.history(PROJECT, budget())).isEqualTo(history);
    }

    private ExecutionRunService.Replacement concurrentReplacement(ExecutionRunService service, UUID previous, UUID build, UUID binding, String key) throws Exception {
        var racers = Executors.newFixedThreadPool(2); var gate = new CyclicBarrier(2);
        try {
            Callable<ExecutionRunService.Replacement> request = () -> {
                gate.await(5, TimeUnit.SECONDS); return service.replace(OWNER, PROJECT, previous, build, binding, null, key);
            };
            var first = racers.submit(request); var second = racers.submit(request);
            var results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().map(value -> value.admission().run().id()).distinct().count()).isEqualTo(1);
            var winners = results.stream().filter(value -> value.admission().ticket() != null).toList();
            assertThat(winners).hasSize(1); return winners.get(0);
        } finally { racers.shutdownNow(); assertThat(racers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    private ExecutionRun awaitReady(ExecutionRunRepository runs, UUID id, Future<ExecutionRun> future) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < end) {
            var current = runs.get(PROJECT, id);
            if (current.status().equals("READY")) return current;
            if (future.isDone()) { var result = future.get(); fail("Runtime finished before READY: " + result.status() + "/" + result.errorCode()); }
            Thread.sleep(50);
        }
        throw new AssertionError("Controlled runtime did not become READY");
    }
    private ExecutionBuild sealedMetadataFixture(ExecutionPaths paths, ExecutionToolchain tools, SourceAgentFixture.Base base, SourceTree tree) throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO execution_builds(id,project_id,owner_id,source_version_id,tree_sha256,idempotency_key_sha256,request_sha256,queue_slot) VALUES (?,?,?,?,?,?,?,1)",
                id, PROJECT, OWNER, base.versionId(), tree.sha256(), id.toString().replace("-", "").repeat(2), HASH);
        jdbc.update("UPDATE execution_builds SET status='PREPARING',queue_slot=null,worker_slot=1 WHERE id=?", id);
        for (String stage : List.of("INSTALLING", "CHECKING", "PACKAGING")) jdbc.update("UPDATE execution_builds SET status=? WHERE id=?", stage, id);
        var builds = new ExecutionBuildRepository(jdbc); var build = builds.get(PROJECT, id);
        var task = paths.createBuild(id); Path backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
        try (var zip = new ZipOutputStream(Files.newOutputStream(Files.createDirectory(backend.resolve("target")).resolve("app.jar")))) {
            zip.putNextEntry(new ZipEntry(ExecutionMigrations.JAR + "V001__execution_contract_fixture.sql"));
            zip.write(ExecutionRunAdmissionIT.SQL.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        var identities = new EnumMap<ExecutionToolchain.Kind, ExecutionToolFiles.Summary>(ExecutionToolchain.Kind.class);
        for (var kind : ExecutionToolchain.Kind.values()) identities.put(kind, tools.identity(kind));
        var artifact = ExecutionArtifact.seal(paths, task, build, new ExecutionArtifact.Evidence(identities,
                new ExecutionNpmPolicy.Verified(HASH, HASH, HASH, 1), new ExecutionMavenPolicy.Verified(HASH, HASH),
                new ExecutionDiagnostics.Frontend(1, 1, 1), new ExecutionJavaReport.Summary(1, 1), new ExecutionPackaging.Summary(1, 1, HASH)), budget(), () -> {});
        jdbc.update("UPDATE execution_builds SET status='SUCCEEDED',jar_bytes=?,jar_sha256=?,manifest_sha256=?,worker_slot=null,finished_at=clock_timestamp() WHERE id=?",
                artifact.summary().jarBytes(), artifact.summary().jarSha256(), artifact.summary().manifestSha256(), id);
        return builds.get(PROJECT, id);
    }
    private Path required(String key) throws Exception {
        String value = System.getProperty(key); assertThat(value).as(key).isNotBlank();
        Path path = Path.of(value); assertThat(path.isAbsolute()).isTrue(); ExecutionRootLease.noLinks(path); return path.toRealPath();
    }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
}
