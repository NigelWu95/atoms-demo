package dev.atoms.demo.instances;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimeConfigurationTest {
    private Properties valid() {
        var p = new Properties();
        p.setProperty("STANDALONE_DB_URL", "jdbc:postgresql://127.0.0.1:5432/runtime_example");
        p.setProperty("STANDALONE_DB_USER", "runtime_example");
        p.setProperty("STANDALONE_DB_PASSWORD", "a".repeat(64));
        p.setProperty("APP_INSTANCE_ID", "d2bd6db9-c072-4554-9cfb-3563d561cfc5");
        p.setProperty("server.address", "127.0.0.1");
        p.setProperty("server.port", "18089");
        p.setProperty("standalone.origin", "http://127.0.0.1:18089");
        p.setProperty("standalone.test-database", "false");
        return p;
    }
    @Test void acceptsOnlyDedicatedLocalRuntimeSlotsAndDoesNotExposeCredentials() {
        var config = RuntimeConfiguration.parse(valid());
        assertThat(config.port()).isEqualTo(18089);
        assertThat(config.toString()).doesNotContain("a".repeat(64), "runtime_example");
    }
    @Test void rejectsTestPlatformAndRemoteTargetsBeforeStartingAnything() {
        for (var pair : new String[][] {{"standalone.test-database","true"}, {"server.address","0.0.0.0"},
                {"standalone.origin","http://evil.example"}, {"STANDALONE_DB_URL","jdbc:postgresql://127.0.0.1:5432/atoms_demo"},
                {"STANDALONE_DB_URL","jdbc:postgresql://127.0.0.1:5432/atoms_demo_test"}, {"server.port","8080"},
                {"spring.config.import","file:/secret"}, {"APP_INSTANCE_ID","not-a-uuid"}}) {
            var p = valid(); p.setProperty(pair[0], pair[1]);
            assertThatThrownBy(() -> RuntimeConfiguration.parse(p)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid dedicated runtime configuration");
        }
    }
}
