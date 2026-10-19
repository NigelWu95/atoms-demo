package dev.atoms.demo.agent;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ProjectAgentBoundaryIT extends NativeJourneySupport {
    @ParameterizedTest
    @ValueSource(strings = {"source-secret", "source-symlink", "source-hardlink", "source-oversize"})
    void successfulEditorCannotPublishAnInvalidSourceCandidate(String mode) {
        var base = source.create(owner);
        var before = source.snapshot(owner, base.projectId(), base.versionId());
        var editor = new AgentFixtureAdapter(temporary, mode);
        var task = tasks.submit(owner, base.projectId(), input("synthetic boundary check", base.versionId(), 1), mode);
        var worker = worker(editor);
        var result = worker.execute(task.id());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).startsWith("SOURCE_");
        assertThat(result.resultVersionId()).isNull();
        assertThat(worker.execute(task.id())).isEqualTo(result);
        assertThat(editor.calls).hasValue(1);
        assertStopped(task.id());
        assertCurrent(base.projectId(), base.versionId(), 1);
        assertThat(source.snapshot(owner, base.projectId(), base.versionId()).sha256()).isEqualTo(before.sha256());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM projects WHERE id=?", UUID.class, base.projectId())).isNull();
        assertThat(editor.work.get(task.id())).isDirectory();
    }
}
