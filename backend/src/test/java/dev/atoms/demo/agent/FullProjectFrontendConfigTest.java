package dev.atoms.demo.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.source.ProjectSourcePolicy;
import dev.atoms.demo.source.SourceTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FullProjectFrontendConfigTest {
    @Test void nativeFrontendReusesReviewedLockedDependenciesAndDisablesInstallScripts() throws Exception {
        Path root = Path.of("../templates/full-project-v1/frontend");
        var json = new ObjectMapper();
        var manifest = json.readTree(Files.readString(root.resolve("package.json")));
        var lock = json.readTree(Files.readString(root.resolve("package-lock.json")));
        var reviewed = json.readTree(Files.readString(Path.of("../templates/standalone-v1/frontend/package-lock.json")));
        assertThat(manifest.path("name").asText()).isEqualTo("atoms-full-project-app");
        assertThat(lock.path("name")).isEqualTo(manifest.path("name"));
        assertThat(lock.path("packages").path("").path("name")).isEqualTo(manifest.path("name"));
        assertThat(lock.path("lockfileVersion").asInt()).isEqualTo(3);
        for (String key : List.of("dependencies", "devDependencies", "engines")) {
            assertThat(manifest.path(key)).isEqualTo(lock.path("packages").path("").path(key));
            assertThat(manifest.path(key)).isEqualTo(reviewed.path("packages").path("").path(key));
        }
        var packages = lock.path("packages").properties();
        assertThat(packages).hasSize(reviewed.path("packages").size());
        for (var entry : packages) if (!entry.getKey().isEmpty())
            assertThat(entry.getValue()).isEqualTo(reviewed.path("packages").path(entry.getKey()));
        assertThat(Files.readString(root.resolve(".npmrc"))).contains("ignore-scripts=true", "engine-strict=true");
        assertThat(manifest.path("scripts").path("typecheck").asText()).isEqualTo("vue-tsc --noEmit");
        assertThat(manifest.path("scripts").path("test:unit").asText()).isEqualTo("vitest run");
        assertThat(manifest.path("scripts").has("postinstall")).isFalse();
        var types = json.readTree(Files.readString(root.resolve("tsconfig.json")));
        assertThat(types.path("compilerOptions").path("strict").asBoolean()).isTrue();
        assertThat(types.path("compilerOptions").path("skipLibCheck").asBoolean()).isFalse();
        var files = new ArrayList<SourceTree.File>();
        for (String path : List.of("package.json", "package-lock.json", ".npmrc", "tsconfig.json"))
            files.add(new SourceTree.File("frontend/" + path, Files.readAllBytes(root.resolve(path)), 0100644));
        assertThat(new ProjectSourcePolicy().validate(files).files()).hasSize(4);
    }
}
