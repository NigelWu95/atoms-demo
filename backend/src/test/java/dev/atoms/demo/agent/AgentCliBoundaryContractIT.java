package dev.atoms.demo.agent;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("agent-cli-contract")
class AgentCliBoundaryContractIT {
    @TempDir Path temporary;
    @Test void forbiddenToolsAndSyntheticPrivateConfigurationCannotBeUsedOrLeaked() throws Exception {
        String cli = System.getProperty("atoms.agent.test.cli");
        assertThat(cli).isNotBlank();
        try (var fixture = new AgentProtocolFixture(temporary.toRealPath(), Path.of(cli))) {
            var result = fixture.exerciseForbiddenCapabilities();
            assertThat(result.initialTools()).containsExactlyInAnyOrder("Read", "Glob", "Grep", "Write", "Edit");
            assertThat(fixture.toolResults()).hasSize(6).allSatisfy((id, outcome) -> assertThat(outcome.error()).as(id).isTrue());
            assertThat(fixture.requests()).hasSize(7).allSatisfy(request -> {
                assertThat(request.apiKey()).isEqualTo("offline-fixture-key");
                assertThat(request.tools()).containsExactlyInAnyOrder("Read", "Glob", "Grep", "Write", "Edit");
            });
            assertThat(fixture.configurationCanarySeen()).isFalse();
            assertThat(fixture.outsideContentSeen()).isFalse();
            // An unadvertised tool_use is malformed protocol even if the CLI later reports success.
            assertThat(fixture.decoderFailure()).isEqualTo("AGENT_PROTOCOL_INVALID");
            assertThat(fixture.summary()).isNull();
        }
    }
}
