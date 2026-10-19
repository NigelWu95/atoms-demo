package dev.atoms.demo.agent;

import dev.atoms.demo.source.ProjectSourcePolicy;
import dev.atoms.demo.source.SourceTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FullProjectApiSeedTest {
    @Test void nativeApiAndMigrationHaveTheirOwnExecutableTestAndNoPlatformDependency() throws Exception {
        var root = Path.of("../templates/full-project-v1");
        var files = new ArrayList<SourceTree.File>();
        for (String path : List.of("backend/src/main/java/dev/atoms/generated/HealthController.java",
                "backend/src/main/resources/db/migration/V001__application_metadata.sql",
                "backend/src/test/java/dev/atoms/generated/HealthControllerTest.java")) {
            var bytes = Files.readAllBytes(root.resolve(path));
            files.add(new SourceTree.File(path, bytes, 0100644));
            assertThat(Files.readString(root.resolve(path))).doesNotContain("dev.atoms.demo", "atoms_demo", "iframe", "AtomsSDK");
        }
        assertThat(new ProjectSourcePolicy().validate(files).files()).hasSize(3);
        assertThat(Files.readString(root.resolve(files.get(0).path())))
                .contains("/api/health", "INITIALIZED", "@RestController");
        assertThat(Files.readString(root.resolve(files.get(1).path())))
                .contains("generated_app.application_metadata", "full-project-v1").doesNotContain("DROP ", "CREATE DATABASE", "CREATE ROLE");
        assertThat(Files.readString(root.resolve(files.get(2).path())))
                .contains("@WebMvcTest(HealthController.class)", "get(\"/api/health\")", "status().isOk()", "INITIALIZED");
    }
}
