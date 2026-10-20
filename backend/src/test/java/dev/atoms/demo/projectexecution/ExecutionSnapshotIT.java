package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.source.enabled=false", "atoms.project-execution.enabled=false",
        "atoms.generation.worker-enabled=false"})
class ExecutionSnapshotIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
    SourceAgentFixture source;
    ExecutionPaths paths;
    ExecutionSnapshot snapshots;
    SourceAgentFixture.Base first;

    @BeforeEach void setup() throws Exception {
        Path root = temporary.toRealPath();
        source = new SourceAgentFixture(jdbc, projects, transactions, root);
        jdbc.update("INSERT INTO guest_users(id) VALUES (?),(?)", owner, stranger);
        first = source.create(owner);
        paths = new ExecutionPaths(root.resolve("execution"), List.of(root.resolve("source"), root.resolve("work")));
        snapshots = new ExecutionSnapshot(source.readGateway(), paths);
    }
    @AfterEach void close() throws Exception {
        try { if (paths != null) paths.close(); } finally { if (source != null) source.close(); }
    }

    @Test void explicitVersionAndAllFilesStayFixedAfterLaterPublication() throws Exception {
        var original = source.snapshot(owner, first.projectId(), first.versionId());
        var changed = new ProjectSourcePolicy().validate(List.of(
                new SourceTree.File("frontend/App.vue", "<template>two</template>".getBytes(), 0100644),
                new SourceTree.File("backend/App.java", "class App { int version = 2; }".getBytes(), 0100644),
                new SourceTree.File("backend/db/V001.sql", "create table tasks(id bigint);".getBytes(), 0100644),
                new SourceTree.File("backend/mvnw", "#!/bin/sh\nexit 0\n".getBytes(), 0100755),
                new SourceTree.File("backend/src/main/resources/application.yml", "spring:\n  application:\n    name: tasks\n".getBytes(), 0100644)));
        var second = source.accept(owner, first, changed);
        var task = paths.createBuild(UUID.randomUUID());
        var copy = snapshots.materialize(owner, first.projectId(), first.versionId(), task, budget());
        assertThat(copy.sourceVersion().id()).isEqualTo(first.versionId()).isNotEqualTo(second.versionId());
        assertThat(copy.tree().sha256()).isEqualTo(original.sha256());
        for (var file : original.files()) {
            Path materialized = paths.project(task).resolve(file.path());
            assertThat(Files.readAllBytes(materialized)).isEqualTo(file.content());
            assertThat(Files.getPosixFilePermissions(materialized)).isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
        }
        snapshots.verify(copy);
        assertThat(paths.project(task).resolve(".git")).doesNotExist();
        assertThat(paths.project(task).resolve("backend/src/main/resources/application.yml")).doesNotExist();
        var secondTask = paths.createBuild(UUID.randomUUID());
        var newer = snapshots.materialize(owner, first.projectId(), second.versionId(), secondTask, budget());
        assertThat(newer.tree().sha256()).isEqualTo(changed.sha256());
        assertThat(Files.readString(paths.project(secondTask).resolve("backend/src/main/resources/application.yml"))).contains("name: tasks");
        assertThat(Files.getPosixFilePermissions(paths.project(secondTask).resolve("backend/mvnw")))
                .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test void copiesAreExclusiveAndDoNotOverwriteExistingContentOrForeignHandles() throws Exception {
        var task = paths.createBuild(UUID.randomUUID());
        Files.writeString(paths.project(task).resolve("evidence"), "retained");
        assertThatThrownBy(() -> snapshots.materialize(owner, first.projectId(), first.versionId(), task, budget()))
                .isInstanceOf(IOException.class);
        assertThat(Files.readString(paths.project(task).resolve("evidence"))).isEqualTo("retained");
        var clean = paths.createBuild(UUID.randomUUID());
        var copy = snapshots.materialize(owner, first.projectId(), first.versionId(), clean, budget());
        assertThatThrownBy(() -> snapshots.materialize(owner, first.projectId(), first.versionId(), clean, budget()))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new ExecutionSnapshot(source.readGateway(), paths).verify(copy))
                .isInstanceOf(IOException.class);
    }

    @Test void changedBytesModesHardlinksSymlinksAndUnexpectedFilesFailRevalidation() throws Exception {
        for (String kind : List.of("bytes", "mode", "hardlink", "symlink", "extra", "large")) {
            var task = paths.createBuild(UUID.randomUUID());
            var copy = snapshots.materialize(owner, first.projectId(), first.versionId(), task, budget());
            Path directory = paths.project(task), target = directory.resolve("README.md");
            switch (kind) {
                case "bytes" -> Files.writeString(target, "changed");
                case "mode" -> Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
                case "hardlink" -> Files.createLink(temporary.resolve("alias"), target);
                case "symlink" -> {
                    Path moved = temporary.resolve("saved-source");
                    Files.move(target, moved); Files.createSymbolicLink(target, moved);
                }
                case "extra" -> Files.writeString(directory.resolve("untracked.txt"), "unexpected");
                case "large" -> Files.write(target, new byte[(2 << 20) + 1]);
                default -> throw new AssertionError();
            }
            assertThatThrownBy(() -> snapshots.verify(copy)).as(kind).isInstanceOf(IOException.class)
                    .hasMessage("EXECUTION_SOURCE_CHANGED");
            assertThat(source.file(owner, first.projectId(), first.versionId(), "README.md")).isEqualTo("unchanged".getBytes());
        }
    }

    @Test void onlyRecognizedOutputDirectoriesAreIgnoredAndLinksAtTheirRootsAreRejected() throws Exception {
        var task = paths.createBuild(UUID.randomUUID());
        var copy = snapshots.materialize(owner, first.projectId(), first.versionId(), task, budget());
        Path directory = paths.project(task);
        Files.createDirectory(directory.resolve("frontend/node_modules"));
        Files.writeString(directory.resolve("frontend/node_modules/compiled-output"), "not-source");
        Files.createDirectory(directory.resolve("backend/target"));
        Files.writeString(directory.resolve("backend/target/classes"), "not-source");
        snapshots.verify(copy);
        Files.createSymbolicLink(directory.resolve("frontend/dist"), temporary);
        assertThatThrownBy(() -> snapshots.verify(copy)).hasMessage("EXECUTION_SOURCE_CHANGED");
    }

    @Test void ownerChangeAndTransactionDoNotAllowMaterializationOrFurtherUse() throws Exception {
        var deniedTask = paths.createBuild(UUID.randomUUID());
        assertThatThrownBy(() -> snapshots.materialize(stranger, first.projectId(), first.versionId(), deniedTask, budget()))
                .isInstanceOf(ApiException.class);
        try (var entries = Files.list(paths.project(deniedTask))) { assertThat(entries.count()).isZero(); }
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            try { return snapshots.materialize(owner, first.projectId(), first.versionId(), deniedTask, budget()); }
            catch (IOException error) { throw new IllegalStateException(error); }
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        var copy = snapshots.materialize(owner, first.projectId(), first.versionId(), deniedTask, budget());
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, first.projectId());
        assertThatThrownBy(() -> snapshots.verify(copy)).isInstanceOf(ApiException.class);
    }

    @Test void sharedDeadlineStillAppliesToCopyAndSubsequentVerification() throws Exception {
        var expired = new AtomicBoolean();
        var task = paths.createBuild(UUID.randomUUID());
        var copy = snapshots.materialize(owner, first.projectId(), first.versionId(), task,
                () -> expired.get() ? Duration.ZERO : Duration.ofSeconds(60));
        expired.set(true);
        assertThatThrownBy(() -> snapshots.verify(copy)).hasMessage("EXECUTION_TIMEOUT");
        var denied = paths.createBuild(UUID.randomUUID());
        assertThatThrownBy(() -> snapshots.materialize(owner, first.projectId(), first.versionId(), denied, () -> Duration.ZERO))
                .hasMessage("EXECUTION_TIMEOUT");
        try (var entries = Files.list(paths.project(denied))) { assertThat(entries.count()).isZero(); }
    }

    @Test void timeoutDuringCopyRetainsPartialEvidenceAndNeverPublishesIt() throws Exception {
        var task = paths.createBuild(UUID.randomUUID());
        Path root = paths.project(task);
        assertThatThrownBy(() -> snapshots.materialize(owner, first.projectId(), first.versionId(), task,
                () -> Files.exists(root.resolve("README.md")) ? Duration.ZERO : Duration.ofSeconds(60)))
                .hasMessage("EXECUTION_TIMEOUT");
        assertThat(root.resolve("README.md")).exists();
        assertThat(root.resolve("frontend/App.vue")).doesNotExist();
        assertThat(source.file(owner, first.projectId(), first.versionId(), "README.md")).isEqualTo("unchanged".getBytes());
    }

    private SourceExecutionBudget budget() { return () -> Duration.ofSeconds(60); }
}
