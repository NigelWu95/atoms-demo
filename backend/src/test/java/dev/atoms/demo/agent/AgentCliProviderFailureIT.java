package dev.atoms.demo.agent;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Real pinned CLI with only a local, deliberately failing provider and synthetic credentials. */
@Tag("agent-cli-contract")
class AgentCliProviderFailureIT {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(ints = {400, 401, 429, 500})
    void reportsProviderFailureWithoutAcceptingASourceResult(int status) throws Exception {
        String cli = System.getProperty("atoms.agent.test.cli");
        assertThat(cli).isNotBlank();
        try (var fixture = new AgentProtocolFixture(temporary.toRealPath(), Path.of(cli))) {
            var result = fixture.rejectProvider(status);
            assertThat(result.success()).isFalse();
            assertThat(fixture.summary()).isNull();
            // The pinned CLI makes two requests for this HTTP 400 fixture within one session.
            // Session reservations must never be presented as an API-request limit.
            assertThat(fixture.requests()).hasSize(status == 400 ? 2 : 1).allSatisfy(request -> {
                assertThat(request.apiKey()).isEqualTo("offline-fixture-key");
                assertThat(request.model()).isEqualTo("deepseek-flash");
                assertThat(request.tools()).containsExactlyInAnyOrder("Read", "Glob", "Grep", "Write", "Edit");
            });
            assertThat(fixture.decoderFailure()).isEqualTo(switch (status) {
                case 401 -> "AGENT_PROVIDER_AUTH_FAILED";
                case 429 -> "AGENT_PROVIDER_RATE_LIMITED";
                case 500 -> "AGENT_PROVIDER_UNAVAILABLE";
                default -> "AGENT_PROVIDER_ERROR";
            });
        }
    }
}
