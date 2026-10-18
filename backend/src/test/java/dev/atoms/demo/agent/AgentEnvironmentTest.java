package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.source.ProjectSourceEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class AgentEnvironmentTest {
    @TempDir Path temporary;

    @Test void disabledPreparationDoesNotCreateDirectoriesOrReadCredentials() throws Exception {
        var config = configuration().withProperty("atoms.agent.enabled", "false");
        try (var source = new ProjectSourceEnvironment(config);
             var environment = new AgentEnvironment(new AgentSettings(config), source, () -> 501)) {
            assertThatThrownBy(() -> environment.prepare(UUID.randomUUID())).isInstanceOf(ApiException.class);
            assertThat(Files.exists(temporary.resolve("state"))).isFalse();
            assertThat(Files.exists(temporary.resolve("source"))).isFalse();
        }
    }

    @Test void taskPreparationSeparatesStateFromSourceAndWorksWithoutARealKey() throws Exception {
        var config = configuration();
        try (var source = new ProjectSourceEnvironment(config);
             var environment = new AgentEnvironment(new AgentSettings(config), source, () -> 501)) {
            var task = environment.prepare(UUID.randomUUID());
            environment.verify(task);
            assertThat(task.config()).startsWith(temporary.toRealPath().resolve("state"));
            assertThat(task.config().startsWith(temporary.toRealPath().resolve("source"))).isFalse();
            assertThat(Files.exists(task.config().resolve("settings.json"))).isFalse();
        }
    }

    @Test void rootLiveExecutionFailsBeforeAnyStorageIsCreated() throws Exception {
        var config = configuration().withProperty("atoms.agent.live-enabled", "true");
        try (var source = new ProjectSourceEnvironment(config);
             var environment = new AgentEnvironment(new AgentSettings(config), source, () -> 0)) {
            assertThatThrownBy(() -> environment.prepare(UUID.randomUUID())).isInstanceOf(ApiException.class)
                    .hasMessageContaining("root");
            assertThat(Files.exists(temporary.resolve("state"))).isFalse();
            assertThat(Files.exists(temporary.resolve("source"))).isFalse();
        }
    }

    private MockEnvironment configuration() throws Exception {
        Path parent = temporary.toRealPath();
        return new MockEnvironment() {
            @Override public String getProperty(String key) {
                if (key.contains("API_KEY") || key.contains("PASSWORD")) throw new AssertionError("Unexpected secret read");
                return super.getProperty(key);
            }
        }.withProperty("atoms.agent.enabled", "true").withProperty("atoms.agent.state-root", parent.resolve("state").toString())
                .withProperty("atoms.agent.cli", "/opt/cli/claude").withProperty("atoms.agent.maintainer-ids", UUID.randomUUID().toString())
                .withProperty("atoms.source.enabled", "true").withProperty("atoms.source.root", parent.resolve("source").toString())
                .withProperty("atoms.source.work-root", parent.resolve("work").toString()).withProperty("atoms.source.git", "/usr/bin/git");
    }
}
