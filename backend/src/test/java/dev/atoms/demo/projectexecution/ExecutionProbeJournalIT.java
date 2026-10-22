package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
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
import static dev.atoms.demo.projectexecution.ExecutionToolchain.Kind.*;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false",
        "atoms.generation.worker-enabled=false"})
class ExecutionProbeJournalIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), version = UUID.randomUUID();
    ExecutionBuild build;
    ExecutionPaths paths;
    ExecutionPaths.Task task;
    ExecutionToolHome home;
    ExecutionToolchain tools;
    MockEnvironment config;
    OwnedProcess.Stopped stopped;

    @BeforeEach void setup() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("atoms_demo_test");
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'probe-journal-test')", project, owner);
        jdbc.update("INSERT INTO project_sources(project_id) VALUES (?)", project);
        jdbc.update("""
                INSERT INTO source_versions(id,project_id,version_no,commit_id,tree_sha256,file_count,tree_bytes)
                VALUES (?,?,1,?,?,1,1)
                """, version, project, "a".repeat(40), "a".repeat(64));
        config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        for (String key : List.of("root", "node", "npm", "java", "maven", "policy"))
            config.withProperty("atoms.project-execution." + key, temporary.resolve("unused-" + key).toString());
        var repository = new ExecutionBuildRepository(jdbc);
        build = new TransactionTemplate(transactions).execute(tx -> repository.insert(owner, project,
                ExecutionBuild.request(version, UUID.randomUUID().toString()), "a".repeat(64), new ExecutionSettings(config).load()));
        build = new ExecutionBuildClaim(new ExecutionSettings(config), projects, repository, jdbc, transactions)
                .claim(owner, project, build.id());
        paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of());
        task = paths.createBuild(build.id());
        home = ExecutionToolHome.create(paths, task, budget());
        tools = ExecutionToolchain.capture(ExecutionToolchainTest.installation(temporary.toRealPath()), List.of(), budget());
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (build != null) {
                if (Boolean.TRUE.equals(row().get("execution_pending"))) {
                    // Only the deliberately lost-result fixture is reconciled, using its real observed stop.
                    assertThat(stopped).isNotNull();
                    String pending = jdbc.queryForObject("""
                            SELECT payload->>'executionId' FROM execution_build_events
                            WHERE build_id=? AND payload->>'probe' IS NOT NULL ORDER BY seq DESC LIMIT 1
                            """, String.class, build.id());
                    assertThat(stopped.matches(build.id(), UUID.fromString(pending), home.home())).isTrue();
                    jdbc.update("UPDATE execution_builds SET execution_pending=false WHERE id=?", build.id());
                }
                jdbc.update("""
                        UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,
                            queue_slot=null,worker_slot=null,finished_at=now()
                        WHERE id=? AND finished_at IS NULL AND NOT execution_pending
                        """, build.id());
            }
        } finally { if (paths != null) paths.close(); }
    }

    @Test void recordsFourOrderedStoppedProbesWithoutClaimingBuildSuccess() throws Exception {
        var journal = journal(jdbc);
        for (var kind : ExecutionToolchain.Kind.values()) {
            UUID execution = UUID.randomUUID(); journal.begin(kind, execution);
            assertThat(row().get("execution_pending")).isEqualTo(true);
            journal.finish(kind, execution, result(kind), stop(execution, home.home()), null);
            assertThat(row().get("execution_pending")).isEqualTo(false);
        }
        var state = row();
        assertThat(state.get("status")).isEqualTo("PREPARING");
        assertThat(state.get("worker_slot")).isNotNull();
        assertThat(state.get("jar_sha256")).isNull();
        assertThat(events()).hasSize(8).allSatisfy(event -> assertThat(event)
                .doesNotContain(home.home().toString(), owner.toString(), "PRIVATE_CANARY", "operation"));
        assertThat(state.get("last_event_seq")).isEqualTo(jdbc.queryForObject(
                "SELECT count(*) FROM execution_build_events WHERE build_id=?", Integer.class, build.id()));
        assertThat(state.get("event_bytes")).isEqualTo(jdbc.queryForObject(
                "SELECT sum(octet_length(payload::text))::integer FROM execution_build_events WHERE build_id=?", Integer.class, build.id()));
        assertThatThrownBy(() -> journal.begin(NODE, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(row()).isEqualTo(state);
    }

    @Test void rejectsOutOfOrderDuplicateAndWrongStopProofWithoutClearingIntent() throws Exception {
        var journal = journal(jdbc);
        assertThatThrownBy(() -> journal.begin(NPM, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        UUID execution = UUID.randomUUID(); journal.begin(NODE, execution);
        var before = row();
        var wrong = stop(UUID.randomUUID(), home.home());
        assertThatThrownBy(() -> journal.finish(NODE, execution, result(NODE), wrong, null))
                .hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(row()).isEqualTo(before);
        assertThatThrownBy(() -> journal.begin(NODE, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        journal.finish(NODE, execution, result(NODE), stop(execution, home.home()), null);
        assertThatThrownBy(() -> journal.begin(NPM, execution)).hasMessage("EXECUTION_STATE_UNAVAILABLE");
    }

    @Test void missingStopProofRetainsPendingAndCapacityDespiteCancellation() throws Exception {
        var journal = journal(jdbc); UUID execution = UUID.randomUUID(); journal.begin(NODE, execution);
        stop(execution, home.home()); // The test withholds a real proof; cleanup alone may use it.
        jdbc.update("UPDATE execution_builds SET cancel_requested=true WHERE id=?", build.id());
        journal.finish(NODE, execution, null, null, "EXECUTION_CANCELLED");
        assertThat(row()).containsEntry("status", "NEEDS_RECONCILIATION")
                .containsEntry("execution_pending", true).containsEntry("error_code", "EXECUTION_PROCESS_UNSETTLED");
        assertThat(row().get("worker_slot")).isNotNull(); assertThat(row().get("finished_at")).isNull();
        assertThatThrownBy(() -> journal.begin(NPM, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
    }

    @Test void explicitUncertaintyDoesNotReleaseCapacityEvenWhenAStopWasSupplied() throws Exception {
        var journal = journal(jdbc); UUID execution = UUID.randomUUID(); journal.begin(NODE, execution);
        journal.finish(NODE, execution, null, stop(execution, home.home()), "EXECUTION_PROCESS_UNSETTLED");
        assertThat(row()).containsEntry("status", "NEEDS_RECONCILIATION").containsEntry("execution_pending", true);
        assertThat(row().get("worker_slot")).isNotNull();
    }

    @Test void rejectsMismatchedToolDigestUnsupportedVersionAndUnboundedText() throws Exception {
        var journal = journal(jdbc); UUID execution = UUID.randomUUID(); journal.begin(NODE, execution);
        var proof = stop(execution, home.home()); var before = row();
        var variants = new ArrayList<ExecutionToolProbe.Version>();
        variants.add(null); variants.add(result(NPM));
        variants.add(new ExecutionToolProbe.Version(NODE, "24.20.0", new ExecutionToolFiles.Summary("b".repeat(64), 1, 4)));
        for (String value : Arrays.asList(null, "25.0.0", "24.20.0\nPRIVATE_CANARY", "24.20.0\n", "x".repeat(100)))
            variants.add(new ExecutionToolProbe.Version(NODE, value, tools.identity(NODE)));
        for (var variant : variants) {
            assertThatThrownBy(() -> journal.finish(NODE, execution, variant, proof, null))
                    .hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
            assertThat(row()).isEqualTo(before);
        }
        assertThatThrownBy(() -> journal.finish(NODE, execution, null, proof, "PRIVATE_CANARY"))
                .hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
        assertThatThrownBy(() -> journal.finish(NPM, execution, result(NPM), proof, null)).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        var wrongCwd = stop(execution, paths.project(task));
        assertThatThrownBy(() -> journal.finish(NODE, execution, result(NODE), wrongCwd, null)).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        stopped = proof;
        journal.finish(NODE, execution, result(NODE), proof, null);
    }

    @Test void revocationStopsNewIntentsButDoesNotPreventOwnedProcessCleanup() throws Exception {
        var journal = journal(jdbc); var before = row();
        for (String key : List.of("enabled", "maintainer-ids")) {
            config.withProperty("atoms.project-execution." + key, key.equals("enabled") ? "false" : UUID.randomUUID().toString());
            assertThatThrownBy(() -> journal.begin(NODE, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
            config.withProperty("atoms.project-execution." + key, key.equals("enabled") ? "true" : owner.toString());
        }
        projects.archive(owner, project.toString(), true);
        assertThatThrownBy(() -> journal.begin(NODE, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        projects.archive(owner, project.toString(), false);
        UUID other = UUID.randomUUID(); jdbc.update("INSERT INTO guest_users(id) VALUES (?)", other);
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", other, project);
        assertThatThrownBy(() -> journal.begin(NODE, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", owner, project);
        assertThat(row()).isEqualTo(before);
        UUID execution = UUID.randomUUID(); journal.begin(NODE, execution); var proof = stop(execution, home.home());
        config.withProperty("atoms.project-execution.enabled", "false");
        journal.finish(NODE, execution, null, proof, "EXECUTION_CHECK_FAILED");
        assertThat(row()).containsEntry("status", "FAILED").containsEntry("execution_pending", false).containsEntry("worker_slot", null);
    }

    @Test void cancellationWinsOverLateSuccessAndTerminalStateCannotBeRewritten() throws Exception {
        var journal = journal(jdbc); UUID execution = UUID.randomUUID(); journal.begin(NODE, execution);
        var proof = stop(execution, home.home());
        jdbc.update("UPDATE execution_builds SET cancel_requested=true WHERE id=?", build.id());
        journal.finish(NODE, execution, result(NODE), proof, null);
        assertThat(row()).containsEntry("status", "CANCELLED").containsEntry("execution_pending", false)
                .containsEntry("worker_slot", null).containsEntry("error_code", "EXECUTION_CANCELLED");
        var terminal = row();
        assertThatThrownBy(() -> journal.finish(NODE, execution, result(NODE), proof, null)).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(row()).isEqualTo(terminal);
    }

    @Test void timeoutReleasesOnlyAfterMatchingStopProof() throws Exception {
        var journal = journal(jdbc); UUID execution = UUID.randomUUID(); journal.begin(NODE, execution);
        journal.finish(NODE, execution, null, stop(execution, home.home()), "EXECUTION_TIMEOUT");
        assertThat(row()).containsEntry("status", "TIMED_OUT").containsEntry("execution_pending", false).containsEntry("worker_slot", null);
    }

    @Test void eventPersistenceFailureRollsBackBothBeginAndCompletionWithoutRetry() throws Exception {
        var broken = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public int update(String sql, Object... arguments) {
                int count = super.update(sql, arguments);
                if (sql.startsWith("INSERT INTO execution_build_events")) throw new IllegalStateException("PRIVATE_SQL_CANARY");
                return count;
            }
        };
        var brokenJournal = journal(broken); var before = row(); UUID execution = UUID.randomUUID();
        assertThatThrownBy(() -> brokenJournal.begin(NODE, execution)).hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
        assertThat(row()).isEqualTo(before); assertThat(events()).isEmpty();
        journal(jdbc).begin(NODE, execution); var proof = stop(execution, home.home()); before = row(); var originalEvents = events();
        assertThatThrownBy(() -> brokenJournal.finish(NODE, execution, result(NODE), proof, null))
                .hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
        assertThat(row()).isEqualTo(before); assertThat(events()).isEqualTo(originalEvents);
        // No production retry; teardown alone reconciles this deliberately lost completion with actual proof.
    }

    @Test void concurrentBeginsHaveExactlyOneWinner() throws Exception {
        var journal = journal(jdbc); var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        Callable<UUID> begin = () -> {
            start.await(); UUID execution = UUID.randomUUID();
            try { journal.begin(NODE, execution); return execution; }
            catch (IOException error) { assertThat(error).hasMessage("EXECUTION_STATE_UNAVAILABLE"); return null; }
        };
        try {
            var first = pool.submit(begin); var second = pool.submit(begin); start.countDown();
            var winners = Arrays.asList(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(winners.stream().filter(Objects::nonNull).count()).isEqualTo(1);
            UUID winner = winners.stream().filter(Objects::nonNull).findFirst().orElseThrow();
            assertThat(events()).hasSize(1);
            journal.finish(NODE, winner, result(NODE), stop(winner, home.home()), null);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void permissionIsRecheckedAfterBothLocksAreAcquired() throws Exception {
        var revokeAtLock = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public Map<String, Object> queryForMap(String sql, Object... arguments) {
                var result = super.queryForMap(sql, arguments);
                if (sql.contains("execution_builds") && sql.contains("FOR UPDATE"))
                    config.withProperty("atoms.project-execution.maintainer-ids", UUID.randomUUID().toString());
                return result;
            }
        };
        var journal = journal(revokeAtLock); var before = row();
        assertThatThrownBy(() -> journal.begin(NODE, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(row()).isEqualTo(before); assertThat(events()).isEmpty();
    }

    @Test void refusesAmbientTransactionsAndMismatchedBuildBindings() throws Exception {
        var journal = journal(jdbc); var before = row();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThatThrownBy(() -> journal(jdbc)).hasMessage("EXECUTION_STATE_UNAVAILABLE");
            assertThatThrownBy(() -> journal.begin(NODE, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
            assertThatThrownBy(() -> journal.finish(NODE, UUID.randomUUID(), null, null, null)).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        });
        var wrong = new ExecutionBuild(build.id(), project, owner, UUID.randomUUID(), build.treeSha256(),
                build.keySha256(), build.requestSha256(), build.status(), false, null, build.createdAt(), null, build.lastEventSeq());
        var wrongJournal = new ExecutionProbeJournal(new ExecutionSettings(config), projects, jdbc, transactions,
                wrong, paths, task, home, tools);
        assertThatThrownBy(() -> wrongJournal.begin(NODE, UUID.randomUUID())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        var other = paths.createBuild(UUID.randomUUID());
        assertThatThrownBy(() -> new ExecutionProbeJournal(new ExecutionSettings(config), projects, jdbc, transactions,
                build, paths, other, home, tools)).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(row()).isEqualTo(before);
    }

    private ExecutionProbeJournal journal(JdbcTemplate database) throws Exception {
        return new ExecutionProbeJournal(new ExecutionSettings(config), projects, database, transactions,
                build, paths, task, home, tools);
    }
    private ExecutionToolProbe.Version result(ExecutionToolchain.Kind kind) {
        return new ExecutionToolProbe.Version(kind, switch (kind) {
            case NODE -> "24.20.0"; case NPM -> "11.19.0"; case JAVA -> "17.0.14"; case MAVEN -> "3.9.11";
        }, tools.identity(kind));
    }
    private OwnedProcess.Stopped stop(UUID execution, Path cwd) throws Exception {
        Path java = Path.of(System.getProperty("java.home"));
        var invocation = new ExecutionProcess.Invocation(java.resolve("bin/java"), List.of("-cp",
                Path.of("target/test-classes").toAbsolutePath().toString(), ExecutionFrontendTest.Fixture.class.getName(), "noop"),
                cwd, cwd, cwd, java, java.resolve("bin"));
        stopped = new ExecutionProcess().run(invocation, budget(), () -> false, build.id(), execution, () -> {}).stopped();
        return stopped;
    }
    private Map<String, Object> row() { return jdbc.queryForMap("SELECT * FROM execution_builds WHERE id=?", build.id()); }
    private List<String> events() { return jdbc.queryForList("""
            SELECT payload::text FROM execution_build_events WHERE build_id=? AND payload->>'probe' IS NOT NULL ORDER BY seq
            """, String.class, build.id()); }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
}
