package dev.atoms.demo.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class AgentStateDirectoryTest {
    @TempDir Path temporary;

    @Test void createsPrivateDistinctTaskDirectoriesAndReopensOnlyAMarkedRoot() throws Exception {
        Path root = temporary.toRealPath().resolve("state");
        try (var state = new AgentStateDirectory(root, List.of())) {
            var first = state.create(UUID.randomUUID());
            var second = state.create(UUID.randomUUID());
            assertThat(first.config()).isNotEqualTo(second.config());
            for (Path directory : List.of(root, first.config().getParent(), first.config(), first.temporary()))
                assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(root.resolve(".atoms-agent-root")))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThatThrownBy(() -> new AgentStateDirectory(root, List.of())).isInstanceOf(IOException.class);
            state.verify(first);
        }
        try (var reopened = new AgentStateDirectory(root, List.of())) { reopened.checkCapacity(); }
    }

    @Test void rejectsUnmarkedBroadOverlappingAndLinkedRootsBeforeCreatingAnything() throws Exception {
        Path parent = temporary.toRealPath(), foreign = Files.createDirectory(parent.resolve("foreign"));
        Files.writeString(foreign.resolve("keep.txt"), "keep");
        for (Path root : List.of(foreign, Path.of("/"), Path.of(System.getProperty("user.home"))))
            assertThatThrownBy(() -> new AgentStateDirectory(root, List.of())).isInstanceOf(IOException.class);
        assertThat(Files.readString(foreign.resolve("keep.txt"))).isEqualTo("keep");
        Path reserved = parent.resolve("reserved");
        assertThatThrownBy(() -> new AgentStateDirectory(reserved, List.of(reserved.resolve("source"))))
                .isInstanceOf(IOException.class);
        assertThat(Files.exists(reserved)).isFalse();
        Files.createSymbolicLink(parent.resolve("alias"), foreign);
        assertThatThrownBy(() -> new AgentStateDirectory(parent.resolve("alias/state"), List.of()))
                .isInstanceOf(IOException.class);
    }

    @Test void detectsReplacedTaskDirectoriesAndRejectsForeignHandles() throws Exception {
        Path parent = temporary.toRealPath();
        try (var first = new AgentStateDirectory(parent.resolve("first"), List.of());
             var second = new AgentStateDirectory(parent.resolve("second"), List.of())) {
            var task = first.create(UUID.randomUUID());
            assertThatThrownBy(() -> second.verify(task)).isInstanceOf(IOException.class);
            Files.move(task.config(), task.config().resolveSibling("saved-config"));
            Files.createDirectory(task.config(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            assertThatThrownBy(() -> first.verify(task)).isInstanceOf(IOException.class);
        }
    }

    @Test void refusesCapacityExhaustionWithoutDeletingEvidence() throws Exception {
        Path root = temporary.toRealPath().resolve("state");
        try (var state = new AgentStateDirectory(root, List.of(), 128, 0)) {
            Path evidence = root.resolve("evidence.txt");
            Files.writeString(evidence, "x".repeat(256));
            assertThatThrownBy(state::checkCapacity).isInstanceOf(IOException.class).hasMessage("AGENT_STATE_CAPACITY");
            assertThat(Files.size(evidence)).isEqualTo(256);
        }
    }

    @Test void refusesDuplicateTasksAndAlteredMarkers() throws Exception {
        Path root = temporary.toRealPath().resolve("state");
        try (var state = new AgentStateDirectory(root, List.of())) {
            UUID id = UUID.randomUUID();
            var task = state.create(id);
            assertThatThrownBy(() -> state.create(id)).isInstanceOf(IOException.class);
            Files.writeString(task.config().getParent().resolve(".atoms-agent-task"), "tampered");
            assertThatThrownBy(() -> state.verify(task)).isInstanceOf(IOException.class);
        }
    }
}
