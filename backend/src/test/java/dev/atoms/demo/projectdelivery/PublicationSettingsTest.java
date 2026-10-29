package dev.atoms.demo.projectdelivery;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class PublicationSettingsTest {
    @Test void disabledNeverResolvesHost() {
        var env = new MockEnvironment() {
            @Override public String getProperty(String key, String fallback) {
                assertThat(key).isEqualTo("atoms.project-publication.enabled"); return "false";
            }
        };
        assertThat(new PublicationSettings(env).load()).isNull();
    }
    @Test void usesExplicitHostAndOffsetOnly() {
        var env = env("192.0.2.10").withProperty("atoms.project-publication.bind-address", "0.0.0.0");
        var config = new PublicationSettings(env).load();
        assertThat(config.origin(18201)).isEqualTo("http://192.0.2.10:28201");
        assertThat(config.bindAddress()).isEqualTo("0.0.0.0");
        assertThatThrownBy(() -> config.port(60000)).hasMessageContaining("配置无效");
    }
    @Test void refusesAmbiguousHostsAndUnsafeConfiguration() {
        for (String host : new String[]{"", "0.0.0.0", "224.0.0.1", "169.254.169.254", "127.1", "0177.0.0.1", "256.0.0.1", "example.com", "1.2.3.4:80", "1.2.3.4\r\n"})
            assertThatThrownBy(() -> new PublicationSettings(env(host)).load()).hasMessageContaining("配置无效");
        assertThatThrownBy(() -> new PublicationSettings(env("127.0.0.1").withProperty("atoms.project-publication.port-offset", "0")).load()).hasMessageContaining("配置无效");
    }
    private MockEnvironment env(String host) { return new MockEnvironment().withProperty("atoms.project-publication.enabled", "true").withProperty("atoms.project-publication.host", host); }
}
