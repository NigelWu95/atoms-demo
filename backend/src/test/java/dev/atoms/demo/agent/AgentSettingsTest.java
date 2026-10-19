package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class AgentSettingsTest {
    private static final UUID OWNER = UUID.fromString("cbe155fc-ab0b-428e-ad2e-a7e468ba0308");
    @TempDir Path temporary;

    @Test void disabledReadsOnlyItsSwitchAndDoesNotTouchDirectoriesOrCredentials() throws Exception {
        var configuration = new MockEnvironment() {
            @Override public String getProperty(String key) {
                assertThat(key).isEqualTo("atoms.agent.enabled");
                return null;
            }
        };
        var settings = new AgentSettings(configuration);
        assertThat(settings.enabled()).isFalse();
        assertThatThrownBy(settings::load).isInstanceOf(ApiException.class).hasMessageContaining("未启用");
        try (var paths = Files.list(temporary)) { assertThat(paths.toList()).isEmpty(); }
    }

    @Test void enabledConfigurationIsPureAndDefaultsRemainOffline() throws Exception {
        var settings = new AgentSettings(config()).load();
        assertThat(settings.liveEnabled()).isFalse();
        assertThat(settings.maxLiveRuns()).isZero();
        assertThat(settings.maintainerIds()).containsExactly(OWNER);
        assertThat(settings.maxOutputTokens()).isEqualTo(128000);
        assertThat(settings.maxTurns()).isEqualTo(24);
        assertThat(settings.taskTimeout()).isEqualTo(Duration.ofSeconds(1800));
        assertThat(settings.queueTimeout()).isEqualTo(Duration.ofSeconds(300));
        assertThat(settings.maxConcurrentTasks()).isEqualTo(2);
        assertThat(settings.cli()).isEqualTo(temporary.resolve("cli"));
        assertThat(settings.stateRoot()).isEqualTo(temporary.resolve("state"));
        try (var paths = Files.list(temporary)) { assertThat(paths.toList()).isEmpty(); }
        assertThatThrownBy(() -> settings.maintainerIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void enabledRequiresDedicatedAbsolutePathsAndCanonicalMaintainers() {
        for (var entry : java.util.Map.of("cli", "", "state-root", "/", "maintainer-ids", "").entrySet()) {
            assertInvalid(entry.getKey(), entry.getValue());
        }
        assertInvalid("cli", "relative/claude");
        assertInvalid("state-root", "/tmp/../state");
        assertInvalid("maintainer-ids", "1-1-1-1-1");
        assertInvalid("maintainer-ids", OWNER + ",");
        assertInvalid("maintainer-ids", OWNER + "," + OWNER);
    }

    @Test void rejectsInvalidSwitchesAndUnapprovedResourceExpansion() {
        assertInvalid("enabled", "maybe");
        assertInvalid("live-enabled", "yes");
        assertInvalid("max-live-runs", "-1");
        assertInvalid("max-output-tokens", "163840");
        assertInvalid("max-output-tokens", "0");
        assertInvalid("max-turns", "25");
        assertInvalid("task-timeout-seconds", "1801");
        assertInvalid("queue-timeout-seconds", "301");
        assertInvalid("queue-timeout-seconds", "0");
        assertInvalid("max-turns", "999999999999999999999999");
    }

    @Test void invalidValuesNeverAppearInPublicErrors() {
        String canary = "not-a-valid-config-secret-canary";
        assertThatThrownBy(() -> new AgentSettings(config().withProperty("atoms.agent.cli", canary)).load())
                .isInstanceOf(ApiException.class).hasMessageNotContaining(canary).hasNoCause();
    }

    @Test void rejectsUnboundedOrMalformedConcurrency() {
        for (String value : new String[]{"0", "-1", "9", "", "two", "999999999999999999999"})
            assertInvalid("max-concurrent-tasks", value);
    }

    @Test void acceptsExplicitConcurrencyWithinTheBound() {
        for (int value : new int[]{1, 2, 3, 8})
            assertThat(new AgentSettings(config().withProperty("atoms.agent.max-concurrent-tasks", Integer.toString(value)))
                    .load().maxConcurrentTasks()).isEqualTo(value);
    }

    private void assertInvalid(String key, String value) {
        assertThatThrownBy(() -> new AgentSettings(config().withProperty("atoms.agent." + key, value)).load())
                .as(key).isInstanceOf(ApiException.class);
    }

    private MockEnvironment config() {
        return new MockEnvironment().withProperty("atoms.agent.enabled", "true")
                .withProperty("atoms.agent.cli", temporary.resolve("cli").toString())
                .withProperty("atoms.agent.state-root", temporary.resolve("state").toString())
                .withProperty("atoms.agent.maintainer-ids", OWNER.toString());
    }
}
