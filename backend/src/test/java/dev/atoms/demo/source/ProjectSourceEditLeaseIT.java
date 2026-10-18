package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceEditLeaseIT extends SourceIntegrationSupport {
    private final UUID version = UUID.randomUUID();
    private SourceEditLeaseRepository leases;

    @BeforeEach void seedMetadataAndRepository() {
        repository.ensureSource(project);
        jdbc.update("""
                INSERT INTO source_versions(id,project_id,version_no,commit_id,tree_sha256,file_count,tree_bytes)
                VALUES (?,?,1,?,?,1,1)
                """, version, project, "a".repeat(40), "b".repeat(64));
        jdbc.update("UPDATE project_sources SET current_source_version_id=?,source_revision=1 WHERE project_id=?", version, project);
        leases = new SourceEditLeaseRepository(jdbc, repository);
    }

    @Test void persistentLeaseRequiresCurrentOwnerAndBaselineAndSurvivesFeatureRollback() {
        assertThatThrownBy(() -> claim(stranger, version, 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> claim(owner, version, 2)).isInstanceOf(ApiException.class);
        var lease = claim(owner, version, 1);
        assertThat(new SourceEditLeaseRepository(jdbc, repository).active(project)).isEqualTo(lease);
        assertThatThrownBy(() -> claim(owner, version, 1)).isInstanceOf(ApiException.class);
        sourceConfiguration.setProperty("atoms.source.enabled", "false");
        assertThatThrownBy(() -> service.writableBase(owner, project, version, 1)).isInstanceOf(ApiException.class);
        assertThat(leases.active(project).id()).isEqualTo(lease.id());
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM projects WHERE id=?", UUID.class, project)).isNull();
    }

    @Test void concurrentClaimHasExactlyOneWinnerAcrossConnections() throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var futures = List.of(pool.submit(() -> { start.await(); return tryClaim(); }),
                    pool.submit(() -> { start.await(); return tryClaim(); }));
            start.countDown();
            assertThat(List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_edit_leases WHERE project_id=?", Integer.class, project)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void databaseRejectsFalseBaselineAndAlreadyStoppedNewLeases() {
        refused(() -> jdbc.update("""
                INSERT INTO source_edit_leases(id,project_id,owner_id,task_id,base_version_id,base_revision)
                VALUES (?,?,?,?,?,2)
                """, UUID.randomUUID(), project, owner, UUID.randomUUID(), version));
        refused(() -> jdbc.update("""
                INSERT INTO source_edit_leases(id,project_id,owner_id,task_id,base_version_id,base_revision,execution_id,stopped_sha256)
                VALUES (?,?,?,?,?,1,?,?)
                """, UUID.randomUUID(), project, owner, UUID.randomUUID(), version, UUID.randomUUID(), "a".repeat(64)));
    }

    @Test void executionIdentityAndStopEvidenceCannotBeReplacedOrCleared() {
        var lease = claim(owner, version, 1); UUID execution = UUID.randomUUID();
        tx(() -> leases.beginExecution(lease, execution));
        for (String assignment : List.of("task_id='" + UUID.randomUUID() + "'", "execution_id='" + UUID.randomUUID() + "'",
                "execution_pending=false", "status='RELEASED',released_at=now()"))
            refused(() -> jdbc.update("UPDATE source_edit_leases SET " + assignment + " WHERE id=?", lease.id()));
        assertThatThrownBy(() -> tx(() -> leases.release(lease))).isInstanceOf(ApiException.class);
        tx(() -> leases.confirmStopped(lease, execution, "c".repeat(64)));
        refused(() -> jdbc.update("UPDATE source_edit_leases SET stopped_sha256=? WHERE id=?", "d".repeat(64), lease.id()));
        refused(() -> jdbc.update("UPDATE source_edit_leases SET execution_pending=true,stopped_sha256=NULL WHERE id=?", lease.id()));
        tx(() -> leases.release(lease));
        assertThat(leases.active(project)).isNull();
        refused(() -> jdbc.update("UPDATE source_edit_leases SET status='ACTIVE',released_at=NULL WHERE id=?", lease.id()));
        refused(() -> jdbc.update("DELETE FROM source_edit_leases WHERE id=?", lease.id()));
    }

    @Test void pendingSourceOperationBlocksClaimAndCannotBeAttachedToUnstoppedLease() {
        var request = new SourceRequest("ACCEPT", version, 1, null, null, null, null, "a".repeat(64));
        var operation = service.admit(owner, project, request, "before-edit");
        assertThatThrownBy(() -> claim(owner, version, 1)).isInstanceOf(ApiException.class);
        service.cancel(owner, project, operation.id());
        var lease = claim(owner, version, 1);
        refused(() -> jdbc.update("""
                INSERT INTO source_operations(id,project_id,owner_id,kind,idempotency_sha256,request_sha256,
                    expected_version_id,expected_revision,candidate_version_id,candidate_sha256,edit_lease_id)
                VALUES (?,?,?,'ACCEPT',?,?,?,1,?,?,?)
                """, UUID.randomUUID(), project, owner, "c".repeat(64), "d".repeat(64), version,
                UUID.randomUUID(), "e".repeat(64), lease.id()));
    }

    @Test void linkedPendingPublicationBlocksReleaseUntilItSettles() {
        var lease = claim(owner, version, 1); UUID execution = UUID.randomUUID(), operation = UUID.randomUUID();
        tx(() -> leases.beginExecution(lease, execution));
        tx(() -> leases.confirmStopped(lease, execution, "f".repeat(64)));
        jdbc.update("""
                INSERT INTO source_operations(id,project_id,owner_id,kind,idempotency_sha256,request_sha256,
                    expected_version_id,expected_revision,candidate_version_id,candidate_sha256,edit_lease_id)
                VALUES (?,?,?,'ACCEPT',?,?,?,1,?,?,?)
                """, operation, project, owner, "1".repeat(64), "2".repeat(64), version, UUID.randomUUID(), "3".repeat(64), lease.id());
        assertThatThrownBy(() -> tx(() -> leases.release(lease))).isInstanceOf(ApiException.class);
        refused(() -> jdbc.update("UPDATE source_operations SET edit_lease_id=NULL WHERE id=?", operation));
        service.cancel(owner, project, operation);
        tx(() -> leases.release(lease));
        assertThat(leases.active(project)).isNull();
    }

    private boolean tryClaim() { try { claim(owner, version, 1); return true; } catch (ApiException error) { return false; } }
    private SourceEditLeaseRepository.Lease claim(UUID actor, UUID base, long revision) {
        return new TransactionTemplate(transactions).execute(status -> leases.claim(actor, project, base, revision, UUID.randomUUID()));
    }
    private void tx(Runnable action) { new TransactionTemplate(transactions).executeWithoutResult(status -> action.run()); }
    private void refused(Runnable action) { assertThatThrownBy(() -> tx(action)).isInstanceOf(DataAccessException.class); }
}
