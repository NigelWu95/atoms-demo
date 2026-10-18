package dev.atoms.demo.source;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceInitializationIT extends SourceIntegrationSupport {
    @Test void publishesAFullTreeOnlyAfterGitAndReturnsTheSameCommittedRetry() throws Exception {
        SourceTree tree = tree();
        var initializer = new SourceInitializer(service);
        var operation = initializer.request(owner, project, tree, "initial-tree");
        assertThat(repository.state(project).versionId()).isNull();
        var worker = new SourceWorker(service, repository, environment, stage -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (stage == SourceWorker.Stage.AFTER_GIT) assertThat(repository.state(project).versionId()).isNull();
        });
        var completed = worker.execute(operation, tree);
        assertThat(completed.status()).isEqualTo("COMMITTED");
        assertThat(completed.executionId()).isNotNull();
        assertThat(completed.executionPending()).isFalse();
        var version = repository.version(project, completed.resultVersionId());
        assertThat(version.versionNo()).isEqualTo(1);
        assertThat(version.treeSha256()).isEqualTo(tree.sha256());
        assertThat(version.treeBytes()).isGreaterThan(120 * 1024);
        assertThat(repository.state(project).versionId()).isEqualTo(version.id());
        assertThat(repository.state(project).revision()).isEqualTo(1);
        var settings = environment.settings();
        var stored = new SourceGitStore(settings.paths(), new SourceGitProcess(settings.git()), new ProjectSourcePolicy());
        assertThat(stored.read(project, version.id()).sha256()).isEqualTo(tree.sha256());
        assertThat(initializer.request(owner, project, tree, "initial-tree").id()).isEqualTo(operation.id());
        assertThat(worker.execute(operation, tree)).isEqualTo(completed);
        assertThatThrownBy(() -> initializer.request(owner, project, tree, "different-key"))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class);
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM projects WHERE id=?", java.util.UUID.class, project)).isNull();
    }

    @Test void ownerChangeAfterGitRefusesPublicationAndRetainsTheCandidateForAudit() throws Exception {
        SourceTree tree = tree();
        var operation = new SourceInitializer(service).request(owner, project, tree, "ownership-change");
        var worker = new SourceWorker(service, repository, environment, stage -> {
            if (stage == SourceWorker.Stage.AFTER_GIT) jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, project);
        });
        var outcome = worker.execute(operation, tree);
        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(repository.state(project).versionId()).isNull();
        assertThat(repository.version(project, operation.candidateVersionId())).isNull();
        var settings = environment.settings();
        assertThat(new SourceGitStore(settings.paths(), new SourceGitProcess(settings.git()), new ProjectSourcePolicy())
                .reference(project, operation.candidateVersionId())).isNotNull();
    }

    @Test void changedInputOrArchiveCannotPublishAnInitialVersion() throws Exception {
        var operation = new SourceInitializer(service).request(owner, project, tree(), "frozen-input");
        var changed = new ProjectSourcePolicy().validate(List.of(file("README.md", "changed")));
        var worker = new SourceWorker(service, repository, environment);
        assertThat(worker.execute(operation, changed).status()).isEqualTo("FAILED");
        assertThat(java.nio.file.Files.exists(temporary.resolve("source"))).isFalse();
        var other = new SourceInitializer(service).request(owner, project, tree(), "archived-input");
        jdbc.update("UPDATE projects SET archived=true WHERE id=?", project);
        assertThat(worker.execute(other, tree()).status()).isEqualTo("FAILED");
        assertThat(repository.state(project).versionId()).isNull();
    }

    static SourceTree tree() {
        return new ProjectSourcePolicy().validate(List.of(file("frontend/src/App.vue", "<template>Tasks</template>\n"),
                file("backend/src/Tasks.java", "class Tasks {}\n"), file("backend/db/V001__tasks.sql", "create table tasks(id bigint);\n"),
                file("README.md", "Complete source\n".repeat(9000))));
    }
    static SourceTree.File file(String path, String value) {
        return new SourceTree.File(path, value.getBytes(StandardCharsets.UTF_8), 0100644);
    }
}
