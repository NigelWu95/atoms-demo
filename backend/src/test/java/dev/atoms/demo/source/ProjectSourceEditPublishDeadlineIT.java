package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceEditPublishDeadlineIT extends SourceIntegrationSupport {
    @Autowired DataSource database;

    private SourceDeadline deadline(AtomicBoolean expired) {
        return new SourceDeadline(Duration.ofMinutes(5), System::nanoTime,
                () -> expired.get() ? Duration.ZERO : Duration.ofMinutes(1));
    }

    @Test void queueWaitConsumesTheOriginalBudgetAndDoesNotStartGit() throws Exception {
        var entered = new CountDownLatch(1); var unblock = new CountDownLatch(1);
        var coordinator = new SourceCoordinator(service, repository, environment, database, stage -> {
            if (stage == SourceWorker.Stage.BEFORE_GIT) {
                entered.countDown();
                try { if (!unblock.await(15, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            }
        });
        try {
            coordinator.start();
            var tree = ProjectSourceInitializationIT.tree();
            var first = coordinator.initialize(owner, project, tree, "first");
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var intent = new SourceInitializer(service).request(owner, project, tree, "queued");
            var expired = new AtomicBoolean();
            var queued = coordinator.submit(owner, project, intent.id(), tree, deadline(expired));
            expired.set(true); unblock.countDown();
            assertThat(first.completion().get(30, TimeUnit.SECONDS).status()).isEqualTo("COMMITTED");
            var result = queued.completion().get(30, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.errorCode()).isEqualTo("SOURCE_OPERATION_TIMEOUT");
            assertThat(result.executionId()).isNull();
            assertThat(repository.state(project).revision()).isEqualTo(1);
        } finally { unblock.countDown(); coordinator.close(); }
    }

    @Test void expiryBeforePublishPreservesPointerButExpiryAfterCommitPreservesFact() {
        var tree = ProjectSourceInitializationIT.tree(); var expired = new AtomicBoolean();
        var intent = new SourceInitializer(service).request(owner, project, tree, "before");
        var result = new SourceWorker(service, repository, environment, stage -> {
            if (stage == SourceWorker.Stage.BEFORE_PUBLISH) expired.set(true);
        }).execute(intent, tree, deadline(expired));
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("SOURCE_OPERATION_TIMEOUT");
        assertThat(result.executionPending()).isFalse();
        assertThat(repository.state(project).versionId()).isNull();
        expired.set(false);
        var next = new SourceInitializer(service).request(owner, project, tree, "after");
        var saved = new SourceWorker(service, repository, environment, stage -> {
            if (stage == SourceWorker.Stage.AFTER_COMMIT) expired.set(true);
        }).execute(next, tree, deadline(expired));
        assertThat(saved.status()).isEqualTo("COMMITTED");
        assertThat(new SourceWorker(service, repository, environment).execute(next, tree, deadline(expired)))
                .isEqualTo(saved);
        assertThat(repository.state(project).versionId()).isEqualTo(saved.resultVersionId());
    }

    @Test void expiredAcceptanceDoesNotAdmitAnOperation() {
        var tree = ProjectSourceInitializationIT.tree();
        var saved = new SourceWorker(service, repository, environment).execute(
                new SourceInitializer(service).request(owner, project, tree, "baseline"), tree);
        var workspaces = new SourceWorkspaceStore(service, new SourceQuery(service, repository, environment), environment);
        var candidates = new SourceCandidateStore(workspaces, environment);
        var candidate = candidates.seal(workspaces.prepare(owner, project, saved.resultVersionId(), 1, UUID.randomUUID()));
        assertThatThrownBy(() -> new SourceAcceptance(service, candidates).prepare(owner, project, candidate,
                "expired", null, deadline(new AtomicBoolean(true))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("SOURCE_OPERATION_TIMEOUT"));
        assertThat(repository.findByKey(project, "expired")).isNull();
    }
}
