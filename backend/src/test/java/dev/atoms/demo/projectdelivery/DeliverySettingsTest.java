package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class DeliverySettingsTest {
    private static final String PREFIX = "atoms.project-delivery.";

    @Test void disabledDoesNotResolveOriginOrStartAnyExecutionResource() {
        var settings = new DeliverySettings(new MockEnvironment() {
            @Override public String getProperty(String name) {
                assertThat(name).isEqualTo(PREFIX + "enabled"); return null;
            }
        });
        assertThat(settings.enabled()).isFalse();
        assertThatThrownBy(settings::load).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("DELIVERY_DISABLED"));
    }

    @Test void acceptsOnlyExplicitHttpOriginsAndCanonicalizesDefaultPorts() {
        assertThat(settings("http://127.0.0.1:5174").load().origin()).isEqualTo("http://127.0.0.1:5174");
        assertThat(settings("https://EXAMPLE.com:443").load().origin()).isEqualTo("https://example.com");
        assertThat(settings("http://localhost:80").load().origin()).isEqualTo("http://localhost");
        assertThat(settings("http://[::1]:5174").load().origin()).isEqualTo("http://[::1]:5174");
    }

    @Test void rejectsPathsCredentialsWildcardsFragmentsAndInvalidPortsWithoutEchoingInput() {
        for (String value : new String[]{"", "https://example.com/", "http://localhost:0", "http://localhost:65536",
                "//example.com", "https://user:PRIVATE_CANARY@example.com", "https://example.com?token=PRIVATE_CANARY",
                "https://example.com#secret", "file:///etc/passwd", "https://*.example.com", "http://localhost\r\nBad: true",
                " https://example.com", "https://example.com ", "http://localhost;evil", "null"})
            assertThatThrownBy(() -> settings(value).load()).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo("DELIVERY_CONFIGURATION_INVALID"))
                    .hasMessageNotContaining("PRIVATE_CANARY").hasNoCause();
    }

    @Test void invalidSwitchAndResolutionErrorsFailClosed() {
        for (String value : new String[]{"TRUE", "yes", "1", "", "false "})
            assertThatThrownBy(() -> new DeliverySettings(new MockEnvironment().withProperty(PREFIX + "enabled", value)).enabled())
                    .isInstanceOf(ApiException.class).hasNoCause();
        assertThatThrownBy(() -> new DeliverySettings(new MockEnvironment() {
            @Override public String getProperty(String key) { throw new IllegalArgumentException("PRIVATE_CANARY"); }
        }).enabled()).hasMessageNotContaining("PRIVATE_CANARY").hasNoCause();
    }

    @Test void shippedAliasesAreLazyAndKeepExistingFeaturesDisabled() throws Exception {
        var env = new MockEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> env.getPropertySources().addLast(source));
        assertThat(new DeliverySettings(env).enabled()).isFalse();
        env.setProperty("PROJECT_DELIVERY_ENABLED", "true"); env.setProperty("PROJECT_DELIVERY_ORIGIN", "http://127.0.0.1:5174");
        assertThat(new DeliverySettings(env).load().origin()).isEqualTo("http://127.0.0.1:5174");
        assertThat(env.getProperty("atoms.project-execution.enabled")).isEqualTo("false");
        assertThat(env.getProperty("atoms.agent.max-concurrent-tasks")).isEqualTo("4");
    }

    private DeliverySettings settings(String origin) {
        return new DeliverySettings(new MockEnvironment().withProperty(PREFIX + "enabled", "true").withProperty(PREFIX + "origin", origin));
    }
}
