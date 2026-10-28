package dev.atoms.demo.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Reproduces thinking progress notifications using the installed CLI and synthetic SSE only. */
@Tag("agent-cli-contract")
class AgentCliThinkingContractIT {
    @TempDir Path temporary;

    @Test void thinkingProgressDoesNotAbortTheFileEditingSessionOrExposeReasoning() throws Exception {
        String cli = System.getProperty("atoms.agent.test.cli");
        assertThat(cli).isNotBlank();
        try (var fixture = new AgentProtocolFixture(temporary.toRealPath(), Path.of(cli))) {
            var result = fixture.editWithThinking();
            assertThat(result.success()).isTrue();
            assertThat(result.exitCode()).isZero();
            assertThat(fixture.summary().toolCounts()).containsEntry("Read", 1).containsEntry("Edit", 1);
            assertThat(fixture.summary().toString()).doesNotContain("PRIVATE_SYNTHETIC_THINKING");
            assertThat(fixture.requests()).hasSize(3);
            assertThat(Files.readString(fixture.project().resolve("hello.txt"))).isEqualTo("after\n");
        }
    }
}
