package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceEditWorkspaceIT extends SourceIntegrationSupport {
    private UUID version;
    private ProjectSourceEditGateway gateway;

    @BeforeEach void baseline() {
        var tree = new ProjectSourcePolicy().validate(List.of(file("frontend/src/App.vue", "<template>Before</template>")));
        var intent = new SourceInitializer(service).request(owner, project, tree, "edit-workspace-baseline");
        var result = new SourceWorker(service, repository, environment).execute(intent, tree);
        assertThat(result.status()).isEqualTo("COMMITTED"); version = result.resultVersionId();
        gateway = gateway();
    }

    @Test void givesAnOwnedPlainCopyAndReleaseRetainsFilesAndOriginalVersion() throws Exception {
        var handle = gateway.claimEdit(owner, project, version, 1, UUID.randomUUID());
        gateway.prepareEdit(handle); Path copy = gateway.workPath(handle);
        assertThat(copy.resolve(".git")).doesNotExist();
        assertThat(copy.resolve("frontend/src/App.vue")).hasContent("<template>Before</template>");
        Files.writeString(copy.resolve("frontend/src/App.vue"), "<template>After</template>");
        assertThat(new SourceQuery(service, repository, environment).file(owner, project, version, "frontend/src/App.vue").content())
                .isEqualTo("<template>Before</template>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        gateway.releaseEdit(handle);
        assertThat(copy.resolve("frontend/src/App.vue")).hasContent("<template>After</template>");
        assertThatThrownBy(() -> gateway.workPath(handle)).isInstanceOf(ApiException.class);
        assertThat(new SourceEditLeaseRepository(jdbc, repository).active(project)).isNull();
    }

    @Test void rejectsForeignHandlesWrongOwnersStaleBasesAndReplacedDirectories() throws Exception {
        assertThatThrownBy(() -> gateway.claimEdit(stranger, project, version, 1, UUID.randomUUID())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> gateway.claimEdit(owner, project, version, 2, UUID.randomUUID())).isInstanceOf(ApiException.class);
        var handle = gateway.claimEdit(owner, project, version, 1, UUID.randomUUID());
        assertThatThrownBy(() -> gateway().prepareEdit(handle)).isInstanceOf(ApiException.class);
        gateway.prepareEdit(handle); Path copy = gateway.workPath(handle);
        Files.move(copy, copy.resolveSibling("preserved-project")); Files.createDirectory(copy);
        assertThatThrownBy(() -> gateway.workPath(handle)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> gateway.prepareEdit(handle)).isInstanceOf(ApiException.class);
    }

    @Test void rechecksCurrentOwnershipAndDoesNotReleaseAnExecutingTask() {
        var handle = gateway.claimEdit(owner, project, version, 1, UUID.randomUUID());
        gateway.prepareEdit(handle);
        var leases = new SourceEditLeaseRepository(jdbc, repository);
        new TransactionTemplate(transactions).executeWithoutResult(status -> leases.beginExecution(leases.active(project), UUID.randomUUID()));
        assertThatThrownBy(() -> gateway.releaseEdit(handle)).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, project);
        assertThatThrownBy(() -> gateway.workPath(handle)).isInstanceOf(ApiException.class);
        assertThat(leases.active(project).executionPending()).isTrue();
    }

    @Test void outerTransactionRollbackRevokesTheHandleAndIoCannotRunInsideATransaction() {
        var issued = new AtomicReference<ProjectSourceEditGateway.EditHandle>();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            issued.set(gateway.claimEdit(owner, project, version, 1, UUID.randomUUID()));
            assertThatThrownBy(() -> gateway.prepareEdit(issued.get())).isInstanceOf(IllegalStateException.class);
            status.setRollbackOnly();
        });
        assertThat(new SourceEditLeaseRepository(jdbc, repository).active(project)).isNull();
        assertThatThrownBy(() -> gateway.prepareEdit(issued.get())).isInstanceOf(ApiException.class);
    }

    private ProjectSourceEditGateway gateway() { return new ProjectSourceEditGateway(service, repository, environment, transactions); }
}
