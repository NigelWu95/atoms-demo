package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real artifacts, HTTP children and stop receipts; controlled metadata/DB and commit failures.
 * Actual PostgreSQL transition constraints are independently covered by ExecutionRunStateIT. */
class ExecutionRunWorkerTest {
    @TempDir Path temporary;
    private static final String HASH = "a".repeat(64);
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), buildId = UUID.randomUUID(), bindingId = UUID.randomUUID();
    private final ExecutionRunRepository runs = mock(ExecutionRunRepository.class);
    private final ExecutionMigrationReviewRepository identities = mock(ExecutionMigrationReviewRepository.class);
    private final ExecutionDatabaseBinding databases = mock(ExecutionDatabaseBinding.class);
    private final Transactions transactions = new Transactions();
    private MockEnvironment environment;
    private ExecutionPaths paths;
    private ExecutionRunService service;
    private ExecutionRunWorker worker;
    private ExecutionRun initial, current;
    private ExecutionBindingRepository.Target target;
    private ExecutionRunProcess process;
    private ExecutionRunProcess.Outcome completed;
    private UUID execution;
    private Path directory;
    private int launches, readyWrites, finishWrites, noLaunchWrites, toolCaptures;
    private boolean failToolCapture;
    private boolean loseBeginCommit, loseReadyCommit, loseFinishCommit, failCompletion, loseCoordination, loseAuthorization, unknownProcess;
    private String mode = "wrong", recordedFailure;

    @BeforeEach void setup() throws Exception {
        Path root = temporary.toRealPath();
        paths = new ExecutionPaths(root.resolve("execution"), List.of());
        var tools = ExecutionToolchain.capture(ExecutionToolchainTest.installation(root.resolve("tools")),
                List.of(paths.checkedRoot()), new ExecutionProcess.Deadline(Duration.ofSeconds(30)));
        var build = new ExecutionBuild(buildId, project, owner, UUID.randomUUID(), HASH, HASH, HASH,
                "SUCCEEDED", false, null, Instant.now(), Instant.now(), 7);
        var task = paths.createBuild(buildId);
        Path backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
        Files.write(Files.createDirectory(backend.resolve("target")).resolve("app.jar"), new byte[]{80, 75, 3, 4, 1});
        var toolIds = new EnumMap<ExecutionToolchain.Kind, ExecutionToolFiles.Summary>(ExecutionToolchain.Kind.class);
        for (var kind : ExecutionToolchain.Kind.values()) toolIds.put(kind, tools.identity(kind));
        var artifact = ExecutionArtifact.seal(paths, task, build, new ExecutionArtifact.Evidence(toolIds,
                new ExecutionNpmPolicy.Verified(HASH, HASH, HASH, 1), new ExecutionMavenPolicy.Verified(HASH, HASH),
                new ExecutionDiagnostics.Frontend(1, 1, 1), new ExecutionJavaReport.Summary(1, 1), new ExecutionPackaging.Summary(1, 1, HASH)),
                new ExecutionProcess.Deadline(Duration.ofSeconds(30)), () -> {});
        int port;
        try (var socket = new ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1", 0)); port = socket.getLocalPort(); }
        target = new ExecutionBindingRepository.Target(project, bindingId, 5432, "worker_app", "worker_role", port, 1000, 1001);
        var identity = new ExecutionMigrationReviewRepository.Identity(build, artifact.summary().jarSha256(), artifact.summary().manifestSha256(), target);
        var request = ExecutionRun.request(buildId, bindingId, null, "once");
        initial = new ExecutionRun(UUID.randomUUID(), project, owner, buildId, build.sourceVersionId(), bindingId, null,
                HASH, identity.jarSha256(), identity.manifestSha256(), target.hash(), request.keyHash(), request.bodyHash(),
                "STARTING", false, null, false, null, null, Instant.now(), null, null);
        current = initial;
        environment = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString())
                .withProperty("atoms.project-execution.root", paths.checkedRoot().toString())
                .withProperty("atoms.project-execution.java", tools.javaHome().resolve("bin/java").toString());
        for (String key : List.of("node", "npm", "maven", "policy")) environment.withProperty("atoms.project-execution." + key, root.resolve(key).toString());
        when(identities.identity(owner, project, buildId)).thenAnswer(call -> {
            inside(); if (loseAuthorization && readyWrites > 0) throw ExecutionRun.error(404, "PROJECT_NOT_FOUND"); return identity;
        });
        when(runs.find(owner, project, request)).thenAnswer(call -> { inside(); return null; });
        when(runs.admit(owner, project, request)).thenAnswer(call -> { inside(); return initial; });
        when(runs.get(project, initial.id())).thenAnswer(call -> { inside(); return current; });
        when(databases.verify(eq(project), any())).thenAnswer(call -> { outside(); return target; });
        when(databases.authorizeLaunch(eq(project), eq(initial.id()), any(), any(), any())).thenAnswer(call -> {
            outside(); var command = (ExecutionRunCommand) call.getArgument(3);
            var grant = mock(ExecutionDatabaseBinding.Launch.class);
            when(grant.builder(any())).thenAnswer(ignored -> command.builder());
            return grant;
        });
        when(runs.begin(eq(initial), any())).thenAnswer(call -> {
            inside(); execution = call.getArgument(1);
            current = row("STARTING", execution, true, null, null, null);
            transactions.failNextCommit = loseBeginCommit; return current;
        });
        when(runs.ready(eq(initial), any(), eq(target), any())).thenAnswer(call -> {
            inside(); readyWrites++; current = row("READY", execution, true, null, null, Instant.now());
            transactions.failNextCommit = loseReadyCommit; return current;
        });
        when(runs.finish(eq(initial), any(), any(), any(), nullable(String.class))).thenAnswer(call -> {
            inside(); finishWrites++; completed = call.getArgument(3); recordedFailure = call.getArgument(4);
            if (failCompletion) throw new org.springframework.dao.DataAccessResourceFailureException("private-worker-canary");
            boolean pending = completed.stopped() == null;
            current = row("NEEDS_RECONCILIATION", execution, pending,
                    pending ? null : completed.stopped().digest(), recordedFailure, current.readyAt());
            transactions.failNextCommit = loseFinishCommit;
            return current;
        });
        when(runs.notStarted(eq(initial), anyString())).thenAnswer(call -> {
            inside(); noLaunchWrites++; recordedFailure = call.getArgument(1);
            return row("FAILED", null, false, null, recordedFailure, null);
        });
        var settings = new ExecutionSettings(environment);
        service = new ExecutionRunService(settings, runs, identities, transactions, paths, databases, mock(ExecutionMigrationReview.class));
        var unsettled = mock(ExecutionRunProcess.class);
        when(unsettled.await(any())).thenReturn(new ExecutionRunProcess.Outcome("RUN_PROCESS_UNSETTLED", new ExecutionRunProcess.Output(1, 1), null));
        worker = new ExecutionRunWorker(settings, service, runs, transactions, paths, claim -> {
            outside(); toolCaptures++;
            assertThat(claim.run).isEqualTo(initial);
            assertThat(claim.budget.remaining()).isPositive().isLessThanOrEqualTo(claim.configuration.startTimeout());
            if (failToolCapture) throw new IOException("EXECUTION_TIMEOUT");
            return tools;
        }, databases, () -> {
            if (loseCoordination && readyWrites > 0) throw new IOException("RUN_COORDINATOR_UNAVAILABLE");
        }, (prepared, run, exec, budget, preflight) -> {
            outside(); assertThat(current.executionPending()).isTrue(); assertThat(current.executionId()).isEqualTo(exec);
            launches++; directory = prepared.directory().toPath();
            if (unknownProcess) {
                // No OS process in this explicit unknown-outcome boundary test; never fabricate Stopped.
                process = unsettled;
            } else {
                var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "--add-modules", "jdk.httpserver",
                        "-cp", Path.of("target/test-classes").toAbsolutePath().toString(),
                        mode.equals("exit") ? ExitChild.class.getName() : ExecutionHealthTest.Child.class.getName(),
                        Integer.toString(target.port()), run.toString(), exec.toString(), mode).directory(prepared.directory());
                command.environment().clear(); process = ExecutionRunProcess.start(command, run, exec, budget, preflight);
            }
            return process;
        });
    }

    @Test void toolChecksAreLazyAndUseTheAdmittedStartupDeadlineBeforeAnyIntent() throws Exception {
        assertThat(toolCaptures).isZero();
        var ticket = ticket(); assertThat(toolCaptures).isZero(); failToolCapture = true;
        worker.run(ticket);
        assertThat(toolCaptures).isEqualTo(1); assertThat(recordedFailure).isEqualTo("RUN_START_TIMEOUT");
        assertThat(noLaunchWrites).isEqualTo(1); assertThat(launches).isZero(); assertThat(execution).isNull();
        assertThat(worker.hasHeldProcesses()).isFalse(); worker.requestStopOwned();
    }

    @Test void nativeWorkerCapturesConfiguredToolsOnlyAfterTakingAFreshTicket() throws Exception {
        var nativeWorker = ExecutionRunWorker.nativeWorker(new ExecutionSettings(environment), service, runs, transactions,
                paths, List.of(temporary.resolve("source")), databases, () -> {});
        assertThat(noLaunchWrites).isZero(); assertThat(nativeWorker.hasHeldProcesses()).isFalse();
        nativeWorker.run(ticket()); // This fixture deliberately has no configured native node/npm/Maven files.
        assertThat(recordedFailure).isEqualTo("RUN_START_FAILED"); assertThat(noLaunchWrites).isEqualTo(1);
        assertThat(execution).isNull(); assertThat(launches).isZero(); assertThat(nativeWorker.hasHeldProcesses()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void failedCompletionRetainsTheOriginalOwnerAndNeverRetriesTheWrite(boolean commitAcknowledgement) throws Exception {
        failCompletion = !commitAcknowledgement; loseFinishCommit = commitAcknowledgement;
        assertThatThrownBy(() -> worker.run(ticket())).hasMessage("RUN_STATE_UNAVAILABLE").hasNoCause().hasNoSuppressedExceptions();
        assertThat(finishWrites).isEqualTo(1); assertThat(noLaunchWrites).isZero();
        assertStopped(); assertThat(worker.ownedProcess(initial.id())).isSameAs(process);
        assertThat(worker.hasHeldProcesses()).isTrue(); worker.requestStopOwned();
        assertThat(worker.hasHeldProcesses()).isTrue(); // A stop signal cannot discard an unacknowledged completion.
    }

    @Test void wrongHealthStopsTheActualChildWithoutPublishingReady() throws Exception {
        var result = worker.run(ticket());
        assertThat(recordedFailure).isEqualTo("RUN_HEALTH_INVALID"); assertThat(readyWrites).isZero();
        assertThat(result.executionPending()).isFalse(); assertThat(result.finishedAt()).isNull();
        assertStopped(); assertThat(finishWrites).isEqualTo(1); assertThat(noLaunchWrites).isZero();
        assertThat(worker.ownedProcess(initial.id())).isNull();
    }

    @Test void closedServerConnectionsDoNotLookLikeAnActivePortOwnerDuringImmediateRestart() throws Exception {
        try (var previous = new ServerSocket()) {
            previous.setReuseAddress(true); previous.bind(new InetSocketAddress("127.0.0.1", target.port()));
            previous.setSoTimeout(2000);
            try (var client = new Socket("127.0.0.1", target.port())) {
                client.setSoTimeout(2000);
                try (var accepted = previous.accept()) { accepted.shutdownOutput(); }
                assertThat(client.getInputStream().read()).isEqualTo(-1);
            }
        }
        worker.run(ticket());
        assertThat(launches).isEqualTo(1); assertThat(recordedFailure).isEqualTo("RUN_HEALTH_INVALID");
        assertStopped();
    }

    @Test void listeningForeignServerStillBlocksLaunchEvenWhenItAllowsAddressReuse() throws Exception {
        try (var foreign = new ServerSocket()) {
            foreign.setReuseAddress(true); foreign.bind(new InetSocketAddress("127.0.0.1", target.port()));
            worker.run(ticket());
            assertThat(launches).isZero(); assertThat(recordedFailure).isEqualTo("RUN_PORT_IN_USE");
            assertThat(foreign.isClosed()).isFalse(); assertThat(noLaunchWrites).isEqualTo(1);
        }
    }

    @Test void earlyExitUsesTheRealReceiptAndDoesNotInventReadiness() throws Exception {
        mode = "exit"; worker.run(ticket());
        assertThat(readyWrites).isZero(); assertThat(finishWrites).isEqualTo(1);
        assertThat(completed.code()).isEqualTo("RUN_EXITED"); assertStopped();
        assertThat(worker.ownedProcess(initial.id())).isNull();
    }

    @Test void lostIntentCommitAcknowledgementNeverCallsLauncherOrCompletesAsNotStarted() throws Exception {
        loseBeginCommit = true;
        assertThatThrownBy(() -> worker.run(ticket())).hasMessage("RUN_STATE_UNAVAILABLE").hasNoCause();
        assertThat(current.executionPending()).isTrue(); assertThat(launches).isZero();
        assertThat(finishWrites).isZero(); assertThat(noLaunchWrites).isZero();
        assertThat(worker.ownedProcess(initial.id())).isNull();
    }

    @Test void lostReadyCommitAcknowledgementStopsOriginalProcessAndCompletesOnlyOnce() throws Exception {
        mode = "valid"; loseReadyCommit = true;
        worker.run(ticket());
        assertThat(readyWrites).isEqualTo(1); assertThat(finishWrites).isEqualTo(1);
        assertThat(noLaunchWrites).isZero(); assertStopped();
        assertThat(worker.ownedProcess(initial.id())).isNull();
    }

    @Test void configurationRevokedBeforeLaunchRecordsOnlyANoLaunchFailure() throws Exception {
        var ticket = ticket(); environment.withProperty("atoms.project-execution.enabled", "false");
        worker.run(ticket);
        assertThat(recordedFailure).isEqualTo("RUN_CONFIGURATION_CHANGED");
        assertThat(launches).isZero(); assertThat(noLaunchWrites).isEqualTo(1); assertThat(finishWrites).isZero();
        assertThat(current.executionId()).isNull();
    }

    @Test void coordinationLostAfterReadyStopsTheHeldProcess() throws Exception {
        mode = "valid"; loseCoordination = true;
        worker.run(ticket());
        assertThat(readyWrites).isEqualTo(1); assertThat(recordedFailure).isEqualTo("RUN_COORDINATOR_UNAVAILABLE");
        assertThat(finishWrites).isEqualTo(1); assertStopped();
    }

    @Test void ownershipLostAfterReadyStillAllowsTheOriginalWorkerToStopAndSettle() throws Exception {
        mode = "valid"; loseAuthorization = true;
        worker.run(ticket());
        assertThat(readyWrites).isEqualTo(1); assertThat(recordedFailure).isEqualTo("RUN_AUTHORIZATION_CHANGED");
        assertThat(finishWrites).isEqualTo(1); assertStopped();
        assertThat(worker.ownedProcess(initial.id())).isNull();
    }

    @Test void absentStopReceiptRetainsOwnershipAndNeverFallsBackToNoLaunch() throws Exception {
        unknownProcess = true;
        var result = worker.run(ticket());
        assertThat(result.executionPending()).isTrue(); assertThat(result.stoppedSha256()).isNull();
        assertThat(completed.stopped()).isNull(); assertThat(readyWrites).isZero();
        assertThat(finishWrites).isEqualTo(1); assertThat(noLaunchWrites).isZero();
        assertThat(worker.ownedProcess(initial.id())).isSameAs(process);
    }

    @AfterEach void close() throws Exception {
        boolean stopped = process == null || unknownProcess;
        if (process != null && !unknownProcess) {
            process.requestStop(); stopped = process.await(Duration.ofSeconds(12)).stopped() != null;
        }
        if (stopped && paths != null) paths.close();
        assertThat(stopped).as("Keep root ownership when the actual child cannot be settled").isTrue();
    }
    private ExecutionRunService.Ticket ticket() throws IOException { return service.submit(owner, project, buildId, bindingId, null, "once").ticket(); }
    private void assertStopped() {
        assertThat(completed).isNotNull(); assertThat(completed.stopped()).isNotNull();
        assertThat(completed.stopped().matches(initial.id(), execution, directory)).isTrue();
        assertThat(process.isAlive()).isFalse();
    }
    private ExecutionRun row(String status, UUID exec, boolean pending, String stopped, String failure, Instant ready) {
        return new ExecutionRun(initial.id(), project, owner, buildId, initial.sourceVersionId(), bindingId, null,
                initial.treeSha256(), initial.jarSha256(), initial.manifestSha256(), initial.bindingIdentitySha256(), initial.keySha256(), initial.requestSha256(),
                status, false, exec, pending, stopped, failure, initial.createdAt(), ready, null);
    }
    private void inside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); }
    private void outside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private static class Transactions extends AbstractPlatformTransactionManager {
        boolean failNextCommit;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failNextCommit) { failNextCommit = false; throw new org.springframework.transaction.TransactionSystemException("private-commit-canary"); }
        }
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
    public static final class ExitChild { public static void main(String[] arguments) {} }
}
