package dev.atoms.demo.source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceFileSnapshotTest {
    @TempDir Path temporary;

    @Test void materializesAndReadsSeparateWorkspaceAndStagingCopies() throws Exception {
        try (var environment = environment()) {
            var paths = environment.settings().paths();
            UUID project = UUID.randomUUID();
            paths.createProject(project);
            var store = new SourceFileSnapshotStore(environment);
            var tree = new ProjectSourcePolicy().validate(List.of(file("src/main.txt", "one")));
            var work = store.create(null, UUID.randomUUID(), "work-marker", tree);
            var staged = store.create(project, UUID.randomUUID(), "stage-marker", tree);
            assertThat(store.read(work).sha256()).isEqualTo(tree.sha256());
            assertThat(store.read(staged).sha256()).isEqualTo(tree.sha256());
            Files.writeString(paths.workRoot().resolve(work.id() + "/project/src/main.txt"), "modified");
            assertThat(store.read(work).sha256()).isNotEqualTo(tree.sha256());
            assertThat(store.read(staged).sha256()).isEqualTo(tree.sha256());
            assertThat(paths.root().resolve(project + "/staging/" + staged.id() + "/project/src/main.txt")).isRegularFile();
        }
    }

    @Test void refusesSecretsBeforeCreatingAndRejectsChangedIdentity() throws Exception {
        try (var environment = environment()) {
            var paths = environment.settings().paths();
            var store = new SourceFileSnapshotStore(environment);
            UUID task = UUID.randomUUID();
            var forged = new SourceTree(List.of(file(".env", "PASSWORD=NOT_REAL_CANARY")), "0".repeat(64), 1);
            assertThatThrownBy(() -> store.create(null, task, "marker", forged)).isInstanceOf(IllegalArgumentException.class);
            assertThat(paths.workRoot().resolve(task.toString())).doesNotExist();
            var tree = new ProjectSourcePolicy().validate(List.of(file("README.md", "one")));
            var copy = store.create(null, task, "marker", tree);
            Files.writeString(paths.workRoot().resolve(task + "/.atoms-source-workspace"), "changed");
            assertThatThrownBy(() -> store.read(copy)).isInstanceOf(java.io.IOException.class);
        }
    }

    private ProjectSourceEnvironment environment() throws Exception {
        Path root = temporary.toRealPath();
        return new ProjectSourceEnvironment(new MockEnvironment().withProperty("atoms.source.enabled", "true")
                .withProperty("atoms.source.root", root.resolve("source").toString())
                .withProperty("atoms.source.work-root", root.resolve("work").toString())
                .withProperty("atoms.source.git", "/usr/bin/git")
                .withProperty("atoms.source.python", ProjectSourceFileProcessTest.python().toString()));
    }
}
