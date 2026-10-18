package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceAdmissionIT extends SourceIntegrationSupport {
    @Test void idempotentAdmissionPersistsIntentWithoutTouchingDisk() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = service.admit(owner, project, request("a"), key);
        assertThat(first.status()).isEqualTo("PREPARING");
        assertThat(first.candidateVersionId()).isNotNull();
        assertThat(service.admit(owner, project, request("a"), key)).isEqualTo(first);
        assertThat(repository.operation(project, first.id())).isEqualTo(first);
        assertThatThrownBy(() -> service.admit(owner, project, request("b"), key))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("SOURCE_IDEMPOTENCY_CONFLICT"));
        assertThat(Files.exists(temporary.resolve("source"))).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE project_id=?", Integer.class, project)).isEqualTo(1);
    }

    @Test void checksCurrentOwnerAndArchiveBeforeCreatingIntentOrReturningOldResults() {
        assertThatThrownBy(() -> service.admit(stranger, project, request("a"), "test-key")).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_sources WHERE project_id=?", Integer.class, project)).isZero();
        jdbc.update("UPDATE projects SET archived=true WHERE id=?", project);
        assertThatThrownBy(() -> service.admit(owner, project, request("a"), "test-key")).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE projects SET archived=false WHERE id=?", project);
        var admitted = service.admit(owner, project, request("a"), "test-key");
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, project);
        assertThatThrownBy(() -> service.admit(owner, project, request("a"), "test-key")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.operation(owner, project, admitted.id())).isInstanceOf(ApiException.class);
        assertThat(service.operation(stranger, project, admitted.id()).id()).isEqualTo(admitted.id());
    }

    @Test void concurrentSameKeyCreatesOneDurableIntent() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); return service.admit(owner, project, request("a"), "concurrent-key"); });
            var b = pool.submit(() -> { start.await(); return service.admit(owner, project, request("a"), "concurrent-key"); });
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS).id()).isEqualTo(b.get(10, TimeUnit.SECONDS).id());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE project_id=?", Integer.class, project)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void rejectsStaleBaselineAndBlocksUnreconciledProject() {
        var wrong = new SourceRequest("ACCEPT", UUID.randomUUID(), 1, null, null, null, null, "a".repeat(64));
        assertThatThrownBy(() -> service.admit(owner, project, wrong, "stale"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("SOURCE_BASE_CONFLICT"));
        service.admit(owner, project, request("a"), "first");
        jdbc.update("UPDATE project_sources SET status='NEEDS_RECONCILIATION' WHERE project_id=?", project);
        assertThatThrownBy(() -> service.admit(owner, project, request("b"), "second"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("SOURCE_NEEDS_RECONCILIATION"));
        assertThat(jdbc.queryForObject("SELECT project_revision FROM projects WHERE id=?", Long.class, project)).isZero();
    }

    private SourceRequest request(String character) {
        return new SourceRequest("INITIALIZE", null, 0, null, null, null, null, character.repeat(64));
    }
}
