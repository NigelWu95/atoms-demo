package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceEditDeadlineIT extends SourceIntegrationSupport {
    private UUID version;
    private SourceQuery query;

    @BeforeEach void baseline() {
        var tree = new ProjectSourcePolicy().validate(List.of(file("README.md", "unchanged")));
        var intent = new SourceInitializer(service).request(owner, project, tree, "deadline-baseline");
        var result = new SourceWorker(service, repository, environment).execute(intent, tree);
        assertThat(result.status()).isEqualTo("COMMITTED"); version = result.resultVersionId();
        query = new SourceQuery(service, repository, environment);
    }

    @Test void expiredTaskCannotPrepareOrFenceAnOtherwiseHealthySource() {
        var gateway = new ProjectSourceEditGateway(service, repository, environment, transactions);
        var task = UUID.randomUUID(); var handle = gateway.claimEdit(owner, project, version, 1, task);
        assertThatThrownBy(() -> gateway.prepareEdit(handle, () -> Duration.ZERO)).isInstanceOf(ApiException.class);
        assertThat(repository.state(project).status()).isEqualTo("READY");
        assertThat(temporary.resolve("work").resolve(task.toString())).doesNotExist();
        assertThat(query.file(owner, project, version, "README.md").content()).isEqualTo("unchanged".getBytes());
        gateway.releaseEdit(handle);
    }

    @Test void checkingAPreparedHandleCannotResetTheTaskBudget() {
        var gateway = new ProjectSourceEditGateway(service, repository, environment, transactions);
        var handle = gateway.claimEdit(owner, project, version, 1, UUID.randomUUID());
        var expired = new AtomicBoolean();
        SourceExecutionBudget budget = () -> expired.get() ? Duration.ZERO : Duration.ofSeconds(60);
        gateway.prepareEdit(handle, budget);
        assertThat(gateway.workPath(handle)).isDirectory();
        expired.set(true);
        assertThatThrownBy(() -> gateway.workPath(handle)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> gateway.prepareEdit(handle, () -> Duration.ofSeconds(60))).isInstanceOf(ApiException.class);
        gateway.releaseEdit(handle);
    }

    @Test void sealingAndRereadingCandidatesBothConsumeTheProvidedTaskBudget() {
        var store = new SourceWorkspaceStore(service, query, environment);
        var expired = new AtomicBoolean();
        SourceExecutionBudget budget = () -> expired.get() ? Duration.ZERO : Duration.ofSeconds(60);
        var deadline = new SourceDeadline(Duration.ofSeconds(300), System::nanoTime, budget);
        var workspace = store.prepare(owner, project, version, 1, UUID.randomUUID(), null, deadline);
        var candidates = new SourceCandidateStore(store, environment);
        expired.set(true);
        assertThatThrownBy(() -> candidates.seal(workspace, deadline)).isInstanceOf(ApiException.class);
        expired.set(false);
        var candidate = candidates.seal(workspace, deadline);
        expired.set(true);
        assertThatThrownBy(() -> candidates.read(owner, project, candidate, deadline)).isInstanceOf(ApiException.class);
        assertThat(repository.state(project).versionId()).isEqualTo(version);
        assertThat(repository.state(project).status()).isEqualTo("READY");
    }
}
