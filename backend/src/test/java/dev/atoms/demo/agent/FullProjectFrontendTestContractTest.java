package dev.atoms.demo.agent;

import dev.atoms.demo.source.ProjectSourcePolicy;
import dev.atoms.demo.source.SourceTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FullProjectFrontendTestContractTest {
    @Test void templateShipsRealComponentAndBoundaryTestsWithoutPlatformFixtures() throws Exception {
        Path root = Path.of("../templates/full-project-v1/frontend");
        var files = new ArrayList<SourceTree.File>();
        for (String path : List.of("src/App.vue", "src/App.spec.ts", "src/app.css", "src/icon.svg", "dev/boundary.spec.ts", "eslint.config.js")) {
            files.add(new SourceTree.File("frontend/" + path, Files.readAllBytes(root.resolve(path)), 0100644));
            assertThat(Files.readString(root.resolve(path))).doesNotContain("AtomsSDK", "/api/session", "api/projects", "DEEPSEEK_API_KEY");
        }
        assertThat(new ProjectSourcePolicy().validate(files).files()).hasSize(6);
        assertThat(Files.readString(root.resolve("src/App.vue")))
                .contains("./api/health", "AbortController", "onUnmounted", "尚未生成业务功能", "role=\"status\"", "role=\"alert\"");
        assertThat(Files.readString(root.resolve("src/App.spec.ts")))
                .contains("mount(App)", "mockRejectedValueOnce", "PRIVATE_UPSTREAM_CANARY", "signal?.aborted", "vi.getTimerCount()");
        assertThat(Files.readString(root.resolve("dev/boundary.spec.ts"))).contains("cross-site", "Origin", "5275", "4275");
        assertThat(Files.readString(root.resolve("eslint.config.js"))).contains("tseslint.configs.recommended", "flat/essential");
    }
}
