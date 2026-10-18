package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceCancellationIT extends SourceIntegrationSupport {
    private SourceTree tree() { return new ProjectSourcePolicy().validate(List.of(file("README.md", "cancel fixture"))); }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE_GIT", "GIT_STARTED", "AFTER_GIT", "BEFORE_PUBLISH", "AFTER_COMMIT"})
    void cancellationReflectsWhetherPublicationAlreadyCommitted(String window) {
        var input = tree();
        var operation = new SourceInitializer(service).request(owner, project, input, "cancel-window");
        var observed = new AtomicBoolean();
        var result = new SourceWorker(service, repository, environment, stage -> {
            if (stage.name().equals(window) && observed.compareAndSet(false, true)) {
                var cancelled = service.cancel(owner, project, operation.id());
                if (window.equals("GIT_STARTED")) {
                    assertThat(cancelled.cancelRequested()).isTrue();
                    assertThat(cancelled.executionPending()).isTrue();
                    assertThat(cancelled.terminal()).isFalse();
                }
            }
        }).execute(operation, input);
        assertThat(observed).isTrue();
        boolean published = window.equals("AFTER_COMMIT");
        assertThat(result.status()).isEqualTo(published ? "COMMITTED" : "CANCELLED");
        assertThat(result.executionPending()).isFalse();
        assertThat(repository.state(project).revision()).isEqualTo(published ? 1 : 0);
        assertThat(repository.versions(project, null, 100)).hasSize(published ? 1 : 0);
        assertThat(service.cancel(owner, project, operation.id())).isEqualTo(result);
    }

    @Test void queuedCancellationRequiresCurrentOwnershipButRemainsAvailableWhenArchived() {
        var input = tree();
        var operation = new SourceInitializer(service).request(owner, project, input, "queued");
        assertThatThrownBy(() -> service.cancel(stranger, project, operation.id())).isInstanceOf(ApiException.class);
        assertThat(repository.operation(project, operation.id()).cancelRequested()).isFalse();
        jdbc.update("UPDATE projects SET archived=true WHERE id=?", project);
        var result = service.cancel(owner, project, operation.id());
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(new SourceWorker(service, repository, environment).execute(operation, input)).isEqualTo(result);
    }

    @Test void unconfirmedExecutionRemainsFencedAcrossRecoveryAfterCancellation() {
        var input = tree();
        var operation = new SourceInitializer(service).request(owner, project, input, "unsettled");
        var executing = service.beginExecution(project, operation.id());
        try {
            assertThat(service.cancel(owner, project, operation.id()).executionPending()).isTrue();
            var result = new SourceRecovery(service, repository, environment).reconcile(project, operation.id());
            assertThat(result.status()).isEqualTo("NEEDS_RECONCILIATION");
            assertThat(result.cancelRequested()).isTrue();
            assertThat(result.executionPending()).isTrue();
            assertThatThrownBy(() -> new SourceInitializer(service).request(owner, project, input, "retry"))
                    .isInstanceOf(ApiException.class);
        } finally {
            // Only this synthetic test execution was marked pending; no process was launched.
            service.executionSettled(project, operation.id(), executing.executionId());
            service.recovered(repository.operation(project, operation.id()));
        }
    }
}
