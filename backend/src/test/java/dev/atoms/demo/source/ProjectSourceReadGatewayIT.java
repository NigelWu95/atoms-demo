package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceReadGatewayIT extends SourceIntegrationSupport {
    @Autowired ProjectSourceReadGateway disabledGateway;

    @Test void returnsTheRequestedFullSnapshotWithoutExposingMutableStorage() {
        var original = tree("first");
        var first = publish(null, 0, original);
        var second = publish(first, 1, tree("second"));
        var snapshot = gateway().read(owner, project, first);
        assertThat(snapshot.version().id()).isEqualTo(first);
        assertThat(snapshot.version().projectId()).isEqualTo(project);
        assertThat(snapshot.tree().sha256()).isEqualTo(original.sha256());
        assertThat(snapshot.tree().totalBytes()).isEqualTo(original.totalBytes());
        assertThat(snapshot.tree().files()).usingRecursiveComparison().isEqualTo(original.files());
        assertThat(snapshot.tree().files()).allSatisfy(value -> assertThat(value.mode()).isEqualTo(0100644));
        var content = snapshot.tree().files().get(0).content();
        byte saved = content[0];
        content[0] = (byte) (saved + 1);
        assertThat(snapshot.tree().files().get(0).content()[0]).isEqualTo(saved);
        assertThatThrownBy(() -> snapshot.tree().files().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(gateway().read(owner, project, second).tree().sha256()).isNotEqualTo(original.sha256());
        assertThat(repository.state(project).versionId()).isEqualTo(second);
    }

    @Test void checksCurrentOwnershipAndBindsTheVersionToItsProject() {
        var version = publish(null, 0, tree("owner"));
        assertCode("PROJECT_NOT_FOUND", () -> gateway().read(stranger, project, version));
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,?)", other, owner, "read-gateway-other");
        assertCode("SOURCE_VERSION_MISSING", () -> gateway().read(owner, other, version));
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, project);
        assertCode("PROJECT_NOT_FOUND", () -> gateway().read(owner, project, version));
        assertThat(gateway().read(stranger, project, version).version().id()).isEqualTo(version);
    }

    @Test void disabledAndMissingReadsNeverInitializeStorage() {
        assertCode("SOURCE_DISABLED", () -> disabledGateway.read(owner, project, UUID.randomUUID()));
        assertCode("SOURCE_VERSION_MISSING", () -> gateway().read(owner, project, UUID.randomUUID()));
        assertThat(repository.state(project)).isNull();
        assertThat(Files.exists(temporary.resolve("source"))).isFalse();
        assertThat(Files.exists(temporary.resolve("work"))).isFalse();
    }

    @Test void refusesGitReadsWhileTheCallerHoldsADatabaseTransaction() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
                gateway().read(owner, project, UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("database transaction");
        assertThat(Files.exists(temporary.resolve("source"))).isFalse();
    }

    @Test void missingPublishedReferenceFencesTheProjectInsteadOfReturningOtherSource() throws Exception {
        var version = publish(null, 0, tree("missing-reference"));
        var reference = environment.settings().paths().root().resolve(project.toString()).resolve("repository.git")
                .resolve("refs/atoms/versions").resolve(version.toString());
        Files.move(reference, reference.resolveSibling("saved-reference"));
        assertCode("SOURCE_STORAGE_UNAVAILABLE", () -> gateway().read(owner, project, version));
        assertThat(repository.state(project).versionId()).isEqualTo(version);
        assertThat(repository.state(project).status()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(Files.exists(reference)).isFalse();
    }

    private ProjectSourceReadGateway gateway() {
        return new ProjectSourceReadGateway(service, repository, environment);
    }

    private UUID publish(UUID base, long revision, SourceTree tree) {
        var operation = service.admit(owner, project, new SourceRequest(base == null ? "INITIALIZE" : "ACCEPT",
                base, revision, null, null, null, null, tree.sha256()), "read-gateway-" + revision);
        var result = new SourceWorker(service, repository, environment).execute(operation, tree);
        assertThat(result.status()).isEqualTo("COMMITTED");
        return result.resultVersionId();
    }

    private static SourceTree tree(String title) {
        return new ProjectSourcePolicy().validate(List.of(file("frontend/src/App.vue", "<template>" + title + "</template>"),
                file("backend/src/Tasks.java", "class Tasks {}"),
                file("backend/db/V001__tasks.sql", "create table tasks(id bigint);"),
                file("frontend/package.json", "{\"name\":\"tasks\",\"private\":true}"),
                file("backend/src/main/resources/application.yml", "spring:\n  application:\n    name: tasks\n")));
    }

    private static void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
