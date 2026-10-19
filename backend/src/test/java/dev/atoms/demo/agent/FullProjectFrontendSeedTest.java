package dev.atoms.demo.agent;

import dev.atoms.demo.source.ProjectSourcePolicy;
import dev.atoms.demo.source.SourceTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FullProjectFrontendSeedTest {
    @Test void entryAndDevelopmentConfigurationAreNativeAndLoopbackOnly() throws Exception {
        var root = Path.of("../templates/full-project-v1/frontend");
        var files = new ArrayList<SourceTree.File>();
        for (String name : List.of("vite.config.ts", "index.html", "src/main.ts", "dev/boundary.ts")) {
            files.add(new SourceTree.File("frontend/" + name, Files.readAllBytes(root.resolve(name)), 0100644));
            assertThat(Files.readString(root.resolve(name))).doesNotContain("AtomsSDK", "iframe", "view.html", "styles.css", "dev.atoms.demo");
        }
        assertThat(new ProjectSourcePolicy().validate(files).files()).hasSize(4);
        assertThat(Files.readString(root.resolve("index.html"))).contains("lang=\"zh-CN\"", "./src/main.ts", "viewport");
        assertThat(Files.readString(root.resolve("src/main.ts"))).contains("createApp(App)", "./App.vue");
        assertThat(Files.readString(root.resolve("vite.config.ts")))
                .contains("envDir: false", "envPrefix: []", "publicDir: false", "127.0.0.1", "strictPort: true", "5275", "4275", "passWithNoTests: false");
        assertThat(Files.readString(root.resolve("dev/boundary.ts"))).contains("FULL_PROJECT_DEV_API_PORT", "isAllowedRequest", "sec-fetch-site");
    }
}
