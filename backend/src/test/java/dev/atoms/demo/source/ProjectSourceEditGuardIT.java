package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceEditGuardIT extends SourceIntegrationSupport {
    private UUID version;
    private SourceTree tree;
    private SourceEditLeaseRepository leases;

    @BeforeEach void publishRealBaseline() {
        tree = new ProjectSourcePolicy().validate(List.of(file("README.md", "original")));
        var intent = new SourceInitializer(service).request(owner, project, tree, "guard-baseline");
        var result = new SourceWorker(service, repository, environment).execute(intent, tree);
        assertThat(result.status()).isEqualTo("COMMITTED");
        version = result.resultVersionId(); leases = new SourceEditLeaseRepository(jdbc, repository);
    }

    @Test void ordinaryWritesAreBlockedWhileVersionReadsStayAvailable() {
        claim();
        blocked(() -> service.admit(owner, project, request(), "ordinary-edit"));
        blocked(() -> service.replay(owner, project, request(), "ordinary-edit"));
        blocked(() -> service.writableBase(owner, project, version, 1));
        blocked(() -> service.admit(owner, project, new SourceRequest("RESTORE", version, 1, version, null, null, null, tree.sha256()), "restore"));
        var query = new SourceQuery(service, repository, environment);
        assertThat(query.snapshot(owner, project, version).tree().sha256()).isEqualTo(tree.sha256());
        assertThat(query.versions(owner, project, null, 10)).hasSize(1);
    }

    @Test void alreadyQueuedUnboundOperationCannotBypassLeaseInWorkerOrRecovery() {
        var operation = service.admit(owner, project, request(), "old-queued");
        // Models a pre-existing/older caller: no Agent executor is involved in this fixture.
        jdbc.update("""
                INSERT INTO source_edit_leases(id,project_id,owner_id,task_id,base_version_id,base_revision)
                VALUES (?,?,?,?,?,1)
                """, UUID.randomUUID(), project, owner, UUID.randomUUID(), version);
        blocked(() -> service.prepare(project, operation.id()));
        blocked(() -> service.beginExecution(project, operation.id()));
        blocked(() -> service.ready(project, operation.id()));
        blocked(() -> service.publish(project, operation.id(), null, tree));
        blocked(() -> service.recovered(operation));
        assertThat(service.operation(owner, project, operation.id()).status()).isEqualTo("PREPARING");
        assertThat(repository.state(project).versionId()).isEqualTo(version);
        // Cancellation is deliberately allowed: it stops old work without replacing a source version.
        assertThat(service.cancel(owner, project, operation.id()).status()).isEqualTo("CANCELLED");
    }

    @Test void leaseAdmissionAndOrdinaryAdmissionSerializeOnTheSameProjectRow() throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var edit = pool.submit(() -> { start.await(); try { claim(); return true; } catch (ApiException error) { return false; } });
            var ordinary = pool.submit(() -> { start.await(); try { service.admit(owner, project, request(), "competing"); return true; }
                catch (ApiException error) { return false; } });
            start.countDown();
            assertThat(List.of(edit.get(10, TimeUnit.SECONDS), ordinary.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally { pool.shutdownNow(); }
    }

    @Test void onlyTheStoppedLeaseCanPublishAndAnUnrelatedTaskIdIsNotAPermit() {
        var lease = claim();
        blocked(() -> service.admit(owner, project, request(), "before-stop", lease));
        UUID execution = UUID.randomUUID();
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> leases.beginExecution(lease, execution));
        blocked(() -> service.admit(owner, project, request(), "while-running", lease));
        tx.executeWithoutResult(status -> leases.confirmStopped(lease, execution, "f".repeat(64)));
        var wrong = new SourceEditLeaseRepository.Lease(lease.id(), project, owner, UUID.randomUUID(), version, 1,
                "ACTIVE", execution, false, "f".repeat(64), lease.createdAt());
        blocked(() -> service.admit(owner, project, request(), "wrong-task", wrong));
        var edited = new ProjectSourcePolicy().validate(List.of(file("README.md", "modified")));
        var request = new SourceRequest("ACCEPT", version, 1, null, null, null, null, edited.sha256());
        var operation = service.admit(owner, project, request, "lease-save", lease);
        assertThat(operation.editLeaseId()).isEqualTo(lease.id());
        assertThat(service.replay(owner, project, request, "lease-save", lease).id()).isEqualTo(operation.id());
        var result = new SourceWorker(service, repository, environment).execute(operation, edited);
        assertThat(result.status()).isEqualTo("COMMITTED");
        assertThat(new SourceQuery(service, repository, environment).snapshot(owner, project, result.resultVersionId()).tree().sha256())
                .isEqualTo(edited.sha256());
        tx.executeWithoutResult(status -> leases.release(lease));
        assertThat(leases.active(project)).isNull();
    }

    private SourceEditLeaseRepository.Lease claim() {
        return new TransactionTemplate(transactions).execute(status -> leases.claim(owner, project, version, 1, UUID.randomUUID()));
    }
    private SourceRequest request() { return new SourceRequest("ACCEPT", version, 1, null, null, null, null, "a".repeat(64)); }
    private void blocked(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("SOURCE_EDIT_BUSY"));
    }
}
