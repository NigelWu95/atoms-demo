package dev.atoms.demo.source;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
    private final TransactionTemplate transaction;
    private final ProjectSourceEnvironment environment;
    private final Map<UUID, EditHandle> issued = new HashMap<>();

    ProjectSourceEditGateway(ProjectSourceService service, SourceRepository repository, ProjectSourceEnvironment environment,
                            PlatformTransactionManager transactions) {
        this.service = service; this.repository = repository; this.leases = repository.edits();
        this.environment = environment;
        this.workspaces = new SourceWorkspaceStore(service, new SourceQuery(service, repository, environment), environment);
        this.transaction = new TransactionTemplate(transactions);
    }

    public synchronized EditHandle claimEdit(UUID owner, UUID project, UUID base, long revision, UUID task) {
        java.util.Objects.requireNonNull(task);
        if (issued.size() >= 64) throw ProjectSourceService.conflict("SOURCE_CAPACITY_EXCEEDED");
        return transaction.execute(status -> {
            service.writableBase(owner, project, base, revision);
            var lease = leases.claim(owner, project, base, revision, task);
            var handle = new EditHandle(lease);
            issued.put(lease.id(), handle);
            // Agent admission can join this transaction; a failed task insert must revoke its handle too.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int completion) {
                    if (completion != STATUS_COMMITTED) synchronized (ProjectSourceEditGateway.this) {
                        issued.remove(lease.id(), handle);
                    }
                }
            });
            return handle;
        });
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
    }

    /** For the trusted process adapter only, not an HTTP DTO or a path editable by the model. */
    public synchronized Path workPath(EditHandle handle) {
        requireOutsideTransaction(); owned(handle);
        if (handle.workspace == null) throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
        return workspaces.checked(handle.workspace, deadline(handle));
    }

    public synchronized void releaseEdit(EditHandle handle) {
        owned(handle);
        transaction.executeWithoutResult(status -> {
            repository.lockEvidence(handle.lease.projectId());
            service.authorize(handle.lease.ownerId(), handle.lease.projectId());
            leases.release(handle.lease);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    synchronized (ProjectSourceEditGateway.this) {
                        workspaces.release(handle.workspace);
                        issued.remove(handle.lease.id(), handle);
                    }
                }
            });
        });
    }

    private void owned(EditHandle handle) {
        if (handle == null || issued.get(handle.lease.id()) != handle) throw ProjectSourceService.conflict("SOURCE_EDIT_UNAVAILABLE");
    }
    private SourceDeadline deadline(EditHandle handle) {
        return new SourceDeadline(environment.operationTimeout(), System::nanoTime, handle.budget);
    }
    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Source workspace I/O cannot run in a database transaction");
    }

    public static final class EditHandle {
        private final SourceEditLeaseRepository.Lease lease;
        private SourceWorkspace workspace;
        private SourceExecutionBudget budget;
        private boolean budgetAssigned;
        private EditHandle(SourceEditLeaseRepository.Lease lease) { this.lease = lease; }
        public UUID leaseId() { return lease.id(); }
        public UUID taskId() { return lease.taskId(); }
        public UUID projectId() { return lease.projectId(); }
        @Override public String toString() { return "SourceEditHandle[taskId=" + taskId() + "]"; }
    }
}
