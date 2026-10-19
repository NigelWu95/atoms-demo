package dev.atoms.demo.agent;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class AgentChildFixtureTest {
    @TempDir Path temporary;

    @Test void pidReadinessIsPublishedOnlyAfterTheCompleteValueHasBeenWritten() throws Exception {
        Path pid = temporary.resolve("child.pid");
        AgentChildFixture.publishPid(pid, () -> {
            assertThat(pid).doesNotExist();
            assertThat(temporary.resolve("child.pid.pending"))
                    .hasContent(Long.toString(ProcessHandle.current().pid()));
        });
        assertThat(pid).hasContent(Long.toString(ProcessHandle.current().pid()));
        assertThat(temporary.resolve("child.pid.pending")).doesNotExist();
    }

    @Test void interruptionBeforePublicationNeverExposesAnEmptyReadySignal() {
        Path pid = temporary.resolve("child.pid");
        assertThatThrownBy(() -> AgentChildFixture.publishPid(pid, () -> { throw new IllegalStateException("fixture interruption"); }))
                .isInstanceOf(IllegalStateException.class).hasMessage("fixture interruption");
        assertThat(pid).doesNotExist();
        assertThat(temporary.resolve("child.pid.pending")).isRegularFile();
    }
}
