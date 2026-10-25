package dev.atoms.demo.projectexecution;

import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
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
import static org.mockito.ArgumentMatchers.*;

/** Explicit local contract: actual Git, sealed ZIP, business catalog and committed PG admission; no application launch. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionRunAdmissionIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource database;
    static final UUID OWNER = ExecutionBindingContractIT.OWNER, PROJECT = ExecutionBindingContractIT.A;
    static final String SQL = "CREATE TABLE execution_contract_fixture(id bigint PRIMARY KEY);\n", HASH = "a".repeat(64);

    @Test void twoConnectionsProduceOneDurableRunAndOneTicketWithRealInputRechecks() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
        Path root = required("execution.runtime-fixture-root"), configuration = required("execution.test-config-directory");
        Path executionParent = required("execution.contract-directory");
        assertThat(jdbc.queryForMap("SELECT owner_id,archived FROM projects WHERE id=?", PROJECT))
                .containsEntry("owner_id", OWNER).containsEntry("archived", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_runs WHERE project_id=? AND finished_at IS NULL", Integer.class, PROJECT)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE status IN ('PREPARING','CANDIDATE_READY','NEEDS_RECONCILIATION')", Integer.class)).isZero();
        var registry = ExecutionDatabaseBinding.load(Map.of(PROJECT, configuration.resolve(ExecutionBindingContractIT.A_DB + ".properties")),
                List.of(root), Set.of("atoms_demo", "atoms_demo_test"), Set.of("postgres", "atoms_test"));
        var target = registry.verify(PROJECT, budget());
        assertThat(new ExecutionBindingRepository(jdbc).get(PROJECT).target()).isEqualTo(target);
        var historyBefore = registry.history(PROJECT, budget());
        try (var source = new SourceAgentFixture(jdbc, projects, transactions, root)) {
            source.start(database);
            var tree = new ProjectSourcePolicy().validate(List.of(new SourceTree.File(
                    ExecutionMigrations.SOURCE + "V001__execution_contract_fixture.sql", SQL.getBytes(StandardCharsets.UTF_8), 0100644)));
            var base = source.initializeExisting(OWNER, PROJECT, tree);
            Path executionRoot = executionParent.resolve("admission-" + UUID.randomUUID());
            try (var paths = new ExecutionPaths(executionRoot, List.of(root.resolve("source"), root.resolve("work")))) {
                var build = sealedMetadataFixture(paths, base, tree);
                var environment = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                        .withProperty("atoms.project-execution.maintainer-ids", OWNER.toString())
                        .withProperty("atoms.project-execution.root", executionRoot.toString());
                for (String key : List.of("node", "npm", "java", "maven", "policy")) environment.withProperty("atoms.project-execution." + key, root.resolve(key).toString());
                var settings = new ExecutionSettings(environment);
                var identities = new ExecutionMigrationReviewRepository(jdbc);
                var runs = spy(new ExecutionRunRepository(jdbc));
                var reviews = spy(new ExecutionMigrationReview(settings, identities, transactions, source.readGateway(), registry));
                var service = new ExecutionRunService(settings, runs, identities, transactions, paths, registry, reviews);
                var concurrent = new AtomicBoolean(true); var connections = ConcurrentHashMap.<Integer>newKeySet();
                var firstReads = ConcurrentHashMap.<Long>newKeySet();
                var beforeLock = new CyclicBarrier(2); var beforeCommit = new CyclicBarrier(2);
                doAnswer(call -> {
                    if (concurrent.get() && firstReads.add(Thread.currentThread().getId())) {
                        connections.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        beforeLock.await(10, TimeUnit.SECONDS);
                    }
                    return call.callRealMethod();
                }).when(runs).find(eq(OWNER), eq(PROJECT), any());
                doAnswer(call -> {
                    var result = call.callRealMethod();
                    if (concurrent.get()) beforeCommit.await(20, TimeUnit.SECONDS);
                    return result;
                }).when(reviews).requireCompatible(eq(OWNER), eq(PROJECT), eq(build.id()), isNull(), any(), any());
                String key = "admission-" + UUID.randomUUID();
                var threads = Executors.newFixedThreadPool(2);
                ExecutionRun original = null;
                try {
                    Callable<ExecutionRunService.Admission> request = () -> service.submit(OWNER, PROJECT, build.id(), target.id(), null, key);
                    var left = threads.submit(request); var right = threads.submit(request);
                    var first = left.get(60, TimeUnit.SECONDS); var second = right.get(60, TimeUnit.SECONDS);
                    concurrent.set(false); original = first.run();
                    assertThat(second.run()).isEqualTo(original); assertThat(connections).hasSize(2);
                    assertThat(List.of(first, second).stream().filter(value -> value.ticket() != null).count()).isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_runs WHERE project_id=? AND build_id=?", Integer.class, PROJECT, build.id())).isEqualTo(1);
                    assertThat(original.executionId()).isNull(); assertThat(original.status()).isEqualTo("STARTING");
                    var ticket = first.ticket() != null ? first.ticket() : second.ticket();
                    service.recheck(service.take(ticket), null);
                    assertThatThrownBy(() -> service.take(ticket)).hasMessage("RUN_LAUNCH_ALREADY_CLAIMED");
                    assertCode(() -> service.submit(OWNER, PROJECT, UUID.randomUUID(), target.id(), null, key), "RUN_IDEMPOTENCY_CONFLICT");
                    assertCode(() -> service.submit(OWNER, PROJECT, build.id(), target.id(), null, key + "-second"), "RUN_PROJECT_BUSY");
                    UUID stranger = UUID.randomUUID();
                    environment.withProperty("atoms.project-execution.maintainer-ids", OWNER + "," + stranger);
                    assertCode(() -> service.submit(stranger, PROJECT, build.id(), target.id(), null, key), "PROJECT_NOT_FOUND");
                    environment.withProperty("atoms.project-execution.maintainer-ids", OWNER.toString());
                    jdbc.update("UPDATE projects SET archived=true WHERE id=?", PROJECT);
                    try { assertCode(() -> service.submit(OWNER, PROJECT, build.id(), target.id(), null, key), "RUN_PROJECT_ARCHIVED"); }
                    finally { jdbc.update("UPDATE projects SET archived=false WHERE id=?", PROJECT); }
                    Path jar = executionRoot.resolve("artifacts/" + build.id() + "/app.jar");
                    Files.writeString(jar, "deliberately-corrupted-contract-artifact");
                    assertThat(service.submit(OWNER, PROJECT, build.id(), target.id(), null, key).ticket()).isNull();
                    assertThatThrownBy(() -> service.submit(OWNER, PROJECT, build.id(), target.id(), null, key + "-changed"))
                            .hasMessage("EXECUTION_ARTIFACT_INVALID");
                    verifyRecovery(settings, runs, environment, original);
                    assertThat(service.submit(OWNER, PROJECT, build.id(), target.id(), null, key).ticket()).isNull();
                } finally {
                    concurrent.set(false); threads.shutdownNow(); assertThat(threads.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
                    // Only this fixture's never-launched rows may finish. Never clear an issued execution or unknown process.
                    for (UUID id : jdbc.queryForList("SELECT id FROM execution_runs WHERE project_id=? AND build_id=? AND finished_at IS NULL", UUID.class, PROJECT, build.id())) {
                        var row = runs.get(PROJECT, id);
                        assertThat(row.executionId()).isNull(); assertThat(row.executionPending()).isFalse();
                        new TransactionTemplate(transactions).execute(tx -> runs.notStarted(row, "RUN_CANCELLED"));
                    }
                }
            }
        }
        assertThat(registry.history(PROJECT, budget())).isEqualTo(historyBefore);
    }

    private void verifyRecovery(ExecutionSettings settings, ExecutionRunRepository runs, MockEnvironment environment,
                                ExecutionRun original) throws Exception {
        // The outer fixture still holds the real execution-root lease; recovery does no artifact or business DB I/O.
        try (var lease = new ExecutionCoordinatorLease(database)) {
            var recovery = new ExecutionRunRecovery(settings, runs, transactions, lease, () -> true);
            doAnswer(call -> {
                var result = call.callRealMethod();
                environment.withProperty("atoms.project-execution.max-running-projects", "3");
                return result;
            }).when(runs).recover(original);
            try {
                assertThatThrownBy(() -> recovery.reconcile(original)).hasMessage("RUN_RECOVERY_UNAVAILABLE").hasNoCause();
                assertThat(runs.get(PROJECT, original.id())).isEqualTo(original); // Real UPDATE rolled back in PG.
            } finally {
                environment.withProperty("atoms.project-execution.max-running-projects", "4");
                doCallRealMethod().when(runs).recover(original);
            }
            var recovered = recovery.reconcile(original);
            assertThat(recovered.status()).isEqualTo("FAILED"); assertThat(recovered.errorCode()).isEqualTo("RUN_INTERRUPTED");
            assertThat(recovered.executionId()).isNull(); assertThat(recovered.stoppedSha256()).isNull();
            assertThat(recovery.reconcile(original)).isEqualTo(recovered);
            lease.close();
            assertThatThrownBy(() -> recovery.reconcile(original)).hasMessage("RUN_RECOVERY_UNAVAILABLE").hasNoCause();
            assertThat(runs.get(PROJECT, original.id())).isEqualTo(recovered);
        }
    }

    /** A named metadata/ZIP fixture, not a successful native Maven build. Actual native execution remains EX25–27. */
    private ExecutionBuild sealedMetadataFixture(ExecutionPaths paths, SourceAgentFixture.Base base, SourceTree tree) throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO execution_builds(id,project_id,owner_id,source_version_id,tree_sha256,idempotency_key_sha256,request_sha256,queue_slot) VALUES (?,?,?,?,?,?,?,1)",
                id, PROJECT, OWNER, base.versionId(), tree.sha256(), id.toString().replace("-", "").repeat(2), HASH);
        jdbc.update("UPDATE execution_builds SET status='PREPARING',queue_slot=null,worker_slot=1 WHERE id=?", id);
        for (String stage : List.of("INSTALLING", "CHECKING", "PACKAGING")) jdbc.update("UPDATE execution_builds SET status=? WHERE id=?", stage, id);
        var builds = new ExecutionBuildRepository(jdbc); var build = builds.get(PROJECT, id);
        var task = paths.createBuild(id); Path backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
        try (var zip = new ZipOutputStream(Files.newOutputStream(Files.createDirectory(backend.resolve("target")).resolve("app.jar")))) {
            zip.putNextEntry(new ZipEntry(ExecutionMigrations.JAR + "V001__execution_contract_fixture.sql"));
            zip.write(SQL.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        var tools = new EnumMap<ExecutionToolchain.Kind, ExecutionToolFiles.Summary>(ExecutionToolchain.Kind.class);
        for (var kind : ExecutionToolchain.Kind.values()) tools.put(kind, new ExecutionToolFiles.Summary(HASH, 1, 20));
        var artifact = ExecutionArtifact.seal(paths, task, build, new ExecutionArtifact.Evidence(tools,
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
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
