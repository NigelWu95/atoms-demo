package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceRestorationGatewayIT extends SourceIntegrationSupport {
    @Autowired DataSource database;
    private final SourceTree firstTree = new ProjectSourcePolicy().validate(List.of(file("README.md", "first version\n")));
    private final SourceTree secondTree = new ProjectSourcePolicy().validate(List.of(file("README.md", "second version\n")));

    @Test void restoresAsNewVersionReplaysOriginalOperationAndKeepsBothHistoricalTrees() throws Exception {
        try (var coordinator = new SourceCoordinator(service, repository, environment, database)) {
            coordinator.start(); var gateway = gateway(coordinator);
            assertThat(gateway.head(owner, project).sourceVersionId()).isNull();
            UUID first = coordinator.initialize(owner, project, firstTree, "initial").completion().get(30, TimeUnit.SECONDS).resultVersionId();
            var edit = service.admit(owner, project, new SourceRequest("ACCEPT", first, 1, null, null, null, null, secondTree.sha256()), "edit");
            UUID second = coordinator.submit(owner, project, edit.id(), secondTree).completion().get(30, TimeUnit.SECONDS).resultVersionId();
            assertThat(gateway.head(owner, project).revision()).isEqualTo(2);
            var accepted = gateway.restore(owner, project, first, second, 2, "restore");
            var result = await(gateway, accepted.operationId());
            assertThat(result.status()).isEqualTo("COMMITTED");
            assertThat(result.resultSourceVersionId()).isNotIn(first, second);
            assertThat(gateway.restore(owner, project, first, second, 2, "restore")).isEqualTo(result);
            code("SOURCE_IDEMPOTENCY_CONFLICT", () -> gateway.restore(owner, project, second, second, 2, "restore"));
            var restored = repository.version(project, result.resultSourceVersionId());
            assertThat(restored.basedOnId()).isEqualTo(second); assertThat(restored.restoredFromId()).isEqualTo(first);
            var query = new SourceQuery(service, repository, environment);
            assertThat(query.snapshot(owner, project, first).tree().sha256()).isEqualTo(firstTree.sha256());
            assertThat(query.snapshot(owner, project, second).tree().sha256()).isEqualTo(secondTree.sha256());
            assertThat(query.snapshot(owner, project, restored.id()).tree().sha256()).isEqualTo(firstTree.sha256());
            // Explicitly restoring the current version still appends history, never rewrites the old version.
            var same = await(gateway, gateway.restore(owner, project, restored.id(), restored.id(), 3, "same").operationId());
            assertThat(same.resultSourceVersionId()).isNotEqualTo(restored.id());
            assertThat(gateway.head(owner, project).revision()).isEqualTo(4);
            String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(result);
            assertThat(json).doesNotContain("idempotencySha256", "requestSha256", "executionId", "candidateSha256", "ownerId", temporary.toString());
        }
    }

    @Test void rejectsStaleBaseForeignOwnerOtherOperationKindAndUnavailableCoordinator() throws Exception {
        var absent = gateway(null);
        code("SOURCE_COORDINATOR_UNAVAILABLE", () -> absent.restore(owner, project, UUID.randomUUID(), UUID.randomUUID(), 1, "absent"));
        assertThat(repository.state(project)).isNull();
        try (var coordinator = new SourceCoordinator(service, repository, environment, database)) {
            coordinator.start(); var gateway = gateway(coordinator);
            var initialized = coordinator.initialize(owner, project, firstTree, "initial").completion().get(30, TimeUnit.SECONDS);
            UUID first = initialized.resultVersionId();
            code("SOURCE_BASE_CONFLICT", () -> gateway.restore(owner, project, first, first, 2, "stale"));
            code("PROJECT_NOT_FOUND", () -> gateway.head(stranger, project));
            code("PROJECT_NOT_FOUND", () -> gateway.restore(stranger, project, first, first, 1, "foreign"));
            code("SOURCE_OPERATION_MISSING", () -> gateway.operation(owner, project, initialized.id()));
            assertThat(repository.state(project).revision()).isEqualTo(1);
            assertThat(repository.versions(project, null, 100)).hasSize(1);
        }
    }

    @Test void uncertainAsynchronousRestoreRemainsQueryableAndDoesNotAdvanceSourceHead() throws Exception {
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        try (var coordinator = new SourceCoordinator(service, repository, environment, database, stage -> {
            if (fail.get() && stage == SourceWorker.Stage.AFTER_GIT) throw new IllegalStateException("controlled failure");
        })) {
            coordinator.start(); var gateway = gateway(coordinator);
            UUID first = coordinator.initialize(owner, project, firstTree, "initial").completion().get(30, TimeUnit.SECONDS).resultVersionId();
            fail.set(true);
            var accepted = gateway.restore(owner, project, first, first, 1, "failed-restore");
            var result = await(gateway, accepted.operationId());
            assertThat(result.status()).isEqualTo("NEEDS_RECONCILIATION");
            assertThat(result.errorCode()).isEqualTo("SOURCE_EXECUTION_UNCERTAIN");
            assertThat(result.resultSourceVersionId()).isNull();
            assertThat(gateway.head(owner, project).sourceVersionId()).isEqualTo(first);
            assertThat(gateway.restore(owner, project, first, first, 1, "failed-restore")).isEqualTo(result);
        }
    }

    private ProjectSourceRestorationGateway gateway(SourceCoordinator coordinator) {
        return new ProjectSourceRestorationGateway(service, repository, environment, (Supplier<SourceCoordinator>) () -> coordinator);
    }
    private ProjectSourceRestorationGateway.Operation await(ProjectSourceRestorationGateway gateway, UUID id) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (;;) {
            var result = gateway.operation(owner, project, id);
            if (!Set.of("PREPARING", "CANDIDATE_READY").contains(result.status())) return result;
            if (System.nanoTime() >= until) throw new AssertionError("Restore did not settle");
            Thread.sleep(20);
        }
    }
    private void code(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
