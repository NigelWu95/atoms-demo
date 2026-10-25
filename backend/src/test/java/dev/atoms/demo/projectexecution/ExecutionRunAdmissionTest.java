package dev.atoms.demo.projectexecution;

import java.io.IOException;
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

/** Real sealed artifacts and transaction boundaries; metadata and physical DB/migration dependencies are controlled here. */
class ExecutionRunAdmissionTest {
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), buildId = UUID.randomUUID(), bindingId = UUID.randomUUID();
    final ExecutionRunRepository runs = mock(ExecutionRunRepository.class);
    final ExecutionMigrationReviewRepository identities = mock(ExecutionMigrationReviewRepository.class);
    final ExecutionDatabaseBinding databases = mock(ExecutionDatabaseBinding.class);
    final ExecutionMigrationReview migrations = mock(ExecutionMigrationReview.class);
    final Transactions transactions = new Transactions();
    MockEnvironment environment;
    ExecutionPaths paths;
    ExecutionArtifact artifact;
    ExecutionRunService service;
    ExecutionMigrationReviewRepository.Identity identity;
    ExecutionRun run;
    ExecutionRun.Request request;

    @BeforeEach void setup() throws Exception {
        Path root = temporary.toRealPath(); String hash = "a".repeat(64);
        paths = new ExecutionPaths(root.resolve("execution"), List.of());
        var build = new ExecutionBuild(buildId, project, owner, UUID.randomUUID(), hash, hash, hash, "SUCCEEDED", false, null, Instant.now(), Instant.now(), 7);
        var task = paths.createBuild(buildId); Path backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
        Files.write(Files.createDirectory(backend.resolve("target")).resolve("app.jar"), new byte[]{80, 75, 3, 4, 1});
        var tools = new EnumMap<ExecutionToolchain.Kind, ExecutionToolFiles.Summary>(ExecutionToolchain.Kind.class);
        for (var kind : ExecutionToolchain.Kind.values()) tools.put(kind, new ExecutionToolFiles.Summary(hash, 1, 20));
        artifact = ExecutionArtifact.seal(paths, task, build, new ExecutionArtifact.Evidence(tools,
                new ExecutionNpmPolicy.Verified(hash, hash, hash, 1), new ExecutionMavenPolicy.Verified(hash, hash),
                new ExecutionDiagnostics.Frontend(1, 1, 1), new ExecutionJavaReport.Summary(1, 1), new ExecutionPackaging.Summary(1, 1, hash)),
                new ExecutionProcess.Deadline(Duration.ofSeconds(30)), () -> {});
        var target = new ExecutionBindingRepository.Target(project, bindingId, 5432, "admission_app", "admission_role", 29111, 1000, 1001);
        identity = new ExecutionMigrationReviewRepository.Identity(build, artifact.summary().jarSha256(), artifact.summary().manifestSha256(), target);
        request = ExecutionRun.request(buildId, bindingId, null, "once");
        run = new ExecutionRun(UUID.randomUUID(), project, owner, buildId, build.sourceVersionId(), bindingId, null,
                hash, identity.jarSha256(), identity.manifestSha256(), target.hash(), request.keyHash(), request.bodyHash(),
                "STARTING", false, null, false, null, null, Instant.now(), null, null);
        environment = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString())
                .withProperty("atoms.project-execution.root", paths.checkedRoot().toString());
        for (String key : List.of("node", "npm", "java", "maven", "policy")) environment.withProperty("atoms.project-execution." + key, root.resolve(key).toString());
        when(identities.identity(owner, project, buildId)).thenAnswer(call -> { inside(); return identity; });
        when(runs.find(owner, project, request)).thenAnswer(call -> { inside(); return null; });
        when(runs.admit(owner, project, request)).thenAnswer(call -> { inside(); return run; });
        when(runs.get(project, run.id())).thenReturn(run);
        when(databases.verify(eq(project), any())).thenAnswer(call -> { outside(); return target; });
        when(migrations.requireCompatible(eq(owner), eq(project), eq(buildId), isNull(), any(), any())).thenAnswer(call -> {
            outside(); return new ExecutionMigrationReview.View(project, buildId, bindingId, identity.jarSha256(), hash, hash,
                    ExecutionMigrationPolicy.Decision.INITIALIZE);
        });
        service = new ExecutionRunService(new ExecutionSettings(environment), runs, identities, transactions, paths, databases, migrations);
    }
    @AfterEach void close() throws Exception { if (paths != null) paths.close(); }

    @Test void freshAdmissionRechecksInputsAndIssuesOnlyOneNonSerializableLaunchTicket() throws Exception {
        var admission = submit(); assertThat(admission.run()).isEqualTo(run); assertThat(admission.ticket()).isNotNull();
        var claim = service.take(admission.ticket()); assertThat(claim.run).isEqualTo(run);
        assertThat(claim.artifact.summary()).isEqualTo(artifact.summary());
        assertThatThrownBy(() -> service.take(admission.ticket())).hasMessage("RUN_LAUNCH_ALREADY_CLAIMED");
        assertThat(transactions.commits).isEqualTo(2);
        assertThat(paths.checkedRoot().resolve("runs").resolve(run.id().toString())).doesNotExist();
        service.recheck(claim, null);
        verify(databases, times(2)).verify(eq(project), any());
        verify(migrations, times(2)).requireCompatible(eq(owner), eq(project), eq(buildId), isNull(), any(), same(claim.budget));
    }

    @Test void replayReturnsTheStoredRunWithoutOpeningArtifactsOrIssuingAnotherTicket() throws Exception {
        doReturn(run).when(runs).find(owner, project, request);
        Files.writeString(artifact.jar(), "artifact-no-longer-readable");
        var replay = submit(); assertThat(replay.run()).isEqualTo(run); assertThat(replay.ticket()).isNull();
        verifyNoInteractions(identities, databases, migrations); verify(runs, never()).admit(any(), any(), any());
    }

    @Test void winnerAtFinalProjectLockTurnsTheSecondRequestIntoReplay() throws Exception {
        doReturn(null, run).when(runs).find(owner, project, request);
        var replay = submit(); assertThat(replay.run()).isEqualTo(run); assertThat(replay.ticket()).isNull();
        verify(runs, never()).admit(any(), any(), any());
    }

    @Test void changedJarWrongPhysicalBindingAndRejectedMigrationNeverAdmit() throws Exception {
        var wrong = new ExecutionBindingRepository.Target(project, bindingId, 5432, "other_app", "other_role", 29111, 2000, 2001);
        when(databases.verify(eq(project), any())).thenReturn(wrong);
        assertThatThrownBy(this::submit).hasMessage("RUN_DB_BINDING_CONFLICT");
        when(databases.verify(eq(project), any())).thenReturn(identity.binding());
        when(migrations.requireCompatible(eq(owner), eq(project), eq(buildId), isNull(), any(), any()))
                .thenThrow(new IOException("RUN_MIGRATION_CHECK_FAILED"));
        assertThatThrownBy(this::submit).hasMessage("RUN_MIGRATION_CHECK_FAILED");
        Files.writeString(artifact.jar(), "changed-jar");
        assertThatThrownBy(this::submit).hasMessage("EXECUTION_ARTIFACT_INVALID");
        verify(runs, never()).admit(any(), any(), any());
    }

    @Test void revocationDuringMigrationCheckPreventsAdmissionAndCommitDriftRollsBack() throws Exception {
        when(migrations.requireCompatible(eq(owner), eq(project), eq(buildId), isNull(), any(), any())).thenAnswer(call -> {
            environment.withProperty("atoms.project-execution.enabled", "false"); return null;
        });
        assertThatThrownBy(this::submit).isInstanceOf(dev.atoms.demo.common.ApiException.class);
        verify(runs, never()).admit(any(), any(), any());
        environment.withProperty("atoms.project-execution.enabled", "true");
        doReturn(null).when(migrations).requireCompatible(eq(owner), eq(project), eq(buildId), isNull(), any(), any());
        doAnswer(call -> {
            inside();
            environment.withProperty("atoms.project-execution.start-timeout-seconds", "119"); return run;
        }).when(runs).admit(owner, project, request);
        assertThatThrownBy(this::submit).isInstanceOf(dev.atoms.demo.common.ApiException.class);
        assertThat(transactions.rollbacks).isPositive();
    }

    @Test void disabledUnauthorizedAndCallerTransactionFailBeforeInputIo() {
        assertThatThrownBy(() -> service.submit(UUID.randomUUID(), project, buildId, bindingId, null, "once"))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class);
        environment.withProperty("atoms.project-execution.enabled", "false");
        assertThatThrownBy(this::submit).isInstanceOf(dev.atoms.demo.common.ApiException.class);
        environment.withProperty("atoms.project-execution.enabled", "true");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> assertThatThrownBy(this::submit).hasMessage("EXECUTION_TRANSACTION_ACTIVE"));
        verifyNoInteractions(runs, identities, databases, migrations);
    }

    @Test void commitAcknowledgementFailureReturnsNoTicketAndDoesNotRetry() {
        transactions.failCommitAt = 2;
        assertThatThrownBy(this::submit).isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class,
                error -> assertThat(error.code()).isEqualTo("RUN_STATE_UNAVAILABLE")).hasNoCause().hasNoSuppressedExceptions();
        verify(runs, times(1)).admit(owner, project, request);
    }

    @Test void stopIsAShortAuthorizedTransactionWithoutArtifactOrBusinessDatabaseIo() throws Exception {
        when(runs.stop(owner, project, run.id())).thenAnswer(call -> { inside(); return run; });
        assertThat(service.stop(owner, project, run.id())).isEqualTo(run);
        verifyNoInteractions(identities, databases, migrations);
        transactions.failCommitAt = transactions.commits + 1;
        assertThatThrownBy(() -> service.stop(owner, project, run.id()))
                .isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class, e -> assertThat(e.code()).isEqualTo("RUN_STATE_UNAVAILABLE"))
                .hasNoCause().hasNoSuppressedExceptions();
        verify(runs, times(2)).stop(owner, project, run.id());
    }

    @Test void stopRejectsUnauthorizedDisabledNestedTransactionsAndConfigurationDrift() throws Exception {
        assertThatThrownBy(() -> service.stop(UUID.randomUUID(), project, run.id())).isInstanceOf(dev.atoms.demo.common.ApiException.class);
        environment.withProperty("atoms.project-execution.enabled", "false");
        assertThatThrownBy(() -> service.stop(owner, project, run.id())).isInstanceOf(dev.atoms.demo.common.ApiException.class);
        environment.withProperty("atoms.project-execution.enabled", "true");
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                assertThatThrownBy(() -> service.stop(owner, project, run.id())).hasMessage("EXECUTION_TRANSACTION_ACTIVE"));
        verifyNoInteractions(runs, identities, databases, migrations);
        when(runs.stop(owner, project, run.id())).thenAnswer(call -> {
            environment.withProperty("atoms.project-execution.start-timeout-seconds", "119"); return run;
        });
        assertThatThrownBy(() -> service.stop(owner, project, run.id())).isInstanceOf(dev.atoms.demo.common.ApiException.class);
        assertThat(transactions.rollbacks).isPositive();
    }

    @Test void replacementBindsItsPredecessorAndWaitsWithoutReadingArtifactsOrIssuingATicket() throws Exception {
        UUID predecessor = UUID.randomUUID();
        var replacement = ExecutionRun.replacement(buildId, bindingId, null, predecessor, "once");
        assertThat(ExecutionRun.valid(replacement)).isTrue();
        assertThat(replacement.keyHash()).isEqualTo(request.keyHash());
        assertThat(replacement.bodyHash()).isNotEqualTo(request.bodyHash());
        assertThat(ExecutionRun.replacement(buildId, bindingId, null, UUID.randomUUID(), "once").bodyHash()).isNotEqualTo(replacement.bodyHash());
        when(runs.stop(owner, project, predecessor)).thenAnswer(call -> { inside(); return run; });
        Files.writeString(artifact.jar(), "unreadable-until-stopped");
        var waiting = service.replace(owner, project, predecessor, buildId, bindingId, null, "once");
        assertThat(waiting.stopping()).isEqualTo(run); assertThat(waiting.admission()).isNull();
        verifyNoInteractions(databases, migrations); verify(runs, never()).admit(any(), any(), any());
        doReturn(run).when(runs).find(owner, project, replacement);
        var replay = service.replace(owner, project, predecessor, buildId, bindingId, null, "once");
        assertThat(replay.stopping()).isNull(); assertThat(replay.admission().run()).isEqualTo(run); assertThat(replay.admission().ticket()).isNull();
        verify(runs, times(1)).stop(owner, project, predecessor);
    }

    @Test void replacementRechecksMigrationAfterStopAndFailedStopCommitNeverAdmits() throws Exception {
        UUID predecessor = UUID.randomUUID();
        var replacement = ExecutionRun.replacement(buildId, bindingId, null, predecessor, "once");
        var stopped = new ExecutionRun(predecessor, project, owner, buildId, run.sourceVersionId(), bindingId, null,
                run.treeSha256(), run.jarSha256(), run.manifestSha256(), run.bindingIdentitySha256(), run.keySha256(), run.requestSha256(),
                "STOPPED", true, UUID.randomUUID(), false, "a".repeat(64), null, run.createdAt(), Instant.now(), Instant.now());
        when(runs.stop(owner, project, predecessor)).thenAnswer(call -> { inside(); return stopped; });
        when(runs.admit(owner, project, replacement)).thenAnswer(call -> { inside(); return run; });
        transactions.failCommitAt = 1;
        assertThatThrownBy(() -> service.replace(owner, project, predecessor, buildId, bindingId, null, "once"))
                .isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class, e -> assertThat(e.code()).isEqualTo("RUN_STATE_UNAVAILABLE"));
        verify(runs, never()).admit(any(), any(), any()); verifyNoInteractions(databases, migrations);
        var next = service.replace(owner, project, predecessor, buildId, bindingId, null, "once");
        assertThat(next.stopping()).isNull(); assertThat(next.admission().ticket()).isNotNull();
        verify(migrations, times(1)).requireCompatible(eq(owner), eq(project), eq(buildId), isNull(), any(), any());
        when(migrations.requireCompatible(eq(owner), eq(project), eq(buildId), isNull(), any(), any()))
                .thenThrow(new IOException("RUN_MIGRATION_INCOMPATIBLE"));
        assertThatThrownBy(() -> service.replace(owner, project, predecessor, buildId, bindingId, null, "once"))
                .hasMessage("RUN_MIGRATION_INCOMPATIBLE");
        verify(runs, times(1)).admit(owner, project, replacement);
    }

    @Test void unknownPredecessorRemainsBlockedAndInvalidTargetDoesNotStopTheCurrentRun() throws Exception {
        var unknown = resident(UUID.randomUUID(), "NEEDS_RECONCILIATION", true);
        when(runs.stop(owner, project, run.id())).thenAnswer(call -> { inside(); return unknown; });
        var blocked = service.replace(owner, project, run.id(), buildId, bindingId, null, "replace");
        assertThat(blocked.stopping()).isEqualTo(unknown); assertThat(blocked.admission()).isNull();
        verifyNoInteractions(databases, migrations); verify(runs, never()).admit(any(), any(), any());
        doThrow(ExecutionRun.error(409, "RUN_BUILD_NOT_READY")).when(identities).identity(owner, project, buildId);
        assertThatThrownBy(() -> service.replace(owner, project, run.id(), buildId, bindingId, null, "invalid"))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class);
        verify(runs, times(1)).stop(owner, project, run.id());
        assertThatThrownBy(() -> ExecutionRun.replacement(buildId, bindingId, null, null, "invalid"))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class);
    }

    @Test void residentMonitoringChecksCurrentIdentityAndStopButNeverRestartsTheStartupDeadline() throws Exception {
        try (var deadlines = mockConstruction(ExecutionProcess.Deadline.class,
                (deadline, context) -> when(deadline.remaining()).thenReturn(Duration.ofSeconds(120)))) {
            var claim = service.take(submit().ticket()); UUID execution = UUID.randomUUID();
            var ready = resident(execution, "READY", false);
            doReturn(ready).when(runs).get(project, run.id());
            when(deadlines.constructed().get(0).remaining()).thenReturn(Duration.ZERO);
            assertThat(service.stopRequested(claim, execution)).isFalse();
            assertThatThrownBy(() -> service.stopRequested(claim, UUID.randomUUID())).isInstanceOf(dev.atoms.demo.common.ApiException.class);
            doReturn(resident(execution, "STOPPING", true)).when(runs).get(project, run.id());
            assertThat(service.stopRequested(claim, execution)).isTrue();
            environment.withProperty("atoms.project-execution.enabled", "false");
            assertThatThrownBy(() -> service.stopRequested(claim, execution)).isInstanceOf(dev.atoms.demo.common.ApiException.class);
            // The only business DB/migration check was admission; polling must not re-run a migration or its old approval.
            verify(databases, times(1)).verify(eq(project), any());
            verify(migrations, times(1)).requireCompatible(eq(owner), eq(project), eq(buildId), isNull(), any(), any());
        }
    }
    private ExecutionRun resident(UUID execution, String status, boolean stopping) {
        return new ExecutionRun(run.id(), project, owner, buildId, run.sourceVersionId(), bindingId, null,
                run.treeSha256(), run.jarSha256(), run.manifestSha256(), run.bindingIdentitySha256(), run.keySha256(), run.requestSha256(),
                status, stopping, execution, true, null, null, run.createdAt(), Instant.now(), null);
    }

    private ExecutionRunService.Admission submit() throws IOException { return service.submit(owner, project, buildId, bindingId, null, "once"); }
    private void inside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); }
    private void outside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private static class Transactions extends AbstractPlatformTransactionManager {
        int commits, rollbacks, failCommitAt;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (++commits == failCommitAt) throw new org.springframework.transaction.TransactionSystemException("private-commit-details");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
