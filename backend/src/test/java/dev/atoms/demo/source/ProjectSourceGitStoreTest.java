package dev.atoms.demo.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceGitStoreTest {
    @TempDir Path temporary;
    private final ProjectSourcePolicy policy = new ProjectSourcePolicy();
    private final SourceGitProcess git = new SourceGitProcess(Path.of("/usr/bin/git"));

    @Test void roundTripsFullTreeAfterClosingAndReopeningStorage() throws Exception {
        Path parent = temporary.toRealPath();
        UUID project = UUID.randomUUID(), version = UUID.randomUUID();
        SourceTree tree = tree("export const score = 1;\n");
        try (var paths = new ProjectSourcePaths(parent.resolve("source"), parent.resolve("work"))) {
            var store = new SourceGitStore(paths, git, policy);
            store.initialize(project);
            var saved = store.save(project, version, null, tree);
            assertThat(saved.commitId()).matches("[a-f0-9]{40}");
            assertThat(saved.treeSha256()).isEqualTo(tree.sha256());
            assertThat(saved.parentCommitId()).isNull();
            assertTree(tree, store.read(project, version));
        }
        try (var paths = new ProjectSourcePaths(parent.resolve("source"), parent.resolve("work"))) {
            assertTree(tree, new SourceGitStore(paths, git, policy).read(project, version));
        }
    }

    @Test void incrementalVersionRetainsUnchangedBlobsAndCannotOverwriteAnExistingVersion() throws Exception {
        Path parent = temporary.toRealPath();
        UUID project = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        try (var paths = new ProjectSourcePaths(parent.resolve("source"), parent.resolve("work"))) {
            var store = new SourceGitStore(paths, git, policy);
            store.initialize(project);
            var one = store.save(project, first, null, tree("export const score = 1;\n"));
            var two = store.save(project, second, first, tree("export const score = 2;\n"));
            assertThat(two.parentCommitId()).isEqualTo(one.commitId());
            assertThat(store.save(project, second, first, tree("export const score = 2;\n"))).isEqualTo(two);
            Path repository = paths.root().resolve(project.toString()).resolve("repository.git");
            assertThat(blob(repository, one.commitId(), "backend/src/Main.java"))
                    .isEqualTo(blob(repository, two.commitId(), "backend/src/Main.java"));
            assertThat(blob(repository, one.commitId(), "frontend/src/game.js"))
                    .isNotEqualTo(blob(repository, two.commitId(), "frontend/src/game.js"));
            assertThatThrownBy(() -> store.save(project, second, first, tree("overwrite")))
                    .isInstanceOf(IOException.class).hasMessage("SOURCE_VERSION_CONFLICT");
            assertThatThrownBy(() -> store.save(project, second, null, tree("export const score = 2;\n")))
                    .isInstanceOf(IOException.class).hasMessage("SOURCE_VERSION_CONFLICT");
            assertTree(tree("export const score = 1;\n"), store.read(project, first));
            assertTree(tree("export const score = 2;\n"), store.read(project, second));
        }
    }

    @Test void rejectsForeignDirectoriesAndMissingVersionsWithoutCreatingAnything() throws Exception {
        Path parent = temporary.toRealPath();
        UUID project = UUID.randomUUID();
        try (var paths = new ProjectSourcePaths(parent.resolve("source"), parent.resolve("work"))) {
            var store = new SourceGitStore(paths, git, policy);
            assertThatThrownBy(() -> store.read(project, UUID.randomUUID())).isInstanceOf(IOException.class);
            assertThat(Files.exists(paths.root().resolve(project.toString()))).isFalse();
            Path foreign = paths.createProject(project);
            Files.writeString(foreign.resolve("keep.txt"), "keep");
            assertThatThrownBy(() -> store.initialize(project)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> store.save(project, UUID.randomUUID(), null, tree("new")))
                    .isInstanceOf(IOException.class);
            assertThat(Files.readString(foreign.resolve("keep.txt"))).isEqualTo("keep");
            assertThat(Files.exists(foreign.resolve("repository.git"))).isFalse();
        }
    }

    @Test void hooksAndAttributesDoNotRunDuringSnapshotWriting() throws Exception {
        Path parent = temporary.toRealPath();
        UUID project = UUID.randomUUID(), version = UUID.randomUUID();
        try (var paths = new ProjectSourcePaths(parent.resolve("source"), parent.resolve("work"))) {
            var store = new SourceGitStore(paths, git, policy);
            store.initialize(project);
            Path repo = paths.root().resolve(project.toString()).resolve("repository.git");
            Files.createDirectories(repo.resolve("hooks"));
            Path canary = parent.resolve("hook-executed");
            Path hook = repo.resolve("hooks/reference-transaction");
            Files.writeString(hook, "#!/bin/sh\ntouch '" + canary + "'\n");
            Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwx------"));
            var files = new ArrayList<>(tree("line\r\n").files());
            files.add(file(".gitattributes", "* filter=untrusted text eol=lf\n"));
            SourceTree input = policy.validate(files);
            store.save(project, version, null, input);
            assertTree(input, store.read(project, version));
            assertThat(Files.exists(canary)).isFalse();
        }
    }

    @Test void rejectsMissingObjectsAndAliasedRepositories() throws Exception {
        Path parent = temporary.toRealPath();
        UUID project = UUID.randomUUID(), version = UUID.randomUUID();
        try (var paths = new ProjectSourcePaths(parent.resolve("source"), parent.resolve("work"))) {
            var store = new SourceGitStore(paths, git, policy);
            store.initialize(project);
            var saved = store.save(project, version, null, tree("test"));
            Path repo = paths.root().resolve(project.toString()).resolve("repository.git");
            String object = blob(repo, saved.commitId(), "frontend/src/game.js");
            Files.move(repo.resolve("objects/" + object.substring(0, 2) + "/" + object.substring(2)), parent.resolve("saved-object"));
            assertThatThrownBy(() -> store.read(project, version)).isInstanceOf(IOException.class);
            Files.move(repo, parent.resolve("other-repository"));
            Files.createSymbolicLink(repo, parent.resolve("other-repository"));
            assertThatThrownBy(() -> store.read(project, version)).isInstanceOf(IOException.class);
        }
    }

    @Test void rejectsForgedTreeMetadataBeforePublishingAReference() throws Exception {
        Path parent = temporary.toRealPath();
        UUID project = UUID.randomUUID(), version = UUID.randomUUID();
        try (var paths = new ProjectSourcePaths(parent.resolve("source"), parent.resolve("work"))) {
            var store = new SourceGitStore(paths, git, policy);
            store.initialize(project);
            var real = tree("valid");
            var forged = new SourceTree(real.files(), "0".repeat(64), real.totalBytes());
            assertThatThrownBy(() -> store.save(project, version, null, forged)).isInstanceOf(IOException.class);
            assertThat(store.reference(project, version)).isNull();
        }
    }

    @Test void rechecksRootIdentityBeforeEveryGitCommandNotOnlyAtTheEnd() throws Exception {
        Path parent = temporary.toRealPath();
        UUID project = UUID.randomUUID();
        try (var paths = new ProjectSourcePaths(parent.resolve("source"), parent.resolve("work"))) {
            new SourceGitStore(paths, git, policy).initialize(project);
            var swapped = new java.util.concurrent.atomic.AtomicBoolean();
            Path replacement = parent.resolve("source").resolve(project.toString()).resolve("repository.git");
            var replacingGit = new SourceGitProcess(Path.of("/usr/bin/git"), java.time.Duration.ofSeconds(60), handle -> {
                if (swapped.compareAndSet(false, true)) {
                    try {
                        Files.move(parent.resolve("source"), parent.resolve("saved-source"));
                        Files.createDirectories(replacement.getParent());
                        git.run(replacement.getParent(), List.of("init", "--bare", "--template=", "repository.git"), new byte[0], 4096);
                    } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                }
            });
            var store = new SourceGitStore(paths, replacingGit, policy);
            assertThatThrownBy(() -> store.save(project, UUID.randomUUID(), null, tree("unchanged"))).isInstanceOf(IOException.class);
            try (var files = Files.walk(replacement.resolve("objects"))) {
                assertThat(files.filter(Files::isRegularFile).count()).isZero();
            }
        }
    }

    private SourceTree tree(String game) {
        return policy.validate(List.of(file("frontend/src/game.js", game), file("frontend/src/App.vue", "<template>游戏</template>\n"),
                file("backend/src/Main.java", "class Main {}\n"), file("backend/pom.xml", "<project/>\n"),
                file("backend/src/db/V001__initial.sql", "create table scores (id bigint);\n"),
                file("README.md", "Full project source\n".repeat(8000)), file(".env.example", "DB_PASSWORD=${DB_PASSWORD}\n"),
                new SourceTree.File("backend/mvnw", "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8), 0100755)));
    }
    private SourceTree.File file(String path, String content) {
        return new SourceTree.File(path, content.getBytes(StandardCharsets.UTF_8), 0100644);
    }
    private String blob(Path repo, String commit, String path) throws IOException {
        return new String(git.run(repo, List.of("rev-parse", commit + ":" + path), new byte[0], 128), StandardCharsets.UTF_8).strip();
    }
    private void assertTree(SourceTree expected, SourceTree actual) {
        assertThat(actual.sha256()).isEqualTo(expected.sha256());
        assertThat(actual.totalBytes()).isEqualTo(expected.totalBytes());
        assertThat(actual.files()).hasSize(expected.files().size());
        for (int i = 0; i < expected.files().size(); i++) {
            assertThat(actual.files().get(i).path()).isEqualTo(expected.files().get(i).path());
            assertThat(actual.files().get(i).mode()).isEqualTo(expected.files().get(i).mode());
            assertThat(actual.files().get(i).content()).isEqualTo(expected.files().get(i).content());
        }
    }
}
