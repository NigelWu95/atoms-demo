package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectSourceCandidateTest {
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), version = UUID.randomUUID();

    @Test void sealedCandidateDoesNotChangeWithItsOriginalWorkspace() throws Exception {
        try (var fixture = fixture()) {
            var workspace = fixture.workspaces.prepare(owner, project, version, 1, UUID.randomUUID());
            Path copy = fixture.workspaces.checked(workspace);
            Files.writeString(copy.resolve("README.md"), "updated");
            var candidate = fixture.candidates.seal(workspace);
            var tree = fixture.candidates.read(owner, project, candidate);
            assertThat(tree.files().get(0).content()).isEqualTo("updated".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.writeString(copy.resolve("README.md"), "changed again");
            assertThat(fixture.candidates.read(owner, project, candidate).sha256()).isEqualTo(tree.sha256());
            Path staged = fixture.environment.settings().paths().root().resolve(project + "/staging/" + candidate.id() + "/project/README.md");
            Files.writeString(staged, "tampered");
            assertThatThrownBy(() -> fixture.candidates.read(owner, project, candidate)).isInstanceOf(ApiException.class);
        }
    }

    @Test void refusesSecretFilesBeforeWritingStagingAndRejectsUnissuedHandles() throws Exception {
        try (var fixture = fixture()) {
            var workspace = fixture.workspaces.prepare(owner, project, version, 1, UUID.randomUUID());
            Path copy = fixture.workspaces.checked(workspace);
            Files.writeString(copy.resolve(".env"), "API_KEY=NOT_REAL_CANARY");
            assertThatThrownBy(() -> fixture.candidates.seal(workspace)).isInstanceOf(ApiException.class);
            assertThat(fixture.environment.settings().paths().root().resolve(project + "/staging")).doesNotExist();
            Files.delete(copy.resolve(".env"));
            var candidate = fixture.candidates.seal(workspace);
            var other = new SourceCandidateStore(fixture.workspaces, fixture.environment);
            assertThatThrownBy(() -> other.read(owner, project, candidate)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> fixture.candidates.read(UUID.randomUUID(), project, candidate)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> fixture.candidates.read(owner, UUID.randomUUID(), candidate)).isInstanceOf(ApiException.class);
        }
    }

    private Fixture fixture() throws Exception {
        Path root = temporary.toRealPath();
        var environment = new ProjectSourceEnvironment(new MockEnvironment().withProperty("atoms.source.enabled", "true")
                .withProperty("atoms.source.root", root.resolve("source").toString())
                .withProperty("atoms.source.work-root", root.resolve("work").toString())
                .withProperty("atoms.source.git", "/usr/bin/git")
                .withProperty("atoms.source.python", ProjectSourceFileProcessTest.python().toString()));
        environment.settings().paths().createProject(project);
        var service = mock(ProjectSourceService.class);
        var query = mock(SourceQuery.class);
        var initial = new ProjectSourcePolicy().validate(List.of(file("README.md", "initial")));
        when(query.snapshot(eq(owner), eq(project), eq(version), any(SourceDeadline.class)))
                .thenReturn(new SourceQuery.Snapshot(null, initial));
        var workspaces = new SourceWorkspaceStore(service, query, environment);
        return new Fixture(environment, workspaces, new SourceCandidateStore(workspaces, environment));
    }

    private record Fixture(ProjectSourceEnvironment environment, SourceWorkspaceStore workspaces,
            SourceCandidateStore candidates) implements AutoCloseable {
        @Override public void close() throws Exception { environment.close(); }
    }
}
