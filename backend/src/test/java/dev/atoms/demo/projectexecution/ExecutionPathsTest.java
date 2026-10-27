package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExecutionPathsTest {
    @TempDir Path temporary;

    @Test void maintenanceReopensExistingAreasWithoutCreatingOrRepairingThem() throws Exception {
        Path root = temporary.toRealPath().resolve("existing");
        assertThatThrownBy(() -> ExecutionPaths.existing(root, List.of())).isInstanceOf(IOException.class);
        assertThat(Files.exists(root)).isFalse();
        try (var paths = new ExecutionPaths(root, List.of())) { paths.createRun(UUID.randomUUID()); }
        try (var paths = ExecutionPaths.existing(root, List.of())) { assertThat(paths.checkedRoot()).isEqualTo(root); }
        Files.move(root.resolve("runs"), root.resolve("retained-runs"));
        assertThatThrownBy(() -> ExecutionPaths.existing(root, List.of())).isInstanceOf(IOException.class);
        assertThat(Files.exists(root.resolve("runs"))).isFalse();
        assertThat(Files.isDirectory(root.resolve("retained-runs"))).isTrue();
    }

    @Test void distinctPrivateAreasAndTaskCapabilitiesSurviveNeitherReplacementNorForeignIssuer() throws Exception {
        Path parent = temporary.toRealPath(), root = parent.resolve("execution");
        try (var paths = new ExecutionPaths(root, List.of());
             var foreign = new ExecutionPaths(parent.resolve("foreign"), List.of())) {
            UUID id = UUID.randomUUID();
            var build = paths.createBuild(id);
            var artifact = paths.createArtifact(id);
            var run = paths.createRun(id);
            assertThat(paths.directory(build)).isEqualTo(root.resolve("builds").resolve(id.toString()));
            assertThat(paths.project(build)).isEqualTo(paths.directory(build).resolve("project"));
            assertThat(paths.directory(artifact)).isEqualTo(root.resolve("artifacts").resolve(id.toString()));
            assertThat(paths.directory(run)).isEqualTo(root.resolve("runs").resolve(id.toString()));
            assertThatThrownBy(() -> paths.project(run)).isInstanceOf(IOException.class);
            for (Path directory : List.of(root.resolve("builds"), paths.directory(build), paths.project(build),
                    paths.directory(artifact), paths.directory(run)))
                assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThatThrownBy(() -> paths.createBuild(id)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> foreign.directory(build)).isInstanceOf(IOException.class);
            Path project = paths.project(build);
            Files.move(project, project.resolveSibling("saved-project"));
            Files.createDirectory(project, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            assertThatThrownBy(() -> paths.project(build)).isInstanceOf(IOException.class);
            paths.directory(artifact);
        }
        try (var reopened = new ExecutionPaths(root, List.of())) { reopened.checkCapacity(); }
    }

    @Test void onlyMatchingTaskEvidenceAndUnchangedAreaIdentityMayBeUsed() throws Exception {
        Path root = temporary.toRealPath().resolve("execution");
        try (var paths = new ExecutionPaths(root, List.of())) {
            var run = paths.createRun(UUID.randomUUID());
            Files.writeString(paths.directory(run).resolve(".atoms-execution-task"), "forged");
            assertThatThrownBy(() -> paths.directory(run)).isInstanceOf(IOException.class);
            Files.move(root.resolve("builds"), root.resolve("saved-builds"));
            Files.createSymbolicLink(root.resolve("builds"), root.resolve("runs"));
            assertThatThrownBy(() -> paths.createBuild(UUID.randomUUID())).isInstanceOf(IOException.class);
        }
    }

    @Test void capacityFailuresNeverDeleteEvidenceOrCreateTheRejectedTask() throws Exception {
        Path root = temporary.toRealPath().resolve("execution");
        try (var paths = new ExecutionPaths(root, List.of(), 1024, 0)) {
            var run = paths.createRun(UUID.randomUUID());
            Path evidence = Files.write(paths.directory(run).resolve("evidence"), new byte[2048]);
            UUID rejected = UUID.randomUUID();
            assertThatThrownBy(() -> paths.createBuild(rejected)).isInstanceOf(IOException.class)
                    .hasMessage("EXECUTION_STORAGE_CAPACITY");
            assertThat(Files.exists(root.resolve("builds").resolve(rejected.toString()))).isFalse();
            assertThat(Files.size(evidence)).isEqualTo(2048);
        }
        try (var paths = new ExecutionPaths(root, List.of(), ExecutionSettings.ROOT_MAX_BYTES, Long.MAX_VALUE)) {
            assertThatThrownBy(paths::checkCapacity).isInstanceOf(IOException.class).hasMessage("EXECUTION_STORAGE_CAPACITY");
        }
        assertThat(Files.exists(root.resolve("runs"))).isTrue();
    }

    @Test void scansDoNotFollowPackageLinksAndObserveExactLogicalBoundaries() throws Exception {
        Path parent = temporary.toRealPath(), root = parent.resolve("execution");
        try (var paths = new ExecutionPaths(root, List.of(), 1024, 0)) {
            var build = paths.createBuild(UUID.randomUUID());
            Path outside = Files.write(parent.resolve("external"), new byte[2048]);
            Files.createSymbolicLink(paths.project(build).resolve("package-link"), outside);
            paths.checkCapacity();
            assertThat(Files.size(outside)).isEqualTo(2048);
        }
        ExecutionPaths.requireCapacity(8L << 30, 1L << 30, 8L << 30, 1L << 30);
        assertThatThrownBy(() -> ExecutionPaths.requireCapacity((8L << 30) + 1, 1L << 30, 8L << 30, 1L << 30))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ExecutionPaths.requireCapacity(0, (1L << 30) - 1, 8L << 30, 1L << 30))
                .isInstanceOf(IOException.class);
    }

    @Test void closingRevokesHandlesAndIncompleteOrUnrecognizedLayoutsAreNotAdopted() throws Exception {
        Path parent = temporary.toRealPath(), root = parent.resolve("execution");
        var paths = new ExecutionPaths(root, List.of());
        var build = paths.createBuild(UUID.randomUUID());
        paths.close();
        assertThatThrownBy(() -> paths.directory(build)).isInstanceOf(IOException.class);
        Files.move(root.resolve("runs"), root.resolve("saved-runs"));
        assertThatThrownBy(() -> new ExecutionPaths(root, List.of())).isInstanceOf(IOException.class);
        assertThat(Files.exists(root.resolve("runs"))).isFalse();
        assertThat(Files.exists(root.resolve("saved-runs"))).isTrue();
    }

    @Test void storageScanAlsoConsumesTheCallersRemainingDeadline() throws Exception {
        Path root = temporary.toRealPath().resolve("execution");
        try (var paths = new ExecutionPaths(root, List.of())) {
            var task = paths.createBuild(UUID.randomUUID());
            Files.writeString(paths.project(task).resolve("evidence"), "retained");
            var steps = new java.util.concurrent.atomic.AtomicInteger(2);
            assertThatThrownBy(() -> paths.checkCapacity(() -> steps.getAndDecrement() > 0
                    ? java.time.Duration.ofSeconds(5) : java.time.Duration.ZERO))
                    .hasMessage("EXECUTION_TIMEOUT");
            assertThat(Files.readString(paths.project(task).resolve("evidence"))).isEqualTo("retained");
        }
    }

    @Test void buildAllocationUsesSharedBudgetAndRetainsEvidenceIfItExpiresAfterAllocation() throws Exception {
        Path root = temporary.toRealPath().resolve("execution");
        try (var paths = new ExecutionPaths(root, List.of())) {
            UUID expired = UUID.randomUUID();
            assertThatThrownBy(() -> paths.createBuild(expired, () -> java.time.Duration.ZERO))
                    .hasMessage("EXECUTION_TIMEOUT");
            assertThat(root.resolve("builds").resolve(expired.toString())).doesNotExist();
            UUID scanning = UUID.randomUUID();
            var steps = new java.util.concurrent.atomic.AtomicInteger(2);
            assertThatThrownBy(() -> paths.createBuild(scanning, () -> steps.getAndDecrement() > 0
                    ? java.time.Duration.ofSeconds(5) : java.time.Duration.ZERO)).hasMessage("EXECUTION_TIMEOUT");
            assertThat(root.resolve("builds").resolve(scanning.toString())).doesNotExist();
            UUID allocated = UUID.randomUUID();
            Path marker = root.resolve("builds").resolve(allocated.toString()).resolve(".atoms-execution-task");
            assertThatThrownBy(() -> paths.createBuild(allocated, () -> Files.exists(marker)
                    ? java.time.Duration.ZERO : java.time.Duration.ofSeconds(5))).hasMessage("EXECUTION_TIMEOUT");
            assertThat(marker).isRegularFile(); // Partial allocation is evidence, not a reusable empty slot.
            assertThatThrownBy(() -> paths.createBuild(allocated)).isInstanceOf(IOException.class);
            UUID successful = UUID.randomUUID();
            var task = paths.createBuild(successful, () -> java.time.Duration.ofSeconds(5));
            assertThat(paths.directory(task)).isEqualTo(root.resolve("builds").resolve(successful.toString()));
        }
    }

    @Test void checkedRootRequiresLiveLeaseAndUnchangedAreaIdentity() throws Exception {
        Path root = temporary.toRealPath().resolve("execution");
        var paths = new ExecutionPaths(root, List.of());
        try {
            assertThat(paths.checkedRoot()).isEqualTo(root);
            Files.move(root.resolve("builds"), temporary.resolve("original-builds"));
            Files.createDirectory(root.resolve("builds"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            assertThatThrownBy(paths::checkedRoot).hasMessage("EXECUTION_STORAGE_UNAVAILABLE");
        } finally { paths.close(); }
        assertThatThrownBy(paths::checkedRoot).hasMessage("EXECUTION_STORAGE_UNAVAILABLE");
    }
}
