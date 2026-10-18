package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceWorkspaceIT extends SourceIntegrationSupport {
    @Test void configuredPythonCanPerformDescriptorRelativeWorkspaceAccess() throws Exception {
        assertThat(environment.python()).isAbsolute().isRegularFile();
        // The other tests exercise the real helper; this platform need not implement SecureDirectoryStream.
        assertThat(new SourceFileProcess(environment.python()).builder().command()).contains("-I", "-S");
    }
    @Test void materializesAnOrdinaryPrivateCopyWithoutExposingTheCanonicalRepository() throws Exception {
        var tree = new ProjectSourcePolicy().validate(List.of(file("frontend/src/App.vue", "<template>One</template>"),
                file("backend/src/Task.java", "class Task {}"), file("backend/db/V001.sql", "create table task(id bigint);"),
                new SourceTree.File("backend/mvnw", "#!/bin/sh\nexit 0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0100755)));
        var version = publish(project, tree);
        var store = store();
        UUID task = UUID.randomUUID();
        var workspace = store.prepare(owner, project, version, 1, task);
        Path copy = store.checked(workspace);
        assertThat(workspace.projectId()).isEqualTo(project);
        assertThat(workspace.taskId()).isEqualTo(task);
        assertThat(workspace.versionId()).isEqualTo(version);
        assertThat(workspace.revision()).isEqualTo(1);
        assertThat(copy).isEqualTo(temporary.toRealPath().resolve("work/" + task + "/project"));
        try (var paths = Files.walk(copy)) {
            assertThat(paths.filter(Files::isRegularFile).map(copy::relativize).map(Path::toString).toList())
                    .containsExactlyInAnyOrderElementsOf(tree.files().stream().map(SourceTree.File::path).toList());
        }
        for (var file : tree.files()) assertThat(Files.readAllBytes(copy.resolve(file.path()))).isEqualTo(file.content());
        assertThat(Files.isExecutable(copy.resolve("backend/mvnw"))).isTrue();
        assertThat(Files.isExecutable(copy.resolve("backend/src/Task.java"))).isFalse();
        Files.writeString(copy.resolve("frontend/src/App.vue"), "changed by the future agent");
        Files.delete(copy.resolve("backend/db/V001.sql"));
        assertThat(query().snapshot(owner, project, version).tree().sha256()).isEqualTo(tree.sha256());
        assertThatThrownBy(() -> store.prepare(owner, project, version, 1, task)).isInstanceOf(ApiException.class);
        assertThat(Files.readString(copy.resolve("frontend/src/App.vue"))).isEqualTo("changed by the future agent");
    }

    @Test void rejectsForeignOwnersProjectsStaleBasesAndUnownedWorkspaceHandles() throws Exception {
        var tree = new ProjectSourcePolicy().validate(List.of(file("README.md", "private project")));
        var version = publish(project, tree);
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,?)", other, owner, "other-workspace");
        var otherVersion = publish(other, tree);
        var store = store();
        for (var denied : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> store.prepare(stranger, project, version, 1, UUID.randomUUID()),
                () -> store.prepare(owner, other, version, 1, UUID.randomUUID()),
                () -> store.prepare(owner, project, version, 2, UUID.randomUUID()))) {
            assertThatThrownBy(denied).isInstanceOf(ApiException.class);
        }
        var one = store.prepare(owner, project, version, 1, UUID.randomUUID());
        var two = store.prepare(owner, other, otherVersion, 1, UUID.randomUUID());
        assertThat(store.checked(one)).isNotEqualTo(store.checked(two));
        assertThatThrownBy(() -> store().checked(one)).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, project);
        assertThatThrownBy(() -> store.checked(one)).isInstanceOf(ApiException.class);
        assertThat(store.checked(two)).isDirectory();
    }

    @Test void refusesAnExistingTaskDirectoryAndAReplacedCopyWithoutAdoptingEither() throws Exception {
        var version = publish(project, new ProjectSourcePolicy().validate(List.of(file("README.md", "one"))));
        var store = store();
        UUID task = UUID.randomUUID();
        Path existing = environment.settings().paths().createWorkspace(task);
        Files.writeString(existing.resolve("user-file"), "do not touch");
        assertThatThrownBy(() -> store.prepare(owner, project, version, 1, task)).isInstanceOf(ApiException.class);
        assertThat(Files.readString(existing.resolve("user-file"))).isEqualTo("do not touch");
        var workspace = store.prepare(owner, project, version, 1, UUID.randomUUID());
        Path original = store.checked(workspace);
        Files.move(original, original.resolveSibling("original-project"));
        Files.createSymbolicLink(original, existing);
        assertThatThrownBy(() -> store.checked(workspace)).isInstanceOf(ApiException.class);
        assertThat(Files.readString(existing.resolve("user-file"))).isEqualTo("do not touch");
    }

    private SourceQuery query() { return new SourceQuery(service, repository, environment); }
    private SourceWorkspaceStore store() { return new SourceWorkspaceStore(service, query(), environment); }
    private UUID publish(UUID target, SourceTree tree) {
        var operation = new SourceInitializer(service).request(owner, target, tree, "workspace-initial");
        var result = new SourceWorker(service, repository, environment).execute(operation, tree);
        assertThat(result.status()).isEqualTo("COMMITTED");
        return result.resultVersionId();
    }
}
