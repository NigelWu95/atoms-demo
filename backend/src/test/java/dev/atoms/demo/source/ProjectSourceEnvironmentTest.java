package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceEnvironmentTest {
    @TempDir Path temporary;

    @Test void disabledDoesNotInspectGitOrCreateAnyDirectory() throws Exception {
        var root = temporary.toRealPath();
        var config = new MockEnvironment().withProperty("atoms.source.root", root.resolve("source").toString())
                .withProperty("atoms.source.git", "/not/an/executable");
        try (var environment = new ProjectSourceEnvironment(config)) {
            assertThat(environment.enabled()).isFalse();
            assertThatThrownBy(environment::settings).isInstanceOf(ApiException.class)
                    .hasMessageContaining("未启用");
        }
        try (var children = Files.list(root)) { assertThat(children.toList()).isEmpty(); }
    }

    @Test void rejectsIncompleteConfigurationBeforeCreatingRoots() throws Exception {
        var root = temporary.toRealPath();
        var config = new MockEnvironment().withProperty("atoms.source.enabled", "true")
                .withProperty("atoms.source.root", root.resolve("source").toString());
        try (var environment = new ProjectSourceEnvironment(config)) {
            assertThatThrownBy(environment::settings).isInstanceOf(ApiException.class);
        }
        assertThat(Files.exists(root.resolve("source"))).isFalse();
    }

    @Test void explicitSettingsAreLazyAndClosedEnvironmentCannotBeReopened() throws Exception {
        var root = temporary.toRealPath();
        var config = new MockEnvironment().withProperty("atoms.source.enabled", "true")
                .withProperty("atoms.source.root", root.resolve("source").toString())
                .withProperty("atoms.source.work-root", root.resolve("work").toString())
                .withProperty("atoms.source.git", "/usr/bin/git");
        var environment = new ProjectSourceEnvironment(config);
        assertThat(Files.exists(root.resolve("source"))).isFalse();
        try (environment) {
            var settings = environment.settings();
            assertThat(settings.git()).isEqualTo(Path.of("/usr/bin/git"));
            assertThat(settings.paths().root()).isEqualTo(root.resolve("source"));
            assertThat(environment.settings()).isSameAs(settings);
        }
        assertThatThrownBy(environment::settings).isInstanceOf(ApiException.class);
        assertThat(Files.isDirectory(root.resolve("source"))).isTrue();
    }
}
