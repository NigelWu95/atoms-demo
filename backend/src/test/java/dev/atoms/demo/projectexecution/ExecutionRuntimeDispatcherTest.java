package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real managed threads with latch-controlled admission/metadata boundaries; no fake OS stop receipts. */
class ExecutionRuntimeDispatcherTest {
    final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), build = UUID.randomUUID(), binding = UUID.randomUUID();
    final ExecutionRuntimeResources resources = mock(ExecutionRuntimeResources.class);
    final ExecutionRunRepository runs = mock(ExecutionRunRepository.class);
    final AtomicReference<List<ExecutionRun>> unfinished = new AtomicReference<>(List.of());
    final AtomicInteger failures = new AtomicInteger();
    final CountDownLatch admitting = new CountDownLatch(1), admitted = new CountDownLatch(1), executing = new CountDownLatch(1), finish = new CountDownLatch(1);
    final ExecutionRun.Request request = ExecutionRun.request(build, binding, null, "start");
    final ExecutionRunService.Ticket ticket = mock(ExecutionRunService.Ticket.class);
    final ExecutionRun row = row(project, request, "STARTING", null);
    final AtomicBoolean ignoreAdmissionInterrupt = new AtomicBoolean();
    final MockEnvironment environment = new MockEnvironment();
    ExecutionRuntimeDispatcher dispatcher;
    boolean expectRetained;

    @BeforeEach void setup() throws Exception {
        environment.withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString())
                .withProperty("atoms.project-execution.max-running-projects", "1");
        for (String name : List.of("root", "node", "npm", "java", "maven", "policy"))
            environment.withProperty("atoms.project-execution." + name, "/unused/runtime/" + name);
        when(runs.unfinished()).thenAnswer(call -> unfinished.get());
        when(resources.submit(owner, project, request)).thenAnswer(call -> {
            admitting.countDown(); await(admitted, ignoreAdmissionInterrupt.get()); unfinished.set(List.of(row));
            return new ExecutionRunService.Admission(row, ticket);
        });
        when(resources.execute(ticket)).thenAnswer(call -> {
            executing.countDown();
            try { await(finish, false); } catch (InterruptedException stopping) { /* Simulate acknowledged worker settlement. */ }
            finally { unfinished.set(List.of()); }
            return new ExecutionRun(row.id(), project, owner, build, row.sourceVersionId(), binding, null,
                    row.treeSha256(), row.jarSha256(), row.manifestSha256(), row.bindingIdentitySha256(), row.keySha256(), row.requestSha256(),
                    "STOPPED", true, null, false, null, null, row.createdAt(), null, Instant.now());
        });
        dispatcher = new ExecutionRuntimeDispatcher(new ExecutionSettings(environment), resources, runs, () -> {}, failures::incrementAndGet);
    }

    @AfterEach void close() throws Exception {
        admitted.countDown(); finish.countDown();
        dispatcher.requestStop(); assertThat(dispatcher.awaitTermination(Duration.ofSeconds(5))).isEqualTo(!expectRetained);
    }

    @Test void oneReservationCoversAdmissionHandoffAndResidenceAndDuplicateCallersCannotCancelIt() throws Exception {
        var first = dispatcher.submit(owner, project, request); await(admitting, false);
        var duplicate = dispatcher.submit(owner, project, request);
        assertThat(first.toCompletableFuture().cancel(true)).isTrue();
        // Persisted row is visible before submit returns its fresh ticket.
        unfinished.set(List.of(row)); dispatcher.checkOwnedRuns();
        admitted.countDown(); assertThat(duplicate.toCompletableFuture().get(5, TimeUnit.SECONDS)).isEqualTo(row);
        await(executing, false); dispatcher.checkOwnedRuns();
        verify(resources, times(1)).submit(owner, project, request); verify(resources, times(1)).execute(ticket);
        assertThat(failures).hasValue(0);
    }

    @Test void fullCapacityStillAuthorizesReplayAndRejectsFreshOrConflictingRequests() throws Exception {
        dispatcher.submit(owner, project, request); await(admitting, false);
        UUID another = UUID.randomUUID(); var existing = row(another, request, "STOPPED", Instant.now());
        when(resources.existing(owner, another, request)).thenReturn(existing);
        assertThat(dispatcher.submit(owner, another, request).toCompletableFuture().get()).isEqualTo(existing);
        assertCode(() -> dispatcher.submit(owner, UUID.randomUUID(), request), "RUN_CAPACITY_FULL");
        assertCode(() -> dispatcher.submit(owner, project, ExecutionRun.request(UUID.randomUUID(), binding, null, "start")), "RUN_IDEMPOTENCY_CONFLICT");
        assertCode(() -> dispatcher.submit(owner, project, ExecutionRun.request(build, binding, null, "another")), "RUN_PROJECT_BUSY");
        when(resources.existing(owner, project, request)).thenThrow(ExecutionRun.error(403, "EXECUTION_FORBIDDEN"));
        assertCode(() -> dispatcher.submit(owner, project, request), "EXECUTION_FORBIDDEN");
    }

    @Test void shutdownDoesNotHoldALockAcrossAdmissionAndConsumesATicketReturnedAfterStop() throws Exception {
        ignoreAdmissionInterrupt.set(true);
        var result = dispatcher.submit(owner, project, request); await(admitting, false);
        dispatcher.requestStop();
        assertThat(dispatcher.awaitTermination(Duration.ofMillis(30))).isFalse();
        assertThatThrownBy(() -> dispatcher.submit(owner, project, request)).hasMessage("RUN_DISPATCH_UNAVAILABLE");
        finish.countDown(); admitted.countDown();
        assertThat(result.toCompletableFuture().get(5, TimeUnit.SECONDS)).isEqualTo(row);
        assertThat(dispatcher.awaitTermination(Duration.ofSeconds(5))).isTrue();
        verify(resources).execute(ticket); verify(resources).requestStopOwned();
    }

    @Test void lookupInProgressAlsoPreventsResourceReleaseAndCannotScheduleAfterShutdown() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(resources.existing(owner, project, request)).thenAnswer(call -> { entered.countDown(); await(release, true); return null; });
        var caller = Executors.newSingleThreadExecutor();
        try {
            var result = caller.submit(() -> dispatcher.submit(owner, project, request)); await(entered, false);
            dispatcher.requestStop(); assertThat(dispatcher.awaitTermination(Duration.ofMillis(30))).isFalse();
            release.countDown();
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
            assertThat(dispatcher.awaitTermination(Duration.ofSeconds(5))).isTrue();
            verify(resources, never()).submit(any(), any(), any(ExecutionRun.Request.class));
        } finally { release.countDown(); caller.shutdownNow(); assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void foreignAndUncertainIntentsAreNeverRecognizedAsOwnedWork() throws Exception {
        dispatcher.submit(owner, project, request); await(admitting, false);
        for (var foreign : List.of(row(UUID.randomUUID(), request, "STARTING", null),
                row(project, ExecutionRun.request(build, binding, null, "other"), "STARTING", null),
                row(project, request, "NEEDS_RECONCILIATION", null), row(project, request, "READY", null))) {
            unfinished.set(List.of(foreign));
            assertThatThrownBy(dispatcher::checkOwnedRuns).hasMessage("RUN_DISPATCH_UNAVAILABLE");
        }
        unfinished.set(List.of());
    }

    @Test void preexistingQuarantinedRunKeepsCapacityWithoutBeingAdopted() throws Exception {
        environment.withProperty("atoms.project-workbench.owner-access-enabled", "true");
        var retained = row(UUID.randomUUID(), request, "NEEDS_RECONCILIATION", null);
        unfinished.set(List.of(retained));
        dispatcher.checkOwnedRuns();
        assertCode(() -> dispatcher.submit(owner, project, request), "RUN_CAPACITY_FULL");
        verify(resources, never()).submit(any(), any(), any(ExecutionRun.Request.class));
        verify(resources, never()).execute(any());
        assertThat(unfinished.get()).containsExactly(retained);
        // Unowned active rows are still an error, rather than being silently treated as quarantined.
        unfinished.set(List.of(row(UUID.randomUUID(), request, "READY", null)));
        assertThatThrownBy(dispatcher::checkOwnedRuns).hasMessage("RUN_DISPATCH_UNAVAILABLE");
        unfinished.set(List.of());
    }

    @Test void unknownCompletionStopsDispatchAndRetainsItsSlotInsteadOfClaimingTermination() throws Exception {
        expectRetained = true;
        doAnswer(call -> { executing.countDown(); throw new IOException("RUN_STATE_UNAVAILABLE"); }).when(resources).execute(ticket);
        admitted.countDown(); dispatcher.submit(owner, project, request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        await(executing, false); awaitFailure();
        assertThat(dispatcher.awaitTermination(Duration.ofMillis(30))).isFalse();
        assertThat(unfinished.get()).containsExactly(row);
        // Executor has stopped, but metadata/ownership remain unresolved. No OS process or DB row was created here.
    }

    @Test void fourProjectsRunOnSeparateResidentThreadsAndTheFifthHasNoAdmission() throws Exception {
        dispatcher.requestStop(); assertThat(dispatcher.awaitTermination(Duration.ofSeconds(5))).isTrue();
        environment.withProperty("atoms.project-execution.max-running-projects", "4");
        dispatcher = new ExecutionRuntimeDispatcher(new ExecutionSettings(environment), resources, runs, () -> {}, failures::incrementAndGet);
        var tickets = new ConcurrentHashMap<ExecutionRunService.Ticket, ExecutionRun>();
        var threads = ConcurrentHashMap.<Thread>newKeySet(); var allRunning = new CountDownLatch(4);
        doAnswer(call -> {
            var admittedRow = row(call.getArgument(1), call.getArgument(2), "STARTING", null);
            var fresh = mock(ExecutionRunService.Ticket.class); tickets.put(fresh, admittedRow);
            return new ExecutionRunService.Admission(admittedRow, fresh);
        }).when(resources).submit(eq(owner), any(), any(ExecutionRun.Request.class));
        doAnswer(call -> {
            threads.add(Thread.currentThread()); allRunning.countDown();
            try { await(finish, false); } catch (InterruptedException stopping) { /* Controlled settlement. */ }
            return terminal(tickets.get(call.getArgument(0)));
        }).when(resources).execute(any());
        for (int i = 0; i < 4; i++) dispatcher.submit(owner, UUID.randomUUID(), request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        await(allRunning, false); assertThat(threads).hasSize(4);
        assertThat(threads).allSatisfy(thread -> assertThat(thread.getName()).isEqualTo("atoms-runtime-worker"));
        assertCode(() -> dispatcher.submit(owner, UUID.randomUUID(), request), "RUN_CAPACITY_FULL");
        assertThat(tickets).hasSize(4);
    }

    @Test void retainedHandlePreventsTerminationEvenWithNoUnfinishedMetadata() throws Exception {
        when(resources.hasHeldProcesses()).thenReturn(true);
        dispatcher.requestStop(); assertThat(dispatcher.awaitTermination(Duration.ofMillis(30))).isFalse();
        assertThat(unfinished.get()).isEmpty();
        when(resources.hasHeldProcesses()).thenReturn(false);
    }

    @Test void replacementStopPhaseUsesNoExtraSlotAndOnlySchedulesAfterOldWorkHasSettled() throws Exception {
        admitted.countDown(); dispatcher.submit(owner, project, request).toCompletableFuture().get(5, TimeUnit.SECONDS); await(executing, false);
        var replace = ExecutionRun.replacement(build, binding, null, row.id(), "replace");
        var stopping = row(project, request, "STOPPING", null);
        when(resources.prepareReplacement(owner, project, replace)).thenReturn(new ExecutionRunService.Replacement(stopping, null));
        assertThat(dispatcher.submit(owner, project, replace).toCompletableFuture().get()).isEqualTo(stopping);
        verify(resources, never()).submit(owner, project, replace);
        when(resources.prepareReplacement(owner, project, replace)).thenReturn(new ExecutionRunService.Replacement(null, new ExecutionRunService.Admission(row, null)));
        assertThat(dispatcher.submit(owner, project, replace).toCompletableFuture().get()).isEqualTo(row);
        verify(resources, times(1)).execute(ticket);
    }

    @Test void callerTransactionsAreRejectedBeforeAnyResourceUse() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> dispatcher.submit(owner, project, request)).hasMessage("EXECUTION_TRANSACTION_ACTIVE"); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verifyNoInteractions(resources, runs);
    }

    @Test void onlyTheOriginalReturnedStoppedFailureCanBeReleasedAfterExactAcceptance() throws Exception {
        var failed = stoppedFailure();
        doAnswer(call -> {
            executing.countDown(); await(finish, true); unfinished.set(List.of(failed)); return failed;
        }).when(resources).execute(ticket);
        admitted.countDown(); dispatcher.submit(owner, project, request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        await(executing, false);
        assertThatThrownBy(() -> dispatcher.checkStoppedFailure(failed)).hasMessage("RUN_DISPATCH_UNAVAILABLE");
        finish.countDown(); awaitFailure();
        assertThat(dispatcher.awaitTermination(Duration.ofSeconds(5))).isFalse();
        dispatcher.checkStoppedFailure(failed);
        when(resources.hasHeldProcesses()).thenReturn(true);
        assertThatThrownBy(() -> dispatcher.checkStoppedFailure(failed)).hasMessage("RUN_DISPATCH_UNAVAILABLE");
        when(resources.hasHeldProcesses()).thenReturn(false);
        var audit = audit(failed, failed.executionId());
        assertThatThrownBy(() -> dispatcher.acceptStoppedFailure(failed, audit(failed, UUID.randomUUID())))
                .hasMessage("RUN_DISPATCH_UNAVAILABLE");
        assertThat(dispatcher.isTerminated()).isFalse();
        // Only the service can supply this trusted committed audit; no OS stop capability is constructed here.
        unfinished.set(List.of()); dispatcher.acceptStoppedFailure(failed, audit);
        assertThat(dispatcher.isTerminated()).isTrue();
        dispatcher.acceptStoppedFailure(failed, audit); // A pure repeat cannot resurrect or re-adopt a job.
        verify(resources, times(1)).execute(ticket);
    }

    @Test void matchingPersistedFailureCannotReplaceALostWorkerReturn() throws Exception {
        expectRetained = true; var failed = stoppedFailure();
        doAnswer(call -> {
            unfinished.set(List.of(failed)); throw new IOException("RUN_STATE_UNAVAILABLE");
        }).when(resources).execute(ticket);
        admitted.countDown(); dispatcher.submit(owner, project, request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        awaitFailure(); assertThat(dispatcher.awaitTermination(Duration.ofSeconds(5))).isFalse();
        assertThatThrownBy(() -> dispatcher.checkStoppedFailure(failed)).hasMessage("RUN_DISPATCH_UNAVAILABLE");
        assertThatThrownBy(() -> dispatcher.acceptStoppedFailure(failed, audit(failed, failed.executionId())))
                .hasMessage("RUN_DISPATCH_UNAVAILABLE");
    }

    @Test void anotherDispatcherCannotAdoptTheSamePersistedStoppedFailure() throws Exception {
        var failed = stoppedFailure(); unfinished.set(List.of(failed));
        dispatcher.requestStop();
        assertThatThrownBy(() -> dispatcher.checkStoppedFailure(failed)).hasMessage("RUN_DISPATCH_UNAVAILABLE");
        dispatcher.acceptStoppedFailure(failed, audit(failed, failed.executionId()));
        assertThat(dispatcher.isTerminated()).isTrue();
        assertThat(unfinished.get()).containsExactly(failed);
        verify(resources, never()).execute(any());
    }

    @Test void lostWorkerReturnStillRetainsOwnershipWhenDatabaseLooksTerminal() throws Exception {
        expectRetained = true;
        doAnswer(call -> { unfinished.set(List.of()); throw new IOException("RUN_STATE_UNAVAILABLE"); }).when(resources).execute(ticket);
        admitted.countDown(); dispatcher.submit(owner, project, request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        awaitFailure(); assertThat(dispatcher.awaitTermination(Duration.ofSeconds(5))).isFalse();
        assertThat(unfinished.get()).isEmpty();
        assertThatThrownBy(() -> dispatcher.checkStoppedFailure(stoppedFailure())).hasMessage("RUN_DISPATCH_UNAVAILABLE");
    }

    @Test void acceptingOneFailureDoesNotClearAnotherProjectsUnconfirmedJob() throws Exception {
        dispatcher.requestStop(); assertThat(dispatcher.awaitTermination(Duration.ofSeconds(5))).isTrue();
        environment.withProperty("atoms.project-execution.max-running-projects", "2");
        dispatcher = new ExecutionRuntimeDispatcher(new ExecutionSettings(environment), resources, runs, () -> {}, failures::incrementAndGet);
        var second = row(UUID.randomUUID(), request, "STARTING", null);
        var firstFailure = stoppedFailure(row); var secondFailure = stoppedFailure(second);
        var both = new CountDownLatch(2); var secondTicket = mock(ExecutionRunService.Ticket.class);
        doReturn(new ExecutionRunService.Admission(row, ticket)).when(resources).submit(owner, project, request);
        doReturn(new ExecutionRunService.Admission(second, secondTicket)).when(resources).submit(owner, second.projectId(), request);
        doAnswer(call -> {
            both.countDown(); await(finish, true);
            return call.getArgument(0) == ticket ? firstFailure : secondFailure;
        }).when(resources).execute(any());
        dispatcher.submit(owner, project, request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        dispatcher.submit(owner, second.projectId(), request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        await(both, false); unfinished.set(List.of(firstFailure, secondFailure)); finish.countDown();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (failures.get() < 2 && System.nanoTime() < until) Thread.sleep(10);
        assertThat(failures).hasValue(2); assertThat(dispatcher.awaitTermination(Duration.ofSeconds(5))).isFalse();
        dispatcher.checkStoppedFailure(firstFailure); dispatcher.checkStoppedFailure(secondFailure);
        unfinished.set(List.of(secondFailure)); dispatcher.acceptStoppedFailure(firstFailure, audit(firstFailure, firstFailure.executionId()));
        assertThat(dispatcher.isTerminated()).isFalse(); dispatcher.checkStoppedFailure(secondFailure);
        unfinished.set(List.of()); dispatcher.acceptStoppedFailure(secondFailure, audit(secondFailure, secondFailure.executionId()));
        assertThat(dispatcher.isTerminated()).isTrue();
    }

    private ExecutionRun stoppedFailure() { return stoppedFailure(row); }
    private ExecutionRun stoppedFailure(ExecutionRun original) {
        return new ExecutionRun(original.id(), original.projectId(), owner, build, original.sourceVersionId(), binding, null,
                original.treeSha256(), original.jarSha256(), original.manifestSha256(), original.bindingIdentitySha256(), original.keySha256(), original.requestSha256(),
                "NEEDS_RECONCILIATION", false, UUID.randomUUID(), false, "e".repeat(64), "RUN_HEALTH_INVALID", original.createdAt(), null, null);
    }
    private ExecutionRunReconciliationRepository.Audit audit(ExecutionRun failed, UUID execution) {
        return new ExecutionRunReconciliationRepository.Audit(UUID.randomUUID(), failed.id(), failed.projectId(), owner, execution,
                failed.stoppedSha256(), "f".repeat(64), "a".repeat(64), "b".repeat(64), Instant.now());
    }

    private ExecutionRun row(UUID target, ExecutionRun.Request req, String status, Instant finished) {
        return new ExecutionRun(UUID.randomUUID(), target, owner, req.build(), UUID.randomUUID(), req.binding(), req.review(),
                "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), req.keyHash(), req.bodyHash(), status,
                false, null, false, null, null, Instant.now(), null, finished);
    }
    private ExecutionRun terminal(ExecutionRun old) {
        return new ExecutionRun(old.id(), old.projectId(), old.ownerId(), old.buildId(), old.sourceVersionId(), old.bindingId(), old.migrationReviewId(),
                old.treeSha256(), old.jarSha256(), old.manifestSha256(), old.bindingIdentitySha256(), old.keySha256(), old.requestSha256(),
                "STOPPED", true, null, false, null, null, old.createdAt(), null, Instant.now());
    }
    private static void await(CountDownLatch latch, boolean ignoreInterrupt) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (;;) {
            try { assertThat(latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)).isTrue(); return; }
            catch (InterruptedException error) { if (!ignoreInterrupt) throw error; }
        }
    }
    private void awaitFailure() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (failures.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(failures).hasValue(1);
    }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
