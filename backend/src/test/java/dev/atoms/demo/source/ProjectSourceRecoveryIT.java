package dev.atoms.demo.source;

import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceRecoveryIT extends SourceIntegrationSupport {
    @ParameterizedTest @EnumSource(SourceWorker.Stage.class)
    void reconstructsPublicationFactsAfterEachCrashWindow(SourceWorker.Stage crash) throws Exception {
        var tree = ProjectSourceInitializationIT.tree();
        var intent = new SourceInitializer(service).request(owner, project, tree, "crash-window");
        var worker = new SourceWorker(service, repository, environment, stage -> { if (stage == crash) throw new Crash(); });
        assertThatThrownBy(() -> worker.execute(intent, tree)).isInstanceOf(Crash.class);
        reopenEnvironment();
        var recovered = new SourceRecovery(service, repository, environment).reconcile(project, intent.id());
        if (crash == SourceWorker.Stage.AFTER_COMMIT) {
            assertThat(recovered.status()).isEqualTo("COMMITTED");
            assertThat(repository.state(project).versionId()).isEqualTo(intent.candidateVersionId());
            assertThat(new SourceInitializer(service).request(owner, project, tree, "crash-window").id()).isEqualTo(intent.id());
        } else if (crash == SourceWorker.Stage.GIT_STARTED) {
            try {
                // A fatal Error escaped before the worker persisted its exit acknowledgement.
                assertThat(recovered.status()).isEqualTo("NEEDS_RECONCILIATION");
                assertThat(recovered.executionPending()).isTrue();
                assertThat(repository.state(project).versionId()).isNull();
                assertThat(repository.version(project, intent.candidateVersionId())).isNull();
                assertThatThrownBy(() -> new SourceInitializer(service).request(owner, project, tree, "blocked-retry"))
                        .isInstanceOf(dev.atoms.demo.common.ApiException.class);
            } finally {
                // This in-process test observed Crash after GitProcess.finally confirmed child exit.
                // Production restart cannot infer that evidence and has no automatic bypass.
                jdbc.update("UPDATE source_operations SET execution_pending=false WHERE id=?", intent.id());
            }
        } else {
            assertThat(recovered.status()).isEqualTo("FAILED");
            assertThat(recovered.errorCode()).isEqualTo("SOURCE_INTERRUPTED");
            assertThat(repository.state(project).versionId()).isNull();
            assertThat(repository.version(project, intent.candidateVersionId())).isNull();
            if (crash == SourceWorker.Stage.AFTER_GIT) assertThat(store().reference(project, intent.candidateVersionId())).isNotNull();
            var retry = new SourceInitializer(service).request(owner, project, tree, "new-explicit-request");
            assertThat(new SourceWorker(service, repository, environment).execute(retry, tree).status()).isEqualTo("COMMITTED");
        }
    }

    @Test void unknownPartialDirectoryRemainsFencedAcrossReopens() throws Exception {
        var operation = new SourceInitializer(service).request(owner, project, ProjectSourceInitializationIT.tree(), "partial-directory");
        Files.createDirectory(environment.settings().paths().root().resolve(project.toString()));
        for (int i = 0; i < 2; i++) {
            reopenEnvironment();
            var recovered = new SourceRecovery(service, repository, environment).reconcile(project, operation.id());
            assertThat(recovered.status()).isEqualTo("NEEDS_RECONCILIATION");
            assertThat(repository.state(project).status()).isEqualTo("NEEDS_RECONCILIATION");
            assertThatThrownBy(() -> new SourceInitializer(service).request(owner, project, ProjectSourceInitializationIT.tree(), UUID.randomUUID().toString()))
                    .isInstanceOf(dev.atoms.demo.common.ApiException.class);
        }
    }

    @Test void missingPublishedRepositoryBlocksWritesWithoutChangingTheCommittedRecord() throws Exception {
        var tree = ProjectSourceInitializationIT.tree();
        var operation = new SourceInitializer(service).request(owner, project, tree, "published");
        new SourceWorker(service, repository, environment).execute(operation, tree);
        Files.move(environment.settings().paths().root().resolve(project.toString()), temporary.toRealPath().resolve("saved-project"));
        reopenEnvironment();
        assertThatThrownBy(() -> new SourceRecovery(service, repository, environment).reconcile(project, operation.id()))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class);
        assertThat(repository.state(project).status()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(repository.state(project).versionId()).isEqualTo(operation.candidateVersionId());
        assertThat(repository.operation(project, operation.id()).status()).isEqualTo("COMMITTED");
    }

    @Test void anUnsettledExecutionCannotBeDeclaredStoppedFromFilesystemEvidence() throws Exception {
        var operation = new SourceInitializer(service).request(owner, project, ProjectSourceInitializationIT.tree(), "unsettled");
        // Simulated durable evidence only: this test never starts a child for this execution id.
        jdbc.update("UPDATE source_operations SET execution_id=?,execution_pending=true WHERE id=?", UUID.randomUUID(), operation.id());
        try {
            reopenEnvironment();
            var result = new SourceRecovery(service, repository, environment).reconcile(project, operation.id());
            assertThat(result.status()).isEqualTo("NEEDS_RECONCILIATION");
            assertThat(result.executionPending()).isTrue();
            assertThat(repository.state(project).versionId()).isNull();
            assertThat(repository.state(project).status()).isEqualTo("NEEDS_RECONCILIATION");
        } finally {
            // Clear only the synthetic test fence; production recovery has no such bypass.
            jdbc.update("UPDATE source_operations SET execution_pending=false WHERE id=?", operation.id());
        }
    }

    private SourceGitStore store() {
        var settings = environment.settings();
        return new SourceGitStore(settings.paths(), new SourceGitProcess(settings.git()), new ProjectSourcePolicy());
    }
    private void reopenEnvironment() throws Exception {
        environment.close();
        var parent = temporary.toRealPath();
        environment = new ProjectSourceEnvironment(new MockEnvironment().withProperty("atoms.source.enabled", "true")
                .withProperty("atoms.source.root", parent.resolve("source").toString()).withProperty("atoms.source.work-root", parent.resolve("work").toString())
                .withProperty("atoms.source.git", "/usr/bin/git"));
        service = new ProjectSourceService(projects, repository, transactions, environment);
    }
    private static final class Crash extends Error {}
}
