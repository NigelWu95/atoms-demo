package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ExecutionSettingsTest {
    private static final String PREFIX = "atoms.project-execution.";
    private static final UUID OWNER = UUID.fromString("cbe155fc-ab0b-428e-ad2e-a7e468ba0308");
    @TempDir Path temporary;

    @Test void disabledReadsOnlyItsSwitchAndHasNoSideEffects() throws Exception {
        var environment = new MockEnvironment() {
            @Override public String getProperty(String key) {
                assertThat(key).isEqualTo(PREFIX + "enabled");
                return null;
            }
        };
        var settings = new ExecutionSettings(environment);
        assertThat(settings.enabled()).isFalse();
        assertThatThrownBy(settings::load).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo("EXECUTION_DISABLED");
            assertThat(error.status()).isEqualTo(503);
        });
        try (var paths = Files.list(temporary)) { assertThat(paths.toList()).isEmpty(); }
    }

    @Test void enabledSettingsArePureAndMatchTheApprovedBudgets() throws Exception {
        var settings = new ExecutionSettings(config()).load();
        assertThat(settings.root()).isEqualTo(temporary.resolve("execution"));
        assertThat(settings.node()).isEqualTo(temporary.resolve("node"));
        assertThat(settings.npm()).isEqualTo(temporary.resolve("npm"));
        assertThat(settings.java()).isEqualTo(temporary.resolve("java"));
        assertThat(settings.maven()).isEqualTo(temporary.resolve("maven"));
        assertThat(settings.policy()).isEqualTo(temporary.resolve("policy.json"));
        assertThat(settings.maintainerIds()).containsExactly(OWNER);
        assertThatThrownBy(() -> settings.maintainerIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(settings.maxConcurrentBuilds()).isOne();
        assertThat(settings.queueCapacity()).isEqualTo(8);
        assertThat(settings.maxRunningProjects()).isEqualTo(4);
        assertThat(settings.taskTimeout()).isEqualTo(Duration.ofSeconds(1800));
        assertThat(settings.queueTimeout()).isEqualTo(Duration.ofSeconds(300));
        assertThat(settings.startTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(ExecutionSettings.ROOT_MAX_BYTES).isEqualTo(8L << 30);
        assertThat(ExecutionSettings.MIN_FREE_BYTES).isEqualTo(1L << 30);
        assertThat(ExecutionSettings.JAR_MAX_BYTES).isEqualTo(256L << 20);
        assertThat(ExecutionSettings.DIAGNOSTIC_MAX_BYTES).isEqualTo(64 << 10);
        assertThat(ExecutionSettings.EVENT_LIMIT).isEqualTo(1000);
        assertThat(ExecutionSettings.PAGE_LIMIT).isEqualTo(100);
        try (var paths = Files.list(temporary)) { assertThat(paths.toList()).isEmpty(); }
    }

    @Test void pathsAreExplicitAbsoluteAndDoNotContainTraversalOrControlCharacters() {
        for (String key : new String[]{"root", "node", "npm", "java", "maven", "policy"}) {
            for (String value : new String[]{"", "/", "/tmp", "relative/tool", "/tmp/../tool", "/tmp/tool\nbad", "/tmp/a\0b"})
                assertInvalid(key, value);
        }
    }

    @Test void maintainersMustBeNonemptyCanonicalAndUnique() {
        for (String value : new String[]{"", " ", "1-1-1-1-1", OWNER + ",", OWNER + "," + OWNER,
                OWNER.toString().toUpperCase()})
            assertInvalid("maintainer-ids", value);
        var second = UUID.fromString("91c43ee2-8889-4ead-9a22-69794d5a5273");
        assertThat(new ExecutionSettings(config().withProperty(PREFIX + "maintainer-ids", OWNER + ", " + second))
                .load().maintainerIds()).containsExactlyInAnyOrder(OWNER, second);
    }

    @Test void malformedOrExpandedBudgetsFailClosed() {
        Map<String, Integer> ceilings = Map.of("max-concurrent-builds", 4, "queue-capacity", 8,
                "max-running-projects", 4, "task-timeout-seconds", 1800,
                "queue-timeout-seconds", 300, "start-timeout-seconds", 120);
        for (var entry : ceilings.entrySet()) {
            for (String value : new String[]{"0", "-1", "", "+1", "1.5", "no", "999999999999999999999",
                    Integer.toString(entry.getValue() + 1)})
                assertInvalid(entry.getKey(), value);
        }
        for (String value : new String[]{"TRUE", "yes", "1", "", "maybe"}) assertInvalid("enabled", value);
        for (int concurrency : new int[]{1, 2, 3, 4})
            assertThat(new ExecutionSettings(config().withProperty(PREFIX + "max-concurrent-builds",
                    Integer.toString(concurrency))).load().maxConcurrentBuilds()).isEqualTo(concurrency);
    }

    @Test void invalidConfigurationAndPlaceholderErrorsNeverEchoValues() {
        String canary = "execution-secret-canary";
        assertThatThrownBy(() -> new ExecutionSettings(config().withProperty(PREFIX + "node", canary)).load())
                .isInstanceOf(ApiException.class).hasMessageNotContaining(canary).hasNoCause();
        var environment = new MockEnvironment() {
            @Override public String getProperty(String key) { throw new IllegalArgumentException(canary); }
        };
        assertThatThrownBy(() -> new ExecutionSettings(environment).enabled())
                .isInstanceOf(ApiException.class).hasMessageNotContaining(canary).hasNoCause();
    }

    @Test void shippedConfigurationUsesIndependentAliasesWithoutChangingAgentDefaults() throws Exception {
        var environment = new MockEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.register(ExecutionSettings.class);
            context.refresh();
            assertThat(context.getBean(ExecutionSettings.class).enabled()).isFalse();
            assertThat(environment.getProperty("atoms.agent.max-concurrent-tasks")).isEqualTo("4");
            assertThat(environment.getProperty("atoms.build.enabled")).isEqualTo("false");
            assertThat(environment.getProperty(PREFIX + "max-concurrent-builds")).isEqualTo("1");
            // Override environment aliases, not the resolved Spring keys, to exercise the shipped YAML.
            environment.setProperty("PROJECT_EXECUTION_ENABLED", "true");
            for (String key : new String[]{"root", "node", "npm", "java", "maven", "policy"})
                environment.setProperty("PROJECT_EXECUTION_" + key.toUpperCase(), config().getProperty(PREFIX + key));
            environment.setProperty("PROJECT_EXECUTION_MAINTAINER_IDS", OWNER.toString());
            environment.setProperty("PROJECT_EXECUTION_MAX_CONCURRENT_BUILDS", "3");
            assertThat(context.getBean(ExecutionSettings.class).load().maxConcurrentBuilds()).isEqualTo(3);
            assertThat(environment.getProperty("atoms.agent.max-concurrent-tasks")).isEqualTo("4");
        }
    }

    private void assertInvalid(String key, String value) {
        assertThatThrownBy(() -> new ExecutionSettings(config().withProperty(PREFIX + key, value)).load())
                .as(key + " rejected").isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("EXECUTION_CONFIGURATION_INVALID"));
    }

    @Test void ownerAccessOpensExecutionWithoutGrantingMaintenancePermissions() {
        UUID guest = UUID.randomUUID();
        assertThat(new ExecutionSettings(config()).load().allows(guest)).isFalse();
        var opened = new ExecutionSettings(config().withProperty("atoms.project-workbench.owner-access-enabled", "true")).load();
        assertThat(opened.allows(guest)).isTrue();
        assertThat(opened.allows(null)).isFalse();
        assertThat(opened.maintainerIds()).doesNotContain(guest);
        assertThatThrownBy(() -> new ExecutionSettings(config().withProperty("atoms.project-workbench.owner-access-enabled", "yes")).load())
                .isInstanceOf(ApiException.class);
    }

    private MockEnvironment config() {
        return new MockEnvironment().withProperty(PREFIX + "enabled", "true")
                .withProperty(PREFIX + "root", temporary.resolve("execution").toString())
                .withProperty(PREFIX + "node", temporary.resolve("node").toString())
                .withProperty(PREFIX + "npm", temporary.resolve("npm").toString())
                .withProperty(PREFIX + "java", temporary.resolve("java").toString())
                .withProperty(PREFIX + "maven", temporary.resolve("maven").toString())
                .withProperty(PREFIX + "policy", temporary.resolve("policy.json").toString())
                .withProperty(PREFIX + "maintainer-ids", OWNER.toString());
    }
}
