package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.SourceAgentFixture;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static dev.atoms.demo.projectexecution.ExecutionToolchain.Operation.*;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false",
        "atoms.generation.worker-enabled=false"})
class ExecutionFrontendJournalIT {
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
    Path frontend;
    OwnedProcess.Stopped heldProof;
    UUID heldExecution;

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
        UUID id = service.submit(owner, base.projectId(), base.versionId(), "frontend").id();
        // Test-only worker claim. Production coordinator/claim is EX10–12, not provided by the journal.
        jdbc.update("UPDATE execution_builds SET status='PREPARING',queue_slot=null,worker_slot=1 WHERE id=?", id);
        build = repository.get(base.projectId(), id);
        paths = new ExecutionPaths(root.resolve("execution"), List.of());
        task = paths.createBuild(build.id());
        frontend = ExecutionRootLease.directory(paths.project(task).resolve("frontend"));
        journal = journal(jdbc);
    }
    private ExecutionBuildJournal journal(JdbcTemplate database) throws IOException {
        return new ExecutionBuildJournal(new ExecutionSettings(configuration), projects, database, transactions, build, paths, task);
    }
    @AfterEach void close() throws Exception {
        try {
            if (build != null) {
                boolean pending = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT execution_pending FROM execution_builds WHERE id=?", Boolean.class, build.id()));
                if (pending) {
                    // Only our deliberate lost-result fixture may be reconciled, with its real held stop proof.
                    assertThat(heldProof).isNotNull();
                    assertThat(heldProof.matches(build.id(), heldExecution, frontend)).isTrue();
                    jdbc.update("UPDATE execution_builds SET execution_pending=false WHERE id=?", build.id());
                }
                jdbc.update("""
                        UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,
                            queue_slot=null,worker_slot=null,finished_at=now()
                        WHERE id=? AND finished_at IS NULL AND NOT execution_pending
                        """, build.id());
            }
        } finally {
            try { if (paths != null) paths.close(); } finally { if (source != null) source.close(); }
        }
    }

    @Test void durableIntentsPrecedeProcessesAndFrontendCompletionIsNotWholeBuildSuccess() throws Exception {
        var tools = ExecutionToolchain.capture(ExecutionToolchainTest.installation(temporary.toRealPath()), List.of(), deadline());
        var home = ExecutionToolHome.create(paths, task, deadline());
        var pipeline = new ExecutionFrontend(paths, task, tools, home, () -> {}, (invocation, budget, cancelled, id, execution, preflight) -> {
            assertThat(pending()).isTrue();
            assertThat(latest("phase")).isEqualTo("started");
            assertThat(latest("executionId")).isEqualTo(execution.toString());
            String mode = latest("operation").equals("UNIT_TEST") ? "success" : "noop";
            var result = new ExecutionProcess().run(child(mode), budget, cancelled, id, execution, preflight);
            heldProof = result.stopped(); heldExecution = execution;
            return result;
        });
        assertThat(pipeline.run(build.id(), deadline(), () -> false, journal).tests()).isEqualTo(1);
        assertThat(status()).isEqualTo("CHECKING");
        assertThat(pending()).isFalse();
        assertThat(jdbc.queryForObject("SELECT worker_slot FROM execution_builds WHERE id=?", Integer.class, build.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_build_events WHERE build_id=? AND payload->>'operation' IS NOT NULL",
                Integer.class, build.id())).isEqualTo(10);
        var events = jdbc.queryForList("SELECT payload::text FROM execution_build_events WHERE build_id=? ORDER BY seq", String.class, build.id());
        assertThat(String.join("", events)).doesNotContain("CANARY", temporary.toString(), "jdbc:", "title", "failureMessages");
        assertThat(jdbc.queryForObject("SELECT event_bytes FROM execution_builds WHERE id=?", Integer.class, build.id()))
                .isEqualTo(jdbc.queryForObject("SELECT sum(octet_length(payload::text))::integer FROM execution_build_events WHERE build_id=?",
                        Integer.class, build.id()));
        assertThatThrownBy(() -> journal.begin(FRONTEND_BUILD, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
    }

    @Test void concurrentBeginAndRetryCannotCreateAnotherExecution() throws Exception {
        UUID execution = UUID.randomUUID();
        var threads = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            start.await();
            try { journal(jdbc).begin(NPM_CI, execution); return true; }
            catch (IOException expected) { return false; }
        };
        try {
            var first = threads.submit(attempt); var second = threads.submit(attempt); start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(pending()).isTrue();
            var proof = stopped(execution);
            journal.finish(new ExecutionFrontend.Stage(NPM_CI, execution, "EXECUTION_PROCESS_FAILED", proof, null, null));
            assertThat(status()).isEqualTo("FAILED");
            assertThat(pending()).isFalse();
        } finally { threads.shutdownNow(); assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void missingStopKeepsCapacityAndCannotBeReplayedAsStopped() throws Exception {
        UUID execution = UUID.randomUUID();
        journal.begin(NPM_CI, execution);
        stopped(execution); // Real process settled; simulate its result being lost at the persistence boundary.
        journal.finish(new ExecutionFrontend.Stage(NPM_CI, execution, "EXECUTION_PROCESS_UNSETTLED", null, null, null));
        assertThat(status()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(pending()).isTrue();
        assertThat(jdbc.queryForObject("SELECT worker_slot FROM execution_builds WHERE id=?", Integer.class, build.id())).isEqualTo(1);
        assertThatThrownBy(() -> journal.begin(NPM_CI, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThatThrownBy(() -> journal.finish(new ExecutionFrontend.Stage(NPM_CI, execution, null, heldProof,
                new ExecutionProcess.Output(0, 0), null))).hasMessage("EXECUTION_STATE_UNAVAILABLE");
    }

    @Test void wrongProofCannotReleasePendingButCorrectProofAllowsCleanupAfterAuthorizationLoss() throws Exception {
        UUID execution = UUID.randomUUID();
        journal.begin(NPM_CI, execution);
        var wrong = stopped(UUID.randomUUID());
        assertThatThrownBy(() -> journal.finish(new ExecutionFrontend.Stage(NPM_CI, execution, null, wrong,
                new ExecutionProcess.Output(0, 0), null))).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(pending()).isTrue();
        var right = stopped(execution);
        configuration.withProperty("atoms.project-execution.enabled", "false");
        projects.archive(owner, build.projectId().toString(), true);
        journal.finish(new ExecutionFrontend.Stage(NPM_CI, execution, "EXECUTION_CANCELLED", right, null, null));
        assertThat(status()).isEqualTo("CANCELLED");
        assertThat(pending()).isFalse();
    }

    @Test void authorizationCancellationOrderingAndAmbientTransactionBlockNewIntent() throws Exception {
        configuration.withProperty("atoms.project-execution.enabled", "false");
        assertThatThrownBy(() -> journal.begin(NPM_CI, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        configuration.withProperty("atoms.project-execution.enabled", "true");
        projects.archive(owner, build.projectId().toString(), true);
        assertThatThrownBy(() -> journal.begin(NPM_CI, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        projects.archive(owner, build.projectId().toString(), false);
        assertThatThrownBy(() -> journal.begin(UNIT_TEST, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                assertThatThrownBy(() -> journal.begin(NPM_CI, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE"));
        jdbc.update("UPDATE execution_builds SET cancel_requested=true WHERE id=?", build.id());
        assertThatThrownBy(() -> journal.begin(NPM_CI, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(pending()).isFalse();
        assertThat(status()).isEqualTo("PREPARING");
    }

    @Test void eventPersistenceFailureRollsBackStageAndIntentTogether() throws Exception {
        int before = jdbc.queryForObject("SELECT last_event_seq FROM execution_builds WHERE id=?", Integer.class, build.id());
        assertThatThrownBy(() -> journal(failingEvents()).begin(NPM_CI, UUID.randomUUID()))
                .hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
        assertThat(status()).isEqualTo("PREPARING");
        assertThat(pending()).isFalse();
        assertThat(jdbc.queryForObject("SELECT last_event_seq FROM execution_builds WHERE id=?", Integer.class, build.id())).isEqualTo(before);
    }

    @Test void completionEventFailureRetainsIntentAndCancellationWinsLateSuccess() throws Exception {
        UUID execution = UUID.randomUUID();
        journal.begin(NPM_CI, execution);
        var proof = stopped(execution);
        var completion = new ExecutionFrontend.Stage(NPM_CI, execution, null, proof, new ExecutionProcess.Output(0, 0), null);
        assertThatThrownBy(() -> journal(failingEvents()).finish(completion))
                .hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
        assertThat(pending()).isTrue();
        assertThat(latest("phase")).isEqualTo("started");
        jdbc.update("UPDATE execution_builds SET cancel_requested=true WHERE id=?", build.id());
        journal.finish(completion);
        assertThat(status()).isEqualTo("CANCELLED");
        assertThat(pending()).isFalse();
        assertThat(latest("phase")).isEqualTo("failed");
        assertThat(latest("code")).isEqualTo("EXECUTION_CANCELLED");
    }

    @Test void executionIdCannotBeReusedForTheNextOperation() throws Exception {
        UUID execution = UUID.randomUUID();
        journal.begin(NPM_CI, execution);
        journal.finish(new ExecutionFrontend.Stage(NPM_CI, execution, null, stopped(execution),
                new ExecutionProcess.Output(0, 0), null));
        assertThatThrownBy(() -> journal.begin(TYPECHECK, execution)).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(status()).isEqualTo("INSTALLING");
        assertThat(pending()).isFalse();
    }

    private JdbcTemplate failingEvents() {
        return new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public int update(String sql, Object... args) {
                if (sql.contains("INSERT INTO execution_build_events")) throw new IllegalStateException("PRIVATE_DB_CANARY");
                return super.update(sql, args);
            }
        };
    }
    private OwnedProcess.Stopped stopped(UUID execution) throws IOException {
        heldProof = new ExecutionProcess().run(child("noop"), deadline(), () -> false, build.id(), execution, () -> {}).stopped();
        heldExecution = execution;
        return heldProof;
    }
    private ExecutionProcess.Invocation child(String mode) throws IOException {
        Path java = Path.of(System.getProperty("java.home"));
        return new ExecutionProcess.Invocation(java.resolve("bin/java"),
                List.of("-cp", Path.of("target/test-classes").toAbsolutePath().toString(),
                        ExecutionFrontendTest.Fixture.class.getName(), mode), frontend, frontend, frontend, java, java.resolve("bin"));
    }
    private String latest(String field) {
        return jdbc.queryForObject("SELECT payload->>? FROM execution_build_events WHERE build_id=? AND payload->>'operation' IS NOT NULL ORDER BY seq DESC LIMIT 1",
                String.class, field, build.id());
    }
    private String status() { return jdbc.queryForObject("SELECT status FROM execution_builds WHERE id=?", String.class, build.id()); }
    private boolean pending() { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT execution_pending FROM execution_builds WHERE id=?", Boolean.class, build.id())); }
    private ExecutionProcess.Deadline deadline() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
}
