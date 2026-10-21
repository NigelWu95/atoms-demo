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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static dev.atoms.demo.projectexecution.ExecutionToolchain.Operation.*;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false",
        "atoms.generation.worker-enabled=false"})
class ExecutionBuildSettlementIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    static final String HASH = "a".repeat(64);
    final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), version = UUID.randomUUID();
    final List<ExecutionBuild> builds = new ArrayList<>();
    final Map<UUID, OwnedProcess.Stopped> stopped = new HashMap<>();
    ExecutionPaths paths;
    ExecutionBuildRepository repository;
    MockEnvironment config;

    @BeforeEach void setup() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("atoms_demo_test");
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'settlement-test')", project, owner);
        jdbc.update("INSERT INTO project_sources(project_id) VALUES (?)", project);
        jdbc.update("""
                INSERT INTO source_versions(id,project_id,version_no,commit_id,tree_sha256,file_count,tree_bytes)
                VALUES (?,?,1,?,?,1,1)
                """, version, project, "a".repeat(40), HASH);
        config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        for (String key : List.of("root", "node", "npm", "java", "maven", "policy"))
            config.withProperty("atoms.project-execution." + key, temporary.resolve("unused-" + key).toString());
        repository = new ExecutionBuildRepository(jdbc);
        paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of());
    }

    @AfterEach void close() throws Exception {
        try {
            for (var build : builds) {
                if (Boolean.TRUE.equals(row(build).get("execution_pending"))) {
                    var last = jdbc.queryForMap("""
                            SELECT payload->>'executionId' AS id,payload->>'operation' AS operation
                            FROM execution_build_events WHERE build_id=? AND payload->>'operation' IS NOT NULL
                            ORDER BY seq DESC LIMIT 1
                            """, build.id());
                    Path cwd = temporary.toRealPath().resolve("execution/builds/" + build.id() + "/project")
                            .resolve("MAVEN_PACKAGE".equals(last.get("operation")) ? "backend" : "frontend");
                    assertThat(stopped.get(build.id())).isNotNull();
                    assertThat(stopped.get(build.id()).matches(build.id(), UUID.fromString((String) last.get("id")), cwd)).isTrue();
                    // Test-only lost-result fixture: actual owned child was observed stopped. Never clear unknown processes.
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

    @Test void commitsSealedDigestsSuccessAndCapacityTogetherAndRejectsReplay() throws Exception {
        var fixture = claimed(); complete(fixture);
        var artifact = seal(fixture);
        var result = settlement(jdbc).succeed(fixture.build(), artifact, budget());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        var state = row(fixture.build());
        assertThat(state.get("jar_bytes")).isEqualTo(artifact.summary().jarBytes());
        assertThat(state.get("jar_sha256")).isEqualTo(artifact.summary().jarSha256());
        assertThat(state.get("manifest_sha256")).isEqualTo(artifact.summary().manifestSha256());
        assertThat(state.get("worker_slot")).isNull(); assertThat(state.get("finished_at")).isNotNull();
        assertThat(state.get("execution_pending")).isEqualTo(false);
        assertThatThrownBy(() -> settlement(jdbc).succeed(fixture.build(), artifact, budget()))
                .hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
        assertThat(row(fixture.build())).isEqualTo(state);
        assertThat(result.lastEventSeq()).isEqualTo(jdbc.queryForObject(
                "SELECT count(*) FROM execution_build_events WHERE build_id=?", Integer.class, result.id()));
        assertThat(state.get("event_bytes")).isEqualTo(jdbc.queryForObject(
                "SELECT sum(octet_length(payload::text))::integer FROM execution_build_events WHERE build_id=?", Integer.class, result.id()));
    }

    @Test void rejectsMissingStagesEvenWithPackagingStatusAndARealSealedHandle() throws Exception {
        var fixture = claimed();
        for (String status : List.of("INSTALLING", "CHECKING", "PACKAGING"))
            jdbc.update("UPDATE execution_builds SET status=? WHERE id=?", status, fixture.build().id());
        var artifact = seal(fixture); var before = row(fixture.build());
        assertThatThrownBy(() -> settlement(jdbc).succeed(fixture.build(), artifact, budget()))
                .hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(row(fixture.build())).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sealedCountsMustMatchCompletedFrontendAndJavaEvidence(boolean javaCounts) throws Exception {
        var fixture = claimed(); complete(fixture);
        var valid = evidence();
        var different = new ExecutionArtifact.Evidence(valid.tools(), valid.npm(), valid.maven(),
                javaCounts ? valid.frontend() : new ExecutionDiagnostics.Frontend(1, 2, 4),
                javaCounts ? new ExecutionJavaReport.Summary(1, 3) : valid.javaTests(), valid.resources());
        var artifact = seal(fixture, different); var before = row(fixture.build());
        assertThatThrownBy(() -> settlement(jdbc).succeed(fixture.build(), artifact, budget()))
                .hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(row(fixture.build())).isEqualTo(before);
    }

    @Test void rejectsArtifactTamperingAndDoesNotPublishItsDigest() throws Exception {
        var fixture = claimed(); complete(fixture); var artifact = seal(fixture);
        var time = Files.getLastModifiedTime(artifact.jar());
        Files.write(artifact.jar(), new byte[]{80, 75, 3, 4, 7, 8, 9});
        Files.setLastModifiedTime(artifact.jar(), time);
        var before = row(fixture.build());
        assertThatThrownBy(() -> settlement(jdbc).succeed(fixture.build(), artifact, budget()))
                .hasMessage("EXECUTION_ARTIFACT_INVALID").hasNoCause();
        assertThat(row(fixture.build())).isEqualTo(before);
        assertThat(settlement(jdbc).fail(fixture.build(), "EXECUTION_ARTIFACT_INVALID").status()).isEqualTo("FAILED");
        assertThat(row(fixture.build()).get("jar_sha256")).isNull();
    }

    @Test void aSealedHandleCannotBeReboundToAnotherBuildOwnerProjectOrSource() throws Exception {
        var fixture = claimed(); var artifact = seal(fixture); var original = fixture.build();
        for (String changed : List.of("id", "project", "owner", "version", "tree", "key", "request")) {
            var wrong = new ExecutionBuild(changed.equals("id") ? UUID.randomUUID() : original.id(),
                    changed.equals("project") ? UUID.randomUUID() : original.projectId(),
                    changed.equals("owner") ? UUID.randomUUID() : original.ownerId(),
                    changed.equals("version") ? UUID.randomUUID() : original.sourceVersionId(),
                    changed.equals("tree") ? "b".repeat(64) : original.treeSha256(),
                    changed.equals("key") ? "b".repeat(64) : original.keySha256(),
                    changed.equals("request") ? "b".repeat(64) : original.requestSha256(),
                    original.status(), false, null, original.createdAt(), null, original.lastEventSeq());
            assertThatThrownBy(() -> settlement(jdbc).succeed(wrong, artifact, budget()))
                    .as(changed).hasMessage("EXECUTION_ARTIFACT_INVALID");
            assertThatThrownBy(() -> settlement(jdbc).fail(wrong, "EXECUTION_CHECK_FAILED"))
                    .as(changed).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        }
        assertThat(row(original).get("status")).isEqualTo("PREPARING");
    }

    @Test void rechecksCurrentConfigurationOwnerArchiveAndCancellationAfterSealing() throws Exception {
        var fixture = claimed(); complete(fixture); var artifact = seal(fixture);
        var before = row(fixture.build());
        for (String key : List.of("enabled", "maintainer-ids")) {
            config.withProperty("atoms.project-execution." + key, key.equals("enabled") ? "false" : UUID.randomUUID().toString());
            assertThatThrownBy(() -> settlement(jdbc).succeed(fixture.build(), artifact, budget()))
                    .hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
            config.withProperty("atoms.project-execution." + key, key.equals("enabled") ? "true" : owner.toString());
        }
        projects.archive(owner, project.toString(), true);
        assertThatThrownBy(() -> settlement(jdbc).succeed(fixture.build(), artifact, budget())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        projects.archive(owner, project.toString(), false);
        UUID nextOwner = UUID.randomUUID(); jdbc.update("INSERT INTO guest_users(id) VALUES (?)", nextOwner);
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", nextOwner, project);
        assertThatThrownBy(() -> settlement(jdbc).succeed(fixture.build(), artifact, budget())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", owner, project);
        assertThat(row(fixture.build())).isEqualTo(before);
        jdbc.update("UPDATE execution_builds SET cancel_requested=true WHERE id=?", fixture.build().id());
        assertThatThrownBy(() -> settlement(jdbc).succeed(fixture.build(), artifact, budget())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(settlement(jdbc).fail(fixture.build(), "EXECUTION_CHECK_FAILED").status()).isEqualTo("CANCELLED");
        assertThat(row(fixture.build()).get("jar_bytes")).isNull();
    }

    @Test void laterFailurePreservesPreviousSuccessAndCleanupSurvivesPermissionLoss() throws Exception {
        var first = claimed(); complete(first); var artifact = seal(first);
        settlement(jdbc).succeed(first.build(), artifact, budget()); var previous = row(first.build());
        var next = claimed();
        assertThatThrownBy(() -> settlement(jdbc).succeed(next.build(), artifact, budget())).hasMessage("EXECUTION_ARTIFACT_INVALID");
        config.withProperty("atoms.project-execution.enabled", "false"); projects.archive(owner, project.toString(), true);
        assertThat(settlement(jdbc).fail(next.build(), "EXECUTION_CHECK_FAILED").status()).isEqualTo("FAILED");
        assertThat(row(first.build())).isEqualTo(previous); artifact.verify(budget());
        assertThat(jdbc.queryForObject("SELECT id FROM execution_builds WHERE project_id=? AND status='SUCCEEDED'",
                UUID.class, project)).isEqualTo(first.build().id());
        assertThatThrownBy(() -> settlement(jdbc).fail(first.build(), "EXECUTION_CHECK_FAILED")).hasMessage("EXECUTION_STATE_UNAVAILABLE");
    }

    @Test void permissionRevokedWhileAcquiringTheBuildLockCannotPublishSuccess() throws Exception {
        var fixture = claimed(); complete(fixture); var artifact = seal(fixture); var before = row(fixture.build());
        var revoking = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public Map<String, Object> queryForMap(String sql, Object... args) {
                var result = super.queryForMap(sql, args);
                if (sql.contains("execution_builds") && sql.contains("FOR UPDATE"))
                    config.withProperty("atoms.project-execution.maintainer-ids", UUID.randomUUID().toString());
                return result;
            }
        };
        assertThatThrownBy(() -> settlement(revoking).succeed(fixture.build(), artifact, budget()))
                .hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(row(fixture.build())).isEqualTo(before);
    }

    @Test void missingCompletionKeepsPendingAndCapacityEvenWhenCancelledOrReportedFailed() throws Exception {
        var fixture = claimed(); complete(fixture, false); var artifact = seal(fixture);
        jdbc.update("UPDATE execution_builds SET cancel_requested=true WHERE id=?", fixture.build().id());
        assertThatThrownBy(() -> settlement(jdbc).succeed(fixture.build(), artifact, budget())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(settlement(jdbc).fail(fixture.build(), "EXECUTION_TIMEOUT").status()).isEqualTo("NEEDS_RECONCILIATION");
        var unknown = row(fixture.build());
        assertThat(unknown.get("execution_pending")).isEqualTo(true);
        assertThat(unknown.get("worker_slot")).isEqualTo(1); assertThat(unknown.get("finished_at")).isNull();
        assertThat(unknown.get("jar_sha256")).isNull();
        settlement(jdbc).fail(fixture.build(), "EXECUTION_CANCELLED");
        assertThat(row(fixture.build())).isEqualTo(unknown);
    }

    @Test void explicitUncertaintyWithoutPendingAlsoCannotBeTurnedIntoReleasedFailure() throws Exception {
        var fixture = claimed();
        assertThat(settlement(jdbc).fail(fixture.build(), "EXECUTION_PROCESS_UNSETTLED").status()).isEqualTo("NEEDS_RECONCILIATION");
        var before = row(fixture.build());
        settlement(jdbc).fail(fixture.build(), "EXECUTION_CHECK_FAILED");
        assertThat(row(fixture.build())).isEqualTo(before);
        assertThat(before.get("worker_slot")).isEqualTo(1); assertThat(before.get("finished_at")).isNull();
    }

    @Test void failedTransactionRollsBackDigestsStatusCapacityAndItsEventWithoutLeakingSql() throws Exception {
        var fixture = claimed(); complete(fixture); var artifact = seal(fixture); var before = row(fixture.build());
        var failing = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public int update(String sql, Object... args) {
                int changed = super.update(sql, args);
                if (sql.contains("status='SUCCEEDED'")) throw new IllegalStateException("PRIVATE_SQL_PASSWORD_CANARY");
                return changed;
            }
        };
        assertThatThrownBy(() -> settlement(failing).succeed(fixture.build(), artifact, budget()))
                .hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
        assertThat(row(fixture.build())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_build_events WHERE build_id=? AND payload->>'status'='SUCCEEDED'",
                Integer.class, fixture.build().id())).isZero();
        artifact.verify(budget());
    }

    @Test void sharedDeadlineAndAmbientTransactionsCannotBypassFinalChecks() throws Exception {
        var fixture = claimed(); complete(fixture); var artifact = seal(fixture); var before = row(fixture.build());
        assertThatThrownBy(() -> settlement(jdbc).succeed(fixture.build(), artifact, () -> Duration.ZERO)).hasMessage("EXECUTION_TIMEOUT");
        assertThatThrownBy(() -> settlement(jdbc).succeed(fixture.build(), artifact,
                () -> TransactionSynchronizationManager.isActualTransactionActive() ? Duration.ZERO : Duration.ofSeconds(30)))
                .hasMessage("EXECUTION_TIMEOUT").hasNoCause();
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> settlement(jdbc).succeed(fixture.build(), artifact, () ->
                TransactionSynchronizationManager.isActualTransactionActive() && checks.incrementAndGet() > 1
                        ? Duration.ZERO : Duration.ofSeconds(30))).hasMessage("EXECUTION_TIMEOUT");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThatThrownBy(() -> settlement(jdbc).succeed(fixture.build(), artifact, budget())).hasMessage("EXECUTION_STATE_UNAVAILABLE");
            assertThatThrownBy(() -> settlement(jdbc).fail(fixture.build(), "EXECUTION_TIMEOUT")).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        });
        assertThatThrownBy(() -> settlement(jdbc).fail(fixture.build(), "PRIVATE_CODE_CANARY")).hasMessage("EXECUTION_STATE_UNAVAILABLE");
        assertThat(row(fixture.build())).isEqualTo(before);
        assertThat(settlement(jdbc).fail(fixture.build(), "EXECUTION_TIMEOUT").status()).isEqualTo("TIMED_OUT");
    }

    @Test void concurrentSettlementHasOneWinnerAndOneSuccessEvent() throws Exception {
        var fixture = claimed(); complete(fixture); var artifact = seal(fixture);
        var threads = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        Callable<String> attempt = () -> {
            start.await();
            try { return settlement(jdbc).succeed(fixture.build(), artifact, budget()).status(); }
            catch (IOException rejected) { return rejected.getMessage(); }
        };
        try {
            var first = threads.submit(attempt); var second = threads.submit(attempt); start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCEEDED", "EXECUTION_STATE_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_build_events WHERE build_id=? AND payload->>'status'='SUCCEEDED'",
                    Integer.class, fixture.build().id())).isEqualTo(1);
        } finally { threads.shutdownNow(); assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void databaseRequiresCompleteBoundedDigestsAndMakesSuccessfulEvidenceImmutable() throws Exception {
        var fixture = claimed(); complete(fixture); var artifact = seal(fixture);
        for (String assignment : List.of("status='SUCCEEDED',worker_slot=null,finished_at=now()", "jar_bytes=7",
                "status='SUCCEEDED',worker_slot=null,finished_at=now(),jar_bytes=7,jar_sha256=repeat('a',64)",
                "status='SUCCEEDED',worker_slot=null,finished_at=now(),jar_bytes=268435457,jar_sha256=repeat('a',64),manifest_sha256=repeat('b',64)"))
            assertThatThrownBy(() -> jdbc.update("UPDATE execution_builds SET " + assignment + " WHERE id=?", fixture.build().id()))
                    .isInstanceOf(DataAccessException.class);
        settlement(jdbc).succeed(fixture.build(), artifact, budget()); var before = row(fixture.build());
        for (String assignment : List.of("jar_bytes=null", "jar_bytes=8", "jar_sha256=repeat('b',64)", "manifest_sha256=repeat('c',64)"))
            assertThatThrownBy(() -> jdbc.update("UPDATE execution_builds SET " + assignment + " WHERE id=?", fixture.build().id()))
                    .isInstanceOf(DataAccessException.class);
        assertThat(row(fixture.build())).isEqualTo(before);
    }

    record Fixture(ExecutionBuild build, ExecutionPaths.Task task, ExecutionBuildJournal journal, Path frontend, Path backend) {}
    private Fixture claimed() throws Exception {
        var build = new TransactionTemplate(transactions).execute(tx -> repository.insert(owner, project,
                ExecutionBuild.request(version, UUID.randomUUID().toString()), HASH, new ExecutionSettings(config).load()));
        builds.add(build);
        build = new ExecutionBuildClaim(new ExecutionSettings(config), projects, repository, jdbc, transactions)
                .claim(owner, project, build.id());
        var task = paths.createBuild(build.id());
        Path frontend = ExecutionRootLease.directory(paths.project(task).resolve("frontend"));
        Path backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
        return new Fixture(build, task, new ExecutionBuildJournal(new ExecutionSettings(config), projects, jdbc,
                transactions, build, paths, task), frontend, backend);
    }
    private void complete(Fixture fixture) throws Exception {
        complete(fixture, true);
    }
    private void complete(Fixture fixture, boolean finishJava) throws Exception {
        for (var operation : List.of(NPM_CI, TYPECHECK, LINT, UNIT_TEST, FRONTEND_BUILD, MAVEN_PACKAGE)) {
            UUID execution = UUID.randomUUID();
            if (operation == MAVEN_PACKAGE) fixture.journal().begin(execution);
            else fixture.journal().begin(operation, execution);
            Path cwd = operation == MAVEN_PACKAGE ? fixture.backend() : fixture.frontend();
            var java = Path.of(System.getProperty("java.home"));
            var invocation = new ExecutionProcess.Invocation(java.resolve("bin/java"), List.of("-cp",
                    Path.of("target/test-classes").toAbsolutePath().toString(), ExecutionFrontendTest.Fixture.class.getName(), "noop"),
                    cwd, cwd, cwd, java, java.resolve("bin"));
            var proof = new ExecutionProcess().run(invocation, budget(), () -> false, fixture.build().id(), execution, () -> {}).stopped();
            stopped.put(fixture.build().id(), proof);
            if (operation == MAVEN_PACKAGE && !finishJava) return;
            if (operation == MAVEN_PACKAGE) fixture.journal().finish(new ExecutionJava.Stage(execution, null, proof,
                    new ExecutionProcess.Output(0, 0), evidence().javaTests()));
            else fixture.journal().finish(new ExecutionFrontend.Stage(operation, execution, null, proof,
                    new ExecutionProcess.Output(0, 0), operation == UNIT_TEST ? evidence().frontend() : null));
        }
    }
    private ExecutionArtifact seal(Fixture fixture) throws Exception {
        return seal(fixture, evidence());
    }
    private ExecutionArtifact seal(Fixture fixture, ExecutionArtifact.Evidence evidence) throws Exception {
        Files.createDirectory(fixture.backend().resolve("target"));
        // Byte-sealing fixture, not a claim of real npm/Maven or executable-JAR acceptance (EX11).
        Files.write(fixture.backend().resolve("target/app.jar"), new byte[]{80, 75, 3, 4, 1, 2, 3});
        return ExecutionArtifact.seal(paths, fixture.task(), fixture.build(), evidence, budget(), () -> {});
    }
    private ExecutionArtifact.Evidence evidence() {
        var tools = new EnumMap<ExecutionToolchain.Kind, ExecutionToolFiles.Summary>(ExecutionToolchain.Kind.class);
        for (var kind : ExecutionToolchain.Kind.values()) tools.put(kind, new ExecutionToolFiles.Summary(HASH, 1, 20));
        return new ExecutionArtifact.Evidence(tools, new ExecutionNpmPolicy.Verified(HASH, HASH, HASH, 50),
                new ExecutionMavenPolicy.Verified(HASH, HASH), new ExecutionDiagnostics.Frontend(1, 2, 3),
                new ExecutionJavaReport.Summary(1, 2), new ExecutionPackaging.Summary(2, 100, HASH));
    }
    private ExecutionBuildSettlement settlement(JdbcTemplate database) {
        return new ExecutionBuildSettlement(new ExecutionSettings(config), projects, database, transactions);
    }
    private Map<String, Object> row(ExecutionBuild build) { return jdbc.queryForMap("SELECT * FROM execution_builds WHERE id=?", build.id()); }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
}
