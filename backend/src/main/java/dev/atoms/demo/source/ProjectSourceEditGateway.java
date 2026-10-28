package dev.atoms.demo.source;

import dev.atoms.demo.execution.OwnedProcess;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Narrow internal capability boundary for editors. Never accepts a client-supplied filesystem path. */
@Service
public class ProjectSourceEditGateway {
    private final ProjectSourceService service;
    private final SourceRepository repository;
    private final SourceEditLeaseRepository leases;
    private final SourceWorkspaceStore workspaces;
    private final SourceCandidateStore candidates;
    private final java.util.function.Supplier<SourceCoordinator> coordinator;
    private final TransactionTemplate transaction;
    private final ProjectSourceEnvironment environment;
    private final Map<UUID, EditHandle> issued = new ConcurrentHashMap<>();
    private final Semaphore capacity = new Semaphore(64);

    /** Lifecycle readiness only; each publication still verifies the coordinator's database lease. */
    public boolean readyForAgent() {
        var scheduler = coordinator.get();
        return scheduler != null && scheduler.isRunning();
    }

    /** Trusted template adapter only. Publishes an initial source version, never an Agent task or business-ready state. */
    public Submitted initializeTemplate(UUID owner, UUID project, SourceTree tree, String templateId, String digest, String key) {
        return initializeTemplate(owner, project, tree, null, templateId, digest, key);
    }

    /** Trusted legacy adapter only; records the authorized, immutable legacy version being imported. */
    public Submitted importTemplate(UUID owner, UUID project, SourceTree tree, UUID legacyVersion, String templateId, String digest, String key) {
        return initializeTemplate(owner, project, tree, java.util.Objects.requireNonNull(legacyVersion), templateId, digest, key);
    }

    private Submitted initializeTemplate(UUID owner, UUID project, SourceTree tree, UUID legacyVersion, String templateId, String digest, String key) {
        requireOutsideTransaction();
        service.authorize(owner, project);
        if (templateId == null || digest == null) throw new IllegalArgumentException("Controlled template origin required");
        var scheduler = coordinator.get();
        if (scheduler == null || !scheduler.isRunning()) throw ProjectSourceService.conflict("SOURCE_COORDINATOR_UNAVAILABLE");
        var submission = scheduler.initialize(owner, project, tree, legacyVersion, templateId, digest, key);
        return new Submitted(submission.operation(), submission.completion());
    }

    ProjectSourceEditGateway(ProjectSourceService service, SourceRepository repository, ProjectSourceEnvironment environment,
                            PlatformTransactionManager transactions) {
        this(service, repository, environment, transactions, (java.util.function.Supplier<SourceCoordinator>) () -> null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    ProjectSourceEditGateway(ProjectSourceService service, SourceRepository repository, ProjectSourceEnvironment environment,
                            PlatformTransactionManager transactions, org.springframework.beans.factory.ObjectProvider<SourceCoordinator> coordinator) {
        this(service, repository, environment, transactions, (java.util.function.Supplier<SourceCoordinator>) coordinator::getIfAvailable);
    }

    ProjectSourceEditGateway(ProjectSourceService service, SourceRepository repository, ProjectSourceEnvironment environment,
                            PlatformTransactionManager transactions, java.util.function.Supplier<SourceCoordinator> coordinator) {
        this.service = service; this.repository = repository; this.leases = repository.edits();
        this.environment = environment;
        this.workspaces = new SourceWorkspaceStore(service, new SourceQuery(service, repository, environment), environment);
        this.candidates = new SourceCandidateStore(workspaces, environment);
        this.coordinator = coordinator;
        this.transaction = new TransactionTemplate(transactions);
    }

    public EditHandle claimEdit(UUID owner, UUID project, UUID base, long revision, UUID task) {
        java.util.Objects.requireNonNull(task);
        if (!capacity.tryAcquire()) throw ProjectSourceService.conflict("SOURCE_CAPACITY_EXCEEDED");
        var slotReleased = new AtomicBoolean();
        try { return transaction.execute(status -> {
            service.writableBase(owner, project, base, revision);
            var lease = leases.claim(owner, project, base, revision, task);
            var handle = new EditHandle(lease, slotReleased);
            issued.put(lease.id(), handle);
            // Agent admission can join this transaction; a failed task insert must revoke its handle too.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int completion) {
                    if (completion != STATUS_COMMITTED) {
                        issued.remove(lease.id(), handle);
                        releaseCapacity(slotReleased);
                    }
                }
            });
            return handle;
        }); } catch (RuntimeException | Error error) { releaseCapacity(slotReleased); throw error; }
    }

    public synchronized void prepareEdit(EditHandle handle) {
        prepareEdit(handle, null);
    }

    public synchronized void prepareEdit(EditHandle handle, SourceExecutionBudget budget) {
        requireOutsideTransaction(); owned(handle);
        if (handle.budgetAssigned && handle.budget != budget) throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
        handle.budget = budget; handle.budgetAssigned = true;
        var deadline = deadline(handle);
        if (handle.workspace != null) { workspaces.checked(handle.workspace, deadline); return; }
        var lease = handle.lease;
        handle.workspace = workspaces.prepare(lease.ownerId(), lease.projectId(), lease.baseVersionId(), lease.baseRevision(), lease.taskId(), lease, deadline);
        handle.directory = workspaces.checked(handle.workspace, deadline);
    }

    /** For the trusted process adapter only, not an HTTP DTO or a path editable by the model. */
    public synchronized Path workPath(EditHandle handle) {
        requireOutsideTransaction(); owned(handle);
        if (handle.workspace == null) throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
        return workspaces.checked(handle.workspace, deadline(handle));
    }

    /** Bounded snapshot of the owned edit; reading is not a stop receipt or a publication permit. */
    public synchronized SourceTree readEdit(EditHandle handle) {
        requireOutsideTransaction(); owned(handle);
        if (handle.workspace == null) throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
        return workspaces.read(handle.workspace, deadline(handle));
    }

    /** DB-only check of the held, never-executed edit. May join a caller's short claim transaction. */
    public void requireUnstartedEdit(EditHandle handle) {
        owned(handle);
        transaction.executeWithoutResult(status -> {
            var lease = handle.lease;
            service.writableBase(lease.ownerId(), lease.projectId(), lease.baseVersionId(), lease.baseRevision(), lease);
            var current = leases.get(lease.projectId(), lease.id());
            if (current.executionId() != null || current.executionPending() || leases.operation(lease) != null)
                throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
        });
    }

    /** Durable intent is committed before the caller starts its process. May join Agent admission's transaction. */
    public void markExecutionStarted(EditHandle handle, UUID execution) {
        owned(handle);
        if (handle.workspace == null || handle.directory == null) throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
        transaction.executeWithoutResult(status -> {
            var lease = handle.lease;
            service.writableBase(lease.ownerId(), lease.projectId(), lease.baseVersionId(), lease.baseRevision(), lease);
            leases.beginExecution(lease, execution);
        });
    }

    /** Stop facts may be recorded after timeout or ownership changes; they do not authorize new source publication. */
    public void confirmExecutionStopped(EditHandle handle, OwnedProcess.Stopped evidence) {
        owned(handle);
        transaction.executeWithoutResult(status -> {
            repository.lockEvidence(handle.projectId());
            var current = leases.get(handle.projectId(), handle.lease.id());
            if (evidence == null || !evidence.matches(handle.taskId(), current.executionId(), handle.directory))
                throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
            leases.confirmStopped(handle.lease, current.executionId(), evidence.digest());
        });
    }

    public sealed interface Acceptance permits NoChanges, Submitted {}
    public record NoChanges(UUID versionId) implements Acceptance {}
    public record Submitted(SourceOperation operation, java.util.concurrent.CompletableFuture<SourceOperation> completion) implements Acceptance {}

    /** Only a stopped task can freeze files. Replays return the durable operation, not a second Git write. */
    public synchronized Acceptance sealAndAccept(EditHandle handle, String idempotencyKey) {
        return accept(handle, idempotencyKey, null);
    }

    /**
     * Trusted caller validation runs on the sealed candidate before any new publication/no-change result.
     * A successful replay returns the original fact; it never authorizes different content or a second write.
     */
    public synchronized Acceptance sealAndAccept(EditHandle handle, String idempotencyKey,
                                                 java.util.function.Consumer<SourceTree> validation) {
        return accept(handle, idempotencyKey, java.util.Objects.requireNonNull(validation));
    }

    private Acceptance accept(EditHandle handle, String idempotencyKey, java.util.function.Consumer<SourceTree> validation) {
        requireOutsideTransaction(); owned(handle);
        String key = SourceRequest.key(idempotencyKey);
        var lease = handle.lease;
        service.authorize(lease.ownerId(), lease.projectId());
        if (handle.acceptanceKey != null && !handle.acceptanceKey.equals(key))
            throw ProjectSourceService.conflict("SOURCE_IDEMPOTENCY_CONFLICT");
        if (handle.accepted instanceof Submitted submitted) {
            var current = service.operation(lease.ownerId(), lease.projectId(), submitted.operation().id());
            return new Submitted(current, submitted.completion());
        }
        if (handle.accepted instanceof NoChanges) return handle.accepted;
        transaction.executeWithoutResult(status -> {
            repository.lockEvidence(lease.projectId());
            leases.requirePermit(lease.ownerId(), lease.projectId(), lease.baseVersionId(), lease.baseRevision(), lease, true);
        });
        var deadline = deadline(handle);
        if (handle.candidate == null) handle.candidate = candidates.seal(handle.workspace, deadline);
        if (validation != null) validation.accept(candidates.read(lease.ownerId(), lease.projectId(), handle.candidate, deadline));
        if (handle.candidate.treeSha256().equals(repository.version(lease.projectId(), lease.baseVersionId()).treeSha256())) {
            handle.acceptanceKey = key;
            handle.accepted = new NoChanges(lease.baseVersionId());
            candidates.release(handle.candidate);
            return handle.accepted;
        }
        var scheduler = coordinator.get();
        if (scheduler == null || !scheduler.isRunning()) throw ProjectSourceService.conflict("SOURCE_COORDINATOR_UNAVAILABLE");
        var prepared = new SourceAcceptance(service, candidates).prepare(lease.ownerId(), lease.projectId(),
                handle.candidate, idempotencyKey, lease, deadline);
        handle.acceptanceKey = key;
        var submission = scheduler.submit(lease.ownerId(), lease.projectId(), prepared.operation().id(), prepared.tree(), deadline);
        handle.accepted = new Submitted(submission.operation(), submission.completion());
        return handle.accepted;
    }

    /** DB-only cancellation must never wait for the file-I/O monitor while holding a project row lock. */
    public Cancellation cancelEdit(UUID owner, EditHandle handle) {
        owned(handle);
        return transaction.execute(status -> {
            repository.lockEvidence(handle.projectId()); service.authorize(owner, handle.projectId());
            var operation = leases.operation(handle.lease);
            if (operation != null && operation.status().equals("COMMITTED")) return new Cancellation(false, operation);
            leases.requestCancel(handle.lease);
            if (operation != null) operation = service.cancel(owner, handle.projectId(), operation.id());
            return new Cancellation(leases.get(handle.projectId(), handle.leaseId()).executionPending(), operation);
        });
    }
    public record Cancellation(boolean executionPending, SourceOperation operation) {}

    /** Trusted held-capability fact lookup for settlement, even if owner or deadline changed after publication. */
    public SourceOperation operationFact(EditHandle handle) {
        owned(handle); return leases.operation(handle.lease);
    }

    /** Fact issued by this gateway after candidate validation; a caller-created NoChanges DTO is not evidence. */
    public boolean noChangesFact(EditHandle handle) {
        owned(handle); return handle.accepted instanceof NoChanges;
    }

    /** Startup-only fact lookup under the caller's exclusive coordinator lease. Does not release or recreate an edit. */
    public RecoveryFact inspectAbandonedEdit(UUID project, UUID leaseId, UUID task) {
        return transaction.execute(status -> recoveryFact(abandonedLease(project, leaseId, task)));
    }

    /** Startup-only, behind the caller's exclusive coordinator lease. Never recreates a writable capability. */
    public RecoveryFact reconcileAbandonedEdit(UUID project, UUID leaseId, UUID task) {
        return transaction.execute(status -> {
            var lease = abandonedLease(project, leaseId, task);
            var fact = recoveryFact(lease);
            if (fact.uncertain()) leases.uncertain(lease);
            else {
                leases.release(lease);
                forgetAfterCommit(leaseId);
            }
            return fact;
        });
    }
    private SourceEditLeaseRepository.Lease abandonedLease(UUID project, UUID leaseId, UUID task) {
        repository.lockEvidence(project);
        var lease = leases.get(project, leaseId);
        if (!lease.taskId().equals(task)) throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
        return lease;
    }
    private RecoveryFact recoveryFact(SourceEditLeaseRepository.Lease lease) {
        var operation = leases.operation(lease);
        boolean uncertain = lease.executionPending() || operation != null && (!operation.terminal() || operation.executionPending());
        return new RecoveryFact(uncertain, operation);
    }
    public record RecoveryFact(boolean uncertain, SourceOperation operation) {}

    public void releaseEdit(EditHandle handle) {
        owned(handle);
        transaction.executeWithoutResult(status -> {
            repository.lockEvidence(handle.lease.projectId());
            service.authorize(handle.lease.ownerId(), handle.lease.projectId());
            leases.release(handle.lease);
            forgetAfterCommit(handle.leaseId());
        });
    }

    /** Held-capability cleanup after stop/terminal publication, even if account ownership has since changed. */
    public void releaseSettledEdit(EditHandle handle) {
        owned(handle);
        transaction.executeWithoutResult(status -> {
            repository.lockEvidence(handle.projectId());
            leases.release(handle.lease); // Rejects pending execution and any unfinished source operation.
            forgetAfterCommit(handle.leaseId());
        });
    }

    private void forgetAfterCommit(UUID leaseId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                synchronized (ProjectSourceEditGateway.this) {
                    var handle = issued.remove(leaseId);
                    if (handle == null) return;
                    candidates.release(handle.candidate);
                    workspaces.release(handle.workspace);
                    releaseCapacity(handle.slotReleased);
                }
            }
        });
    }

    private void owned(EditHandle handle) {
        if (handle == null || issued.get(handle.lease.id()) != handle) throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
    }
    private void releaseCapacity(AtomicBoolean released) { if (released.compareAndSet(false, true)) capacity.release(); }
    private SourceDeadline deadline(EditHandle handle) {
        return new SourceDeadline(environment.operationTimeout(), System::nanoTime, handle.budget);
    }
    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Source workspace I/O cannot run in a database transaction");
    }

    public static final class EditHandle {
        private final SourceEditLeaseRepository.Lease lease;
        private final AtomicBoolean slotReleased;
        private volatile SourceWorkspace workspace;
        private volatile Path directory;
        private SourceCandidate candidate;
        private String acceptanceKey;
        private volatile Acceptance accepted;
        private SourceExecutionBudget budget;
        private boolean budgetAssigned;
        private EditHandle(SourceEditLeaseRepository.Lease lease, AtomicBoolean slotReleased) {
            this.lease = lease; this.slotReleased = slotReleased;
        }
        public UUID leaseId() { return lease.id(); }
        public UUID taskId() { return lease.taskId(); }
        public UUID projectId() { return lease.projectId(); }
        @Override public String toString() { return "SourceEditHandle[taskId=" + taskId() + "]"; }
    }
}
