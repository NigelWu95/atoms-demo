package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudeCodeAgentTest {
    @TempDir Path temporary;
    private final UUID owner = UUID.randomUUID();

    @Test void disabledOrUnapprovedCallsDoNotReadCredentialsOrTouchAgentState() {
        var state = mock(AgentEnvironment.class);
        var disabled = new SecretGuardEnvironment();
        assertThatThrownBy(() -> new ClaudeCodeAgent(new AgentSettings(disabled), state, disabled)
                .prepare(task(), temporary, new AgentDeadline(Duration.ofMinutes(1))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AGENT_DISABLED"));
        var config = config();
        assertThatThrownBy(() -> new ClaudeCodeAgent(new AgentSettings(config), state, config)
                .prepare(task(), temporary, new AgentDeadline(Duration.ofMinutes(1))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AGENT_LIVE_DISABLED"));
        config.withProperty("atoms.agent.live-enabled", "true").withProperty("atoms.agent.maintainer-ids", UUID.randomUUID().toString());
        assertThatThrownBy(() -> new ClaudeCodeAgent(new AgentSettings(config), state, config)
                .prepare(task(), temporary, new AgentDeadline(Duration.ofMinutes(1))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AGENT_NOT_ALLOWED"));
        verifyNoInteractions(state);
        assertThat(temporary.resolve("state")).doesNotExist();
    }

    @Test void expiredTaskAndChangedBudgetFailBeforeProbeOrSecrets() {
        var state = mock(AgentEnvironment.class); var config = config().withProperty("atoms.agent.live-enabled", "true");
        var agent = new ClaudeCodeAgent(new AgentSettings(config), state, config);
        var time = new java.util.concurrent.atomic.AtomicLong();
        var deadline = new AgentDeadline(Duration.ofSeconds(1), time::get); time.set(Duration.ofSeconds(2).toNanos());
        assertThatThrownBy(() -> agent.prepare(task(), temporary, deadline)).hasMessage("AGENT_TIMEOUT");
        config.withProperty("atoms.agent.max-turns", "1");
        assertThatThrownBy(() -> agent.prepare(task(), temporary, new AgentDeadline(Duration.ofMinutes(1))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AGENT_CONFIGURATION_CHANGED"));
        verifyNoInteractions(state);
    }

    private SecretGuardEnvironment config() {
        var config = new SecretGuardEnvironment();
        config.withProperty("atoms.agent.enabled", "true").withProperty("atoms.agent.cli", temporary.resolve("cli").toString())
                .withProperty("atoms.agent.state-root", temporary.resolve("state").toString())
                .withProperty("atoms.agent.maintainer-ids", owner.toString());
        return config;
    }
    private AgentTask task() {
        return new AgentTask(UUID.randomUUID(), UUID.randomUUID(), owner, UUID.randomUUID(), UUID.randomUUID(), 1,
                "a".repeat(64), "b".repeat(64), "PRIVATE_IDEA", "PREPARING", false, null, null, null, false, null,
                128000, 24, 1800, 300, Instant.now(), Instant.now(), null, "internal_service");
    }
    private static class SecretGuardEnvironment extends MockEnvironment {
        @Override public String getProperty(String key) {
            if (key.equals("DEEPSEEK_API_KEY")) throw new AssertionError("Credential was accessed before admission");
            return super.getProperty(key);
        }
    }
}
