package dev.atoms.demo.workspace;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class BuildEnvironmentTest {
    @TempDir Path temporary;
    @Test void staysDisabledByDefaultAndRefusesPublicOrIncompleteConfiguration() {
        var env = new MockEnvironment();
        assertThatThrownBy(() -> new BuildEnvironment(env).settings()).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("BUILD_DISABLED");
        env.withProperty("atoms.build.enabled", "true");
        env.setActiveProfiles("local");
        env.withProperty("server.address", "0.0.0.0");
        assertThatThrownBy(() -> new BuildEnvironment(env).settings()).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("BUILD_ENVIRONMENT_NOT_READY");
        env.withProperty("server.address", "127.0.0.1");
        assertThatThrownBy(() -> new BuildEnvironment(env).settings()).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("BUILD_ENVIRONMENT_NOT_READY");
    }
    @Test void explicitInstalledToolsAndPrivateConfigDoNotChangeSystemEnvironment() throws Exception {
        Path root = temporary.toRealPath();
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path config = Files.createFile(root.resolve("test.properties"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(config, "standalone.test-database=true\n");
        Path node = node();
        var env = new MockEnvironment().withProperty("atoms.build.enabled", "true").withProperty("server.address", "127.0.0.1")
                .withProperty("atoms.build.root", root.toString()).withProperty("atoms.build.node", node.toString())
                .withProperty("atoms.build.java", Path.of(System.getProperty("java.home"), "bin/java").toString())
                .withProperty("atoms.build.test-config", config.toString());
        env.setActiveProfiles("local");
        var settings = new BuildEnvironment(env).settings();
        assertThat(settings.node()).isEqualTo(node);
        assertThat(settings.environment()).containsKey("JAVA_HOME").doesNotContainKeys("DEEPSEEK_API_KEY", "APP_DB_PASSWORD", "NODE_OPTIONS", "JAVA_TOOL_OPTIONS");
        env.setActiveProfiles("server");
        assertThatThrownBy(() -> new BuildEnvironment(env).settings()).isInstanceOf(ApiException.class);
        env.setActiveProfiles("local");
        Files.writeString(config, "standalone.test-database=false\n");
        assertThatThrownBy(() -> new BuildEnvironment(env).settings()).isInstanceOf(ApiException.class)
                .hasMessageNotContaining(config.toString());
    }
    private Path node() throws Exception {
        for (String directory : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            Path executable = Path.of(directory).resolve("node");
            if (Files.isExecutable(executable)) return executable.toRealPath();
        }
        throw new IllegalStateException("Node 24 required");
    }
}
