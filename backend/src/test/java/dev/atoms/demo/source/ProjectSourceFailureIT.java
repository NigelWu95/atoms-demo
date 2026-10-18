package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceFailureIT extends SourceIntegrationSupport {
    private SourceTree tree(String text) { return new ProjectSourcePolicy().validate(List.of(file("README.md", text))); }
    private SourceOperation initialize() {
        var tree = tree("initial");
        return new SourceWorker(service, repository, environment).execute(new SourceInitializer(service).request(owner, project, tree, "initial"), tree);
    }

    @Test void tinyBudgetFailsBeforeGitAndDoesNotPublishAnEmptyProject() throws Exception {
        sourceConfiguration.withProperty("atoms.source.max-bytes", "16");
        var result = initialize();
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("SOURCE_STORAGE_CAPACITY");
        assertThat(repository.state(project).versionId()).isNull();
        assertThat(environment.settings().paths().root().resolve(project.toString())).doesNotExist();
    }

    @Test void insufficientSpacePreventsWorkspaceCreationWithoutChangingThePublishedVersion() throws Exception {
        var initial = initialize();
        sourceConfiguration.withProperty("atoms.source.reserve-bytes", Long.toString(Long.MAX_VALUE));
        var workspaces = new SourceWorkspaceStore(service, new SourceQuery(service, repository, environment), environment);
        UUID task = UUID.randomUUID();
        assertThatThrownBy(() -> workspaces.prepare(owner, project, initial.resultVersionId(), 1, task)).isInstanceOf(ApiException.class);
        assertThat(environment.settings().paths().workRoot().resolve(task.toString())).doesNotExist();
        assertThat(repository.state(project).versionId()).isEqualTo(initial.resultVersionId());
    }

    @Test void genuineGitWriteFailureKeepsThePreviousVersionAndRetainsEvidence() throws Exception {
        var initial = initialize();
        var input = tree("new blob that cannot be written");
        var operation = service.admit(owner, project, new SourceRequest("ACCEPT", initial.resultVersionId(), 1,
                null, null, null, null, input.sha256()), "write-failure");
        var objects = environment.settings().paths().root().resolve(project + "/repository.git/objects");
        var permissions = Files.getPosixFilePermissions(objects);
        try {
            Files.setPosixFilePermissions(objects, PosixFilePermissions.fromString("r-x------"));
            assertThat(Files.isWritable(objects)).isFalse();
            var result = new SourceWorker(service, repository, environment).execute(operation, input);
            assertThat(result.status()).isEqualTo("NEEDS_RECONCILIATION");
            assertThat(result.executionPending()).isFalse();
            assertThat(repository.state(project).versionId()).isEqualTo(initial.resultVersionId());
            assertThat(objects).isDirectory();
        } finally { Files.setPosixFilePermissions(objects, permissions); }
        assertThat(new SourceQuery(service, repository, environment).snapshot(owner, project, initial.resultVersionId()).tree().sha256())
                .isEqualTo(tree("initial").sha256());
    }

    @Test void totalDeadlineExpiresBeforeLaunchingGitAndDoesNotLeaveAnExecutionFence() {
        sourceConfiguration.withProperty("atoms.source.operation-timeout-seconds", "1");
        var input = tree("timeout");
        var operation = new SourceInitializer(service).request(owner, project, input, "deadline");
        var started = new AtomicBoolean();
        var result = new SourceWorker(service, repository, environment, stage -> {
            if (stage == SourceWorker.Stage.BEFORE_GIT) {
                try { Thread.sleep(1100); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }
            if (stage == SourceWorker.Stage.GIT_STARTED) started.set(true);
        }).execute(operation, input);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("SOURCE_OPERATION_TIMEOUT");
        assertThat(result.executionPending()).isFalse();
        assertThat(started).isFalse();
        assertThat(repository.state(project).revision()).isZero();
    }
}
