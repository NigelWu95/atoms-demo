package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.ProjectSourceReadGateway;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false",
        "atoms.generation.worker-enabled=false"})
class ExecutionCancellationIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
    final List<ExecutionBuild> fixtures = new ArrayList<>();
    final ProjectSourceReadGateway source = mock(ProjectSourceReadGateway.class);
    MockEnvironment config;
    ExecutionSettings settings;
    ExecutionBuildRepository repository;
    ProjectExecutionService service;

    @BeforeEach void setup() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("atoms_demo_test");
        jdbc.update("INSERT INTO guest_users(id) VALUES (?),(?)", owner, stranger);
        config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        for (String key : List.of("root", "node", "npm", "java", "maven", "policy"))
            config.withProperty("atoms.project-execution." + key, temporary.resolve("unused-" + key).toString());
        settings = new ExecutionSettings(config); repository = new ExecutionBuildRepository(jdbc);
        service = service(projects, repository);
    }

    @AfterEach void retainEvidence() {
        for (var build : fixtures) {
            // No tools are launched by the metadata fixtures. The one real child must settle through its journal.
            assertThat(row(build).get("execution_pending")).isEqualTo(false);
            jdbc.update("""
                    UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,queue_slot=null,
                        worker_slot=null,finished_at=clock_timestamp()
                    WHERE id=? AND finished_at IS NULL AND NOT execution_pending
                    """, build.id());
        }
        verifyNoInteractions(source);
    }

    @Test void queuedCancellationCommitsEventAndReturnsCapacityOnce() {
        config.withProperty("atoms.project-execution.queue-capacity", "1");
        var build = queue();
        var cancelled = service.cancel(owner, build.projectId(), build.id());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelRequested()).isTrue();
        assertThat(cancelled.errorCode()).isEqualTo("EXECUTION_CANCELLED");
        assertThat(cancelled.finishedAt()).isNotNull();
        assertThat(row(build).get("queue_slot")).isNull();
        assertThat(cancelled.lastEventSeq()).isEqualTo(2);
        var saved = row(build);
        assertThat(service.cancel(owner, build.projectId(), build.id())).isEqualTo(cancelled);
        assertThat(row(build)).isEqualTo(saved);
        assertThat(queue().status()).isEqualTo("QUEUED");
        assertThat(temporary.resolve("unused-root")).doesNotExist();
    }

    @Test void claimedAndUnresolvedBuildsRetainCapacityEvenWithoutPendingProcess() {
        for (String status : List.of("PREPARING", "NEEDS_RECONCILIATION")) {
            var build = queue(); claim(projects, build);
            if (status.equals("NEEDS_RECONCILIATION"))
                jdbc.update("UPDATE execution_builds SET status='NEEDS_RECONCILIATION' WHERE id=?", build.id());
            var before = row(build);
            var cancelled = service.cancel(owner, build.projectId(), build.id());
            assertThat(cancelled.status()).isEqualTo(status);
            assertThat(cancelled.cancelRequested()).isTrue();
            assertThat(cancelled.finishedAt()).isNull();
            assertThat(row(build).get("worker_slot")).isEqualTo(before.get("worker_slot"));
            assertThat(service.cancel(owner, build.projectId(), build.id())).isEqualTo(cancelled);
            // This fixture has never launched a process; keep the row/event history after the assertion.
            jdbc.update("UPDATE execution_builds SET status='CANCELLED',worker_slot=null,finished_at=clock_timestamp() WHERE id=?", build.id());
        }
    }

    @Test void cancellationWaitsForRealStopReceiptAndWinsOverLateSuccess() throws Exception {
        var build = queue(); build = claim(projects, build);
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of())) {
            var task = paths.createBuild(build.id());
            Path frontend = Files.createDirectory(paths.project(task).resolve("frontend"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var journal = new ExecutionBuildJournal(settings, projects, jdbc, transactions, build, paths, task);
            UUID execution = UUID.randomUUID();
            journal.begin(ExecutionToolchain.Operation.NPM_CI, execution);
            Path java = Path.of(System.getProperty("java.home"));
            var result = new ExecutionProcess().run(new ExecutionProcess.Invocation(java.resolve("bin/java"), List.of("-version"),
                    frontend, frontend, frontend, java, frontend), new ExecutionProcess.Deadline(Duration.ofSeconds(10)),
                    () -> false, build.id(), execution, () -> {});
            try {
                var cancelled = service.cancel(owner, build.projectId(), build.id());
                assertThat(cancelled.status()).isEqualTo("INSTALLING");
                assertThat(cancelled.finishedAt()).isNull();
                assertThat(row(build).get("execution_pending")).isEqualTo(true);
                assertThat(row(build).get("worker_slot")).isEqualTo(1);
            } finally {
                journal.finish(new ExecutionFrontend.Stage(ExecutionToolchain.Operation.NPM_CI, execution,
                        null, result.stopped(), result.summary(), null));
            }
            assertThat(repository.get(build.projectId(), build.id()).status()).isEqualTo("CANCELLED");
            assertThat(row(build).get("execution_pending")).isEqualTo(false);
            assertThat(row(build).get("worker_slot")).isNull();
        }
    }

    @Test void terminalFailureRemainsUnchangedAndArchiveDoesNotBlockCancellation() throws Exception {
        var failed = queue();
        new ExecutionBuildSettlement(settings, projects, jdbc, transactions).fail(failed, "EXECUTION_CHECK_FAILED");
        var before = row(failed);
        assertThat(service.cancel(owner, failed.projectId(), failed.id()).status()).isEqualTo("FAILED");
        assertThat(row(failed)).isEqualTo(before);
        var archived = queue(); projects.archive(owner, archived.projectId().toString(), true);
        assertThat(service.cancel(owner, archived.projectId(), archived.id()).status()).isEqualTo("CANCELLED");
    }

    @Test void disabledUnauthorizedForeignAndTransferredRequestsLeaveTaskUntouched() {
        var build = queue(); var before = row(build);
        config.withProperty("atoms.project-execution.enabled", "false");
        code("EXECUTION_DISABLED", () -> service.cancel(owner, build.projectId(), build.id()));
        config.withProperty("atoms.project-execution.enabled", "true");
        code("EXECUTION_FORBIDDEN", () -> service.cancel(stranger, build.projectId(), build.id()));
        config.withProperty("atoms.project-execution.maintainer-ids", owner + "," + stranger);
        code("PROJECT_NOT_FOUND", () -> service.cancel(stranger, build.projectId(), build.id()));
        code("EXECUTION_BUILD_NOT_FOUND", () -> service.cancel(owner, build.projectId(), UUID.randomUUID()));
        var other = queue();
        code("EXECUTION_BUILD_NOT_FOUND", () -> service.cancel(owner, other.projectId(), build.id()));
        code("EXECUTION_INVALID_INPUT", () -> service.cancel(owner, null, build.id()));
        code("EXECUTION_INVALID_INPUT", () -> service.cancel(owner, build.projectId(), null));
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, build.projectId());
        code("PROJECT_NOT_FOUND", () -> service.cancel(owner, build.projectId(), build.id()));
        code("EXECUTION_BUILD_OWNER_CHANGED", () -> service.cancel(stranger, build.projectId(), build.id()));
        assertThat(row(build)).isEqualTo(before);
    }

    @Test void permissionIsRecheckedAfterTheProjectLock() {
        var build = queue(); var before = row(build);
        var guarded = mock(ProjectService.class);
        doAnswer(call -> {
            var result = projects.lock(owner, build.projectId().toString());
            config.withProperty("atoms.project-execution.maintainer-ids", stranger.toString());
            return result;
        }).when(guarded).lock(owner, build.projectId().toString());
        code("EXECUTION_FORBIDDEN", () -> service(guarded, repository).cancel(owner, build.projectId(), build.id()));
        assertThat(row(build)).isEqualTo(before);
    }

    @Test void failedReadRollsBackCancelEventAndCapacityAndHidesDatabaseDetails() {
        var build = queue(); var before = row(build);
        var failing = new ExecutionBuildRepository(jdbc) {
            @Override ExecutionBuild get(UUID project, UUID id) {
                var current = super.get(project, id);
                if (current.cancelRequested()) throw new org.springframework.dao.DataAccessResourceFailureException("PRIVATE_DATABASE_CANARY");
                return current;
            }
        };
        assertThatThrownBy(() -> service(projects, failing).cancel(owner, build.projectId(), build.id()))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo("EXECUTION_STATE_UNAVAILABLE");
                    assertThat(error.getMessage()).doesNotContain("CANARY"); assertThat(error.getCause()).isNull();
                });
        assertThat(row(build)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_build_events WHERE build_id=?", Integer.class, build.id())).isEqualTo(1);
    }

    @Test void callerTransactionCannotObserveAnUncommittedCancellation() {
        var build = queue(); var before = row(build);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> assertThatThrownBy(
                () -> service.cancel(owner, build.projectId(), build.id())).isInstanceOf(IllegalStateException.class));
        assertThat(row(build)).isEqualTo(before);
    }

    @Test void concurrentCancelAndClaimSerializeOnProjectWithoutReleasingAClaimedWorker() throws Exception {
        var build = queue(); var barrier = new CyclicBarrier(2);
        var connections = ConcurrentHashMap.<Integer>newKeySet(); var guarded = mock(ProjectService.class);
        doAnswer(call -> {
            connections.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            barrier.await(3, TimeUnit.SECONDS); return projects.lock(owner, build.projectId().toString());
        }).when(guarded).lock(owner, build.projectId().toString());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var cancel = pool.submit(() -> service(guarded, repository).cancel(owner, build.projectId(), build.id()));
            var claim = pool.submit(() -> {
                try { return claim(guarded, build).status(); }
                catch (ApiException error) { return error.code(); }
            });
            var result = cancel.get(10, TimeUnit.SECONDS); String claimed = claim.get(10, TimeUnit.SECONDS);
            assertThat(connections).hasSize(2); assertThat(result.cancelRequested()).isTrue();
            assertThat(claimed).isIn("PREPARING", "EXECUTION_BUILD_NOT_QUEUED");
            assertThat(row(build).get("status")).isEqualTo(claimed.equals("PREPARING") ? "PREPARING" : "CANCELLED");
            assertThat(row(build).get("worker_slot")).isEqualTo(claimed.equals("PREPARING") ? 1 : null);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    private ExecutionBuild queue() {
        UUID project = UUID.randomUUID(), version = UUID.randomUUID(); String hash = "a".repeat(64);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'cancellation-metadata-fixture')", project, owner);
        jdbc.update("INSERT INTO project_sources(project_id) VALUES (?)", project);
        jdbc.update("INSERT INTO source_versions(id,project_id,version_no,commit_id,tree_sha256,file_count,tree_bytes) VALUES (?,?,1,?,?,1,1)",
                version, project, "a".repeat(40), hash);
        var build = new TransactionTemplate(transactions).execute(tx -> {
            projects.lock(owner, project.toString());
            return repository.insert(owner, project, ExecutionBuild.request(version, UUID.randomUUID().toString()), hash, settings.load());
        });
        fixtures.add(build); return build;
    }
    private ProjectExecutionService service(ProjectService projectService, ExecutionBuildRepository builds) {
        return new ProjectExecutionService(settings, builds, projectService, source, transactions);
    }
    private ExecutionBuild claim(ProjectService projectService, ExecutionBuild build) {
        return new ExecutionBuildClaim(settings, projectService, repository, jdbc, transactions).claim(owner, build.projectId(), build.id());
    }
    private Map<String, Object> row(ExecutionBuild build) {
        return jdbc.queryForMap("SELECT * FROM execution_builds WHERE id=?", build.id());
    }
    private void code(String expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(expected));
    }
}
