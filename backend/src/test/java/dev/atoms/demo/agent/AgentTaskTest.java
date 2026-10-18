package dev.atoms.demo.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentTaskTest {
    private AgentSettings.Configuration config(int turns) {
        return new AgentSettings.Configuration(Path.of("/tools/claude"), Path.of("/state/agent"), Set.of(UUID.randomUUID()),
                false, 0, 128000, turns, Duration.ofMinutes(30), Duration.ofMinutes(5));
    }

    @Test void strictRequestBindsModelConfigurationAndExactPromptWithoutExposingIt() {
        var base = UUID.randomUUID().toString();
        var one = AgentTask.request("PRIVATE_PROMPT", base, 1, "request", config(24));
        var same = AgentTask.request("PRIVATE_PROMPT", base, 1, "request", config(24));
        assertThat(one.digest()).isEqualTo(same.digest()).matches("[a-f0-9]{64}");
        assertThat(one.key()).matches("[a-f0-9]{64}").isNotEqualTo("request");
        assertThat(one.digest()).isNotEqualTo(AgentTask.request("PRIVATE_PROMPT", base, 1, "request", config(23)).digest());
        assertThat(one.digest()).isNotEqualTo(AgentTask.request("PRIVATE_PROMPT changed", base, 1, "request", config(24)).digest());
        assertThat(one.toString()).doesNotContain("PRIVATE_PROMPT", "request");
    }

    @Test void rejectsMalformedOrOversizedInputBeforeAnyAdmission() {
        var base = UUID.randomUUID().toString();
        for (String prompt : new String[] {null, "", "  ", "a\0b", "x".repeat(8001)})
            assertThatThrownBy(() -> AgentTask.request(prompt, base, 1, "key", config(24))).isInstanceOf(dev.atoms.demo.common.ApiException.class);
        for (String id : new String[] {null, "1-1-1-1-1", base.toUpperCase(), "invalid"})
            assertThatThrownBy(() -> AgentTask.request("idea", id, 1, "key", config(24))).isInstanceOf(dev.atoms.demo.common.ApiException.class);
        for (String key : new String[] {null, "", " ", "a\nb", "x".repeat(129)})
            assertThatThrownBy(() -> AgentTask.request("idea", base, 1, key, config(24))).isInstanceOf(dev.atoms.demo.common.ApiException.class);
        assertThatThrownBy(() -> AgentTask.request("idea", base, 0, "key", config(24))).isInstanceOf(dev.atoms.demo.common.ApiException.class);
        assertThatThrownBy(() -> AgentTask.request("idea", base, 9007199254740992L, "key", config(24))).isInstanceOf(dev.atoms.demo.common.ApiException.class);
    }
}
