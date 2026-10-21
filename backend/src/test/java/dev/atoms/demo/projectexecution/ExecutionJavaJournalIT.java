package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.SourceAgentFixture;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static dev.atoms.demo.projectexecution.ExecutionToolchain.Operation.*;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false",
        "atoms.generation.worker-enabled=false"})
class ExecutionJavaJournalIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID();
    SourceAgentFixture source;
    ExecutionPaths paths;
    ExecutionPaths.Task task;
    ExecutionBuild build;
    MockEnvironment configuration;
    ExecutionBuildJournal journal;
    Path frontend, backend;
    OwnedProcess.Stopped heldProof;

    @BeforeEach void setup() throws Exception {
        Path root = temporary.toRealPath();
        source = new SourceAgentFixture(jdbc, projects, transactions, root);
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        var base = source.create(owner);
        configuration = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        for (String key : List.of("root", "node", "npm", "java", "maven", "policy"))
            configuration.withProperty("atoms.project-execution." + key, root.resolve("unused-" + key).toString());
        var repository = new ExecutionBuildRepository(jdbc);
        var service = new ProjectExecutionService(new ExecutionSettings(configuration), repository,
                projects, source.readGateway(), transactions);
        UUID id = service.submit(owner, base.projectId(), base.versionId(), "java").id();
        // Test-only claim; the production worker/scheduler is not implemented by this journal.
        jdbc.update("UPDATE execution_builds SET status='PREPARING',queue_slot=null,worker_slot=1 WHERE id=?", id);
        build = repository.get(base.projectId(), id);
        paths = new ExecutionPaths(root.resolve("execution"), List.of()); task = paths.createBuild(id);
        frontend = ExecutionRootLease.directory(paths.project(task).resolve("frontend"));
        backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
        journal = journal(jdbc);
    }
    @AfterEach void close() throws Exception {
        try {
            if (build != null) {
                if (pending()) {
                    // Only this test's observed, stopped child can settle its deliberate lost-result fixture.
                    assertThat(heldProof).isNotNull();
                    assertThat(heldProof.matches(build.id(), UUID.fromString(latest("executionId")),
                            latest("operation").equals("MAVEN_PACKAGE") ? backend : frontend)).isTrue();
                    jdbc.update("UPDATE execution_builds SET execution_pending=false WHERE id=?", build.id());
                }
                jdbc.update("""
                        UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,
                            queue_slot=null,worker_slot=null,finished_at=now()
                        WHERE id=? AND finished_at IS NULL AND NOT execution_pending
                        """, build.id());
            }
        } finally { try { if (paths != null) paths.close(); } finally { if (source != null) source.close(); } }
    }

    @Test void javaIntentCommitsBeforeLaunchAndCompletionStillNeedsArtifactSealing() throws Exception {
        completeFrontend();
        var tools = ExecutionToolchain.capture(ExecutionToolchainTest.installation(temporary.toRealPath()), List.of(), deadline());
        var home = ExecutionToolHome.create(paths, task, deadline());
        var pipeline = new ExecutionJava(paths, task, tools, home, () -> {}, (invocation, budget, cancelled, id, execution, preflight) -> {
            assertThat(pending()).isTrue(); assertThat(status()).isEqualTo("PACKAGING");
            assertThat(latest("operation")).isEqualTo("MAVEN_PACKAGE");
            assertThat(latest("executionId")).isEqualTo(execution.toString());
            assertThat(latest("phase")).isEqualTo("started");
            var result = new ExecutionProcess().run(child(backend, ExecutionJavaTest.Fixture.class, "success"),
                    budget, cancelled, id, execution, preflight);
            heldProof = result.stopped(); return result;
        });
        assertThat(pipeline.run(build.id(), deadline(), () -> false, journal)).isEqualTo(new ExecutionJavaReport.Summary(1, 1));
        assertThat(status()).isEqualTo("PACKAGING"); assertThat(pending()).isFalse();
        assertThat(workerSlot()).isEqualTo(1);
        assertThat(latest("phase")).isEqualTo("completed");
        assertThat(latest("tests")).isEqualTo("1"); assertThat(latest("files")).isEqualTo("1");
        assertThat(latest("suites")).isNull();
        assertThat(backend.resolve("target/app.jar")).exists();
        assertThat(String.join("", jdbc.queryForList("SELECT payload::text FROM execution_build_events WHERE build_id=?",
                String.class, build.id()))).doesNotContain("CANARY", temporary.toString(), "jdbc:");
        assertThatThrownBy(() -> journal.begin(UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT event_bytes FROM execution_builds WHERE id=?", Integer.class, build.id()))
                .isEqualTo(jdbc.queryForObject("SELECT sum(octet_length(payload::text))::integer FROM execution_build_events WHERE build_id=?",
                        Integer.class, build.id()));
    }

    @Test void requiresCompletedFrontendFreshExecutionAndTheJavaEntryPoint() throws Exception {
        assertThatThrownBy(() -> journal.begin(UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        UUID previous = completeFrontend();
        assertThatThrownBy(() -> journal.begin(previous)).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThatThrownBy(() -> journal.begin((UUID) null)).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThatThrownBy(() -> journal.begin(MAVEN_PACKAGE, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx ->
                assertThatThrownBy(() -> journal.begin(UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE"));
        assertThat(pending()).isFalse(); assertThat(status()).isEqualTo("CHECKING");
    }

    @Test void missingStopRetainsCapacityAndCannotBeReplayedAsSuccess() throws Exception {
        completeFrontend(); UUID execution = UUID.randomUUID(); journal.begin(execution);
        heldProof = proof(build.id(), execution, backend); // Deliberately lose only the persisted result.
        journal.finish(new ExecutionJava.Stage(execution, "EXECUTION_PROCESS_UNSETTLED", null, null, null));
        assertThat(status()).isEqualTo("NEEDS_RECONCILIATION"); assertThat(pending()).isTrue();
        assertThat(workerSlot()).isEqualTo(1);
        assertThatThrownBy(() -> journal.begin(UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThatThrownBy(() -> journal.finish(success(execution))).hasMessage("EXECUTION_STATE_UNAVAILABLE");
    }

    @Test void rejectsWrongBuildExecutionOrFrontendProofButAllowsCleanupAfterPermissionLoss() throws Exception {
        completeFrontend(); UUID execution = UUID.randomUUID(); journal.begin(execution);
        heldProof = proof(build.id(), execution, backend);
        for (var wrong : List.of(proof(UUID.randomUUID(), execution, backend),
                proof(build.id(), UUID.randomUUID(), backend), proof(build.id(), execution, frontend))) {
            assertThatThrownBy(() -> journal.finish(new ExecutionJava.Stage(execution, null, wrong,
                    new ExecutionProcess.Output(0, 0), new ExecutionJavaReport.Summary(1, 1))))
                    .hasMessage("EXECUTION_STATE_UNAVAILABLE");
            assertThat(pending()).isTrue(); assertThat(latest("phase")).isEqualTo("started");
        }
        assertThatThrownBy(() -> journal.finish(new ExecutionFrontend.Stage(MAVEN_PACKAGE, execution, null,
                heldProof, new ExecutionProcess.Output(0, 0), null))).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        configuration.withProperty("atoms.project-execution.enabled", "false");
        projects.archive(owner, build.projectId().toString(), true);
        journal.finish(new ExecutionJava.Stage(execution, "EXECUTION_CANCELLED", heldProof, null, null));
        assertThat(status()).isEqualTo("CANCELLED"); assertThat(pending()).isFalse(); assertThat(workerSlot()).isNull();
    }

    @Test void failedTransactionsRetainIntentAndLateCancellationWins() throws Exception {
        completeFrontend();
        int sequence = jdbc.queryForObject("SELECT last_event_seq FROM execution_builds WHERE id=?", Integer.class, build.id());
        assertThatThrownBy(() -> journal(failingEvents()).begin(UUID.randomUUID()))
                .hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
        assertThat(status()).isEqualTo("CHECKING"); assertThat(pending()).isFalse();
        assertThat(jdbc.queryForObject("SELECT last_event_seq FROM execution_builds WHERE id=?", Integer.class, build.id())).isEqualTo(sequence);
        UUID execution = UUID.randomUUID(); journal.begin(execution); heldProof = proof(build.id(), execution, backend);
        assertThatThrownBy(() -> journal(failingEvents()).finish(success(execution)))
                .hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
        assertThat(pending()).isTrue(); assertThat(workerSlot()).isEqualTo(1);
        assertThat(latest("phase")).isEqualTo("started");
        jdbc.update("UPDATE execution_builds SET cancel_requested=true WHERE id=?", build.id());
        journal.finish(success(execution));
        assertThat(status()).isEqualTo("CANCELLED"); assertThat(pending()).isFalse(); assertThat(workerSlot()).isNull();
        assertThat(latest("code")).isEqualTo("EXECUTION_CANCELLED");
    }

    @Test void rejectsInvalidTestCountsMissingEvidenceAndUnboundedDiagnostics() throws Exception {
        completeFrontend(); UUID execution = UUID.randomUUID(); journal.begin(execution);
        heldProof = proof(build.id(), execution, backend);
        for (var counts : List.of(new ExecutionJavaReport.Summary(0, 1), new ExecutionJavaReport.Summary(513, 513),
                new ExecutionJavaReport.Summary(1, 0), new ExecutionJavaReport.Summary(1, 10001), new ExecutionJavaReport.Summary(2, 1)))
            assertThatThrownBy(() -> journal.finish(new ExecutionJava.Stage(execution, null, heldProof,
                    new ExecutionProcess.Output(0, 0), counts))).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        for (var invalid : List.of(new ExecutionJava.Stage(execution, null, heldProof, null, new ExecutionJavaReport.Summary(1, 1)),
                new ExecutionJava.Stage(execution, null, heldProof, new ExecutionProcess.Output(0, 0), null),
                new ExecutionJava.Stage(execution, "PRIVATE_CODE_CANARY", heldProof, null, null),
                new ExecutionJava.Stage(execution, null, heldProof, new ExecutionProcess.Output(-1, 0), new ExecutionJavaReport.Summary(1, 1)),
                new ExecutionJava.Stage(execution, null, heldProof, new ExecutionProcess.Output(0, ExecutionProcess.STREAM_MAX_BYTES + 1), new ExecutionJavaReport.Summary(1, 1))))
            assertThatThrownBy(() -> journal.finish(invalid)).hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
        assertThat(pending()).isTrue(); assertThat(latest("phase")).isEqualTo("started");
        journal.finish(new ExecutionJava.Stage(execution, "EXECUTION_TIMEOUT", heldProof, null, null));
        assertThat(status()).isEqualTo("TIMED_OUT"); assertThat(workerSlot()).isNull(); assertThat(pending()).isFalse();
    }

    @Test void disabledArchivedCancelledOrMissingBackendCannotCreateIntent() throws Exception {
        completeFrontend();
        configuration.withProperty("atoms.project-execution.enabled", "false");
        assertThatThrownBy(() -> journal.begin(UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        configuration.withProperty("atoms.project-execution.enabled", "true");
        configuration.withProperty("atoms.project-execution.maintainer-ids", UUID.randomUUID().toString());
        assertThatThrownBy(() -> journal.begin(UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        configuration.withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        projects.archive(owner, build.projectId().toString(), true);
        assertThatThrownBy(() -> journal.begin(UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        projects.archive(owner, build.projectId().toString(), false);
        Path moved = backend.resolveSibling("backend.saved"); Files.move(backend, moved);
        assertThatThrownBy(() -> journal.begin(UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        Files.move(moved, backend);
        jdbc.update("UPDATE execution_builds SET cancel_requested=true WHERE id=?", build.id());
        assertThatThrownBy(() -> journal.begin(UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(pending()).isFalse(); assertThat(status()).isEqualTo("CHECKING");
    }

    @Test void concurrentJavaIntentsOnlyAdmitOneExecution() throws Exception {
        completeFrontend(); UUID execution = UUID.randomUUID();
        var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> attempt = () -> {
            start.await();
            try { journal(jdbc).begin(execution); return true; } catch (IOException rejected) { return false; }
        };
        try {
            var first = threads.submit(attempt); var second = threads.submit(attempt); start.countDown();
            assertThat(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            heldProof = proof(build.id(), execution, backend);
            journal.finish(new ExecutionJava.Stage(execution, "EXECUTION_PROCESS_FAILED", heldProof, null, null));
            assertThat(status()).isEqualTo("FAILED"); assertThat(pending()).isFalse(); assertThat(workerSlot()).isNull();
        } finally { threads.shutdownNow(); assertThat(threads.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }

    private ExecutionJava.Stage success(UUID execution) {
        return new ExecutionJava.Stage(execution, null, heldProof, new ExecutionProcess.Output(0, 0), new ExecutionJavaReport.Summary(1, 1));
    }
    private JdbcTemplate failingEvents() {
        return new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public int update(String sql, Object... args) {
                if (sql.contains("INSERT INTO execution_build_events")) throw new IllegalStateException("PRIVATE_DB_CANARY");
                return super.update(sql, args);
            }
        };
    }

    private UUID completeFrontend() throws IOException {
        UUID execution = null;
        for (var operation : List.of(NPM_CI, TYPECHECK, LINT, UNIT_TEST, FRONTEND_BUILD)) {
            execution = UUID.randomUUID(); journal.begin(operation, execution);
            heldProof = proof(build.id(), execution, frontend);
            journal.finish(new ExecutionFrontend.Stage(operation, execution, null, heldProof,
                    new ExecutionProcess.Output(0, 0), operation == UNIT_TEST ? new ExecutionDiagnostics.Frontend(1, 1, 1) : null));
        }
        return execution;
    }
    private ExecutionBuildJournal journal(JdbcTemplate database) throws IOException {
        return new ExecutionBuildJournal(new ExecutionSettings(configuration), projects, database, transactions, build, paths, task);
    }
    private OwnedProcess.Stopped proof(UUID id, UUID execution, Path cwd) throws IOException {
        return new ExecutionProcess().run(child(cwd, ExecutionFrontendTest.Fixture.class, "noop"), deadline(),
                () -> false, id, execution, () -> {}).stopped();
    }
    private ExecutionProcess.Invocation child(Path cwd, Class<?> fixture, String mode) throws IOException {
        Path java = Path.of(System.getProperty("java.home"));
        return new ExecutionProcess.Invocation(java.resolve("bin/java"), List.of("-cp",
                Path.of("target/test-classes").toAbsolutePath().toString(), fixture.getName(), mode), cwd, cwd, cwd, java, java.resolve("bin"));
    }
    private String latest(String field) {
        return jdbc.queryForObject("SELECT payload->>? FROM execution_build_events WHERE build_id=? AND payload->>'operation' IS NOT NULL ORDER BY seq DESC LIMIT 1",
                String.class, field, build.id());
    }
    private String status() { return jdbc.queryForObject("SELECT status FROM execution_builds WHERE id=?", String.class, build.id()); }
    private boolean pending() { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT execution_pending FROM execution_builds WHERE id=?", Boolean.class, build.id())); }
    private Integer workerSlot() { return jdbc.queryForObject("SELECT worker_slot FROM execution_builds WHERE id=?", Integer.class, build.id()); }
    private ExecutionProcess.Deadline deadline() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
}
