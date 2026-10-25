package dev.atoms.demo.projectexecution;

import java.net.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Rolled-back metadata and real controlled HTTP children. No generated application or business migration. */
@ActiveProfiles("test") @Transactional
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionRunStateIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), source = UUID.randomUUID(), build = UUID.randomUUID();
    final UUID execution = UUID.randomUUID();
    static final String HASH = "a".repeat(64);
    ExecutionRunRepository repository;
    ExecutionBindingRepository.Target target;
    ExecutionRun run;
    ExecutionRunProcess process;

    @BeforeEach void metadata() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo("atoms_demo_test.atoms_verify_20261024_ex16");
        int port;
        try (var socket = new ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1", 0)); port = socket.getLocalPort(); }
        repository = new ExecutionRunRepository(jdbc);
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'run-state-fixture')", project, owner);
        jdbc.update("INSERT INTO project_sources(project_id) VALUES (?)", project);
        jdbc.update("INSERT INTO source_versions(id,project_id,version_no,commit_id,tree_sha256,file_count,tree_bytes) VALUES (?,?,1,?,?,1,1)", source, project, "a".repeat(40), HASH);
        String name = "state_fixture_" + project.toString().replace("-", "");
        target = new ExecutionBindingRepository.Target(project, UUID.randomUUID(), 5432, name, name + "_r", port, 900001, 900002);
        new ExecutionBindingRepository(jdbc).reserve(target, 4);
        jdbc.update("""
                INSERT INTO execution_builds(id,project_id,owner_id,source_version_id,tree_sha256,idempotency_key_sha256,request_sha256,queue_slot)
                VALUES (?,?,?,?,?,?,?,1)
                """, build, project, owner, source, HASH, HASH, HASH);
        jdbc.update("UPDATE execution_builds SET status='PREPARING',queue_slot=null,worker_slot=1 WHERE id=?", build);
        for (String stage : List.of("INSTALLING", "CHECKING", "PACKAGING")) jdbc.update("UPDATE execution_builds SET status=? WHERE id=?", stage, build);
        jdbc.update("UPDATE execution_builds SET status='SUCCEEDED',jar_bytes=1,jar_sha256=?,manifest_sha256=?,worker_slot=null,finished_at=clock_timestamp() WHERE id=?", HASH, HASH, build);
        run = repository.admit(owner, project, request("one"));
    }

    @AfterEach void stopChild() {
        if (process != null) outside(() -> { process.requestStop(); assertThat(process.await(Duration.ofSeconds(12)).stopped()).isNotNull(); return null; });
    }

    @Test void claimIsSingleUseAndReplayDoesNotCreateAnotherExecution() {
        assertThat(repository.find(owner, project, request("one"))).isEqualTo(run);
        assertThat(repository.find(owner, project, request("absent"))).isNull();
        var claimed = repository.begin(run, execution);
        assertThat(claimed.executionId()).isEqualTo(execution); assertThat(claimed.executionPending()).isTrue();
        assertThat(claimed.status()).isEqualTo("STARTING"); assertThat(claimed.readyAt()).isNull();
        rejected(() -> repository.begin(run, execution));
        rejected(() -> repository.begin(run, UUID.randomUUID()));
        assertThat(repository.admit(owner, project, request("one"))).isEqualTo(claimed);
    }

    @Test void stopBeforeLaunchIsMonotonicAndOnlyTheWorkerCanFinishCancellation() {
        var requested = repository.stop(owner, project, run.id());
        assertThat(requested.stopRequested()).isTrue(); assertThat(requested.status()).isEqualTo("STARTING");
        assertThat(requested.executionId()).isNull(); assertThat(requested.finishedAt()).isNull();
        assertThat(repository.stop(owner, project, run.id())).isEqualTo(requested);
        rejected(() -> repository.begin(run, execution));
        rejected(() -> repository.admit(owner, project, request("second")));
        var cancelled = repository.notStarted(run, "RUN_CANCELLED");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(repository.stop(owner, project, run.id())).isEqualTo(cancelled);
    }

    @Test void stopOfReadyArchivedProjectNeedsActualExitBeforeAnotherRunCanStart() {
        repository.begin(run, execution); repository.ready(run, execution, target, health(execution));
        jdbc.update("UPDATE projects SET archived=true WHERE id=?", project);
        var stopping = repository.stop(owner, project, run.id());
        assertThat(stopping.status()).isEqualTo("STOPPING"); assertThat(stopping.executionPending()).isTrue();
        assertThat(stopping.stoppedSha256()).isNull(); assertThat(stopping.finishedAt()).isNull();
        assertThat(repository.stop(owner, project, run.id())).isEqualTo(stopping);
        jdbc.update("UPDATE projects SET archived=false WHERE id=?", project);
        rejected(() -> repository.admit(owner, project, request("second")));
        var stopped = repository.finish(run, execution, temporary, stop(), null);
        assertThat(stopped.status()).isEqualTo("STOPPED");
        assertThat(repository.stop(owner, project, run.id())).isEqualTo(stopped);
        assertThat(repository.admit(owner, project, request("second")).id()).isNotEqualTo(run.id());
    }

    @Test void stopCannotClearUnknownExecutionOrOperateAcrossOwnership() {
        rejected(() -> repository.stop(UUID.randomUUID(), project, run.id()));
        rejected(() -> repository.stop(owner, UUID.randomUUID(), run.id()));
        rejected(() -> repository.stop(owner, project, UUID.randomUUID()));
        repository.begin(run, execution);
        var unknown = repository.finish(run, execution, temporary,
                new ExecutionRunProcess.Outcome("RUN_PROCESS_UNSETTLED", new ExecutionRunProcess.Output(0, 0), null), null);
        var requested = repository.stop(owner, project, run.id());
        assertThat(requested.status()).isEqualTo("NEEDS_RECONCILIATION"); assertThat(requested.executionPending()).isTrue();
        assertThat(requested.errorCode()).isEqualTo(unknown.errorCode()); assertThat(requested.stoppedSha256()).isNull();
        rejected(() -> repository.admit(owner, project, request("second")));
        UUID nextOwner = UUID.randomUUID(); jdbc.update("INSERT INTO guest_users(id) VALUES (?)", nextOwner);
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", nextOwner, project);
        rejected(() -> repository.stop(owner, project, run.id()));
        rejected(() -> repository.stop(nextOwner, project, run.id()));
    }

    @Test void onlyLiveHealthProofCanPublishReadyAndActualExitSettlesTheOriginalIntent() {
        repository.begin(run, execution);
        rejected(() -> repository.ready(run, execution, target, null));
        var proof = health(execution);
        rejected(() -> repository.ready(run, UUID.randomUUID(), target, proof));
        var ready = repository.ready(run, execution, target, proof);
        assertThat(ready.status()).isEqualTo("READY"); assertThat(ready.readyAt()).isNotNull();
        assertThat(repository.ready(run, execution, target, proof)).isEqualTo(ready);
        var outcome = stop();
        rejected(() -> repository.ready(run, execution, target, proof));
        var finished = repository.finish(run, execution, temporary, outcome, null);
        assertThat(finished.status()).isEqualTo("FAILED"); assertThat(finished.executionPending()).isFalse();
        assertThat(finished.finishedAt()).isNotNull(); assertThat(finished.stoppedSha256()).isEqualTo(outcome.stopped().digest());
        assertThat(repository.finish(run, execution, temporary, outcome, null)).isEqualTo(finished);
    }

    @Test void preReadyExitRetainsDataReconciliationEvenWhenTheActualProcessHasStopped() {
        repository.begin(run, execution); health(execution); var outcome = stop();
        var failed = repository.finish(run, execution, temporary, outcome, "RUN_HEALTH_INVALID");
        assertThat(failed.status()).isEqualTo("NEEDS_RECONCILIATION"); assertThat(failed.executionPending()).isFalse();
        assertThat(failed.errorCode()).isEqualTo("RUN_HEALTH_INVALID"); assertThat(failed.finishedAt()).isNull();
        assertThat(failed.stoppedSha256()).isEqualTo(outcome.stopped().digest());
        rejected(() -> repository.admit(owner, project, request("second")));
        assertThat(repository.finish(run, execution, temporary, outcome, null)).isEqualTo(failed);
    }

    @Test void wrongStopProofIsRefusedAndMissingProofNeverClearsTheIntent() {
        repository.begin(run, execution); health(UUID.randomUUID()); var wrong = stop();
        rejected(() -> repository.finish(run, execution, temporary, wrong, null));
        assertThat(repository.get(project, run.id()).executionPending()).isTrue();
        var lost = new ExecutionRunProcess.Outcome("RUN_PROCESS_UNSETTLED", wrong.output(), null);
        var unknown = repository.finish(run, execution, temporary, lost, null);
        assertThat(unknown.status()).isEqualTo("NEEDS_RECONCILIATION"); assertThat(unknown.executionPending()).isTrue();
        assertThat(unknown.stoppedSha256()).isNull(); assertThat(unknown.finishedAt()).isNull();
        rejected(() -> repository.notStarted(run, "RUN_START_FAILED"));
    }

    @Test void failureBeforeIntentCanFinishButCannotClearAnIssuedExecution() {
        var failed = repository.notStarted(run, "RUN_PORT_IN_USE");
        assertThat(failed.status()).isEqualTo("FAILED"); assertThat(failed.executionId()).isNull();
        assertThat(failed.finishedAt()).isNotNull();
        var second = repository.admit(owner, project, request("next"));
        repository.begin(second, execution);
        rejected(() -> repository.notStarted(second, "RUN_START_FAILED"));
        assertThat(repository.get(project, second.id()).executionPending()).isTrue();
    }

    @Test void currentOwnershipAndArchiveAreCheckedButDoNotPreventOwnedCleanup() {
        jdbc.update("UPDATE projects SET archived=true WHERE id=?", project);
        rejected(() -> repository.begin(run, execution));
        jdbc.update("UPDATE projects SET archived=false WHERE id=?", project);
        repository.begin(run, execution); var proof = health(execution);
        jdbc.update("UPDATE projects SET archived=true WHERE id=?", project);
        rejected(() -> repository.ready(run, execution, target, proof));
        UUID nextOwner = UUID.randomUUID(); jdbc.update("INSERT INTO guest_users(id) VALUES (?)", nextOwner);
        jdbc.update("UPDATE projects SET owner_id=?,archived=false WHERE id=?", nextOwner, project);
        rejected(() -> repository.ready(run, execution, target, proof));
        var result = repository.finish(run, execution, temporary, stop(), "RUN_START_FAILED");
        assertThat(result.executionPending()).isFalse(); assertThat(result.finishedAt()).isNull();
    }

    @Test void terminalWriteFailureRollsBackTheStopReceiptWithTheStateUpdate() {
        repository.begin(run, execution);
        var ready = repository.ready(run, execution, target, health(execution)); var outcome = stop();
        var failing = org.mockito.Mockito.spy(jdbc);
        org.mockito.Mockito.doAnswer(call -> {
            if (((String) call.getArgument(0)).startsWith("UPDATE execution_runs SET status=?,error_code=?,finished_at="))
                throw new org.springframework.dao.DataAccessResourceFailureException("test-write-failure");
            return call.callRealMethod();
        }).when(failing).update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
        var nested = new TransactionTemplate(transactions); nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(() -> nested.execute(tx -> new ExecutionRunRepository(failing).finish(run, execution, temporary, outcome, null)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(repository.get(project, run.id())).isEqualTo(ready);
        assertThat(repository.finish(run, execution, temporary, outcome, null).finishedAt()).isNotNull();
    }

    private ExecutionRun.Request request(String key) { return ExecutionRun.request(build, target.id(), null, key); }
    private void rejected(Runnable operation) { assertThatThrownBy(operation::run).isInstanceOf(dev.atoms.demo.common.ApiException.class); }
    private ExecutionHealth.Confirmed health(UUID id) {
        return outside(() -> {
            var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "--add-modules", "jdk.httpserver",
                    "-cp", Path.of("target/test-classes").toAbsolutePath().toString(), ExecutionHealthTest.Child.class.getName(),
                    Integer.toString(target.port()), run.id().toString(), id.toString(), "valid").directory(temporary.toFile());
            command.environment().clear();
            process = ExecutionRunProcess.start(command, run.id(), id, new ExecutionProcess.Deadline(Duration.ofSeconds(15)), () -> {});
            var budget = new ExecutionProcess.Deadline(Duration.ofSeconds(10));
            while (true) {
                var proof = new ExecutionHealth().probe(process, run.id(), id, target, budget);
                if (proof != null) return proof;
                Thread.sleep(20);
            }
        });
    }
    private ExecutionRunProcess.Outcome stop() {
        return outside(() -> { process.requestStop(); return process.await(Duration.ofSeconds(12)); });
    }
    private <T> T outside(Callable<T> operation) {
        var suspended = new TransactionTemplate(transactions);
        suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return suspended.execute(tx -> { try { return operation.call(); } catch (Exception error) { throw new AssertionError(error); } });
    }
}
