package dev.atoms.demo.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.Artifact;
import dev.atoms.demo.artifact.ArtifactService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LegacyEngineeringSourceTest {
    @Test void keepsPreviousCodeAsReferenceAndExcludesRuntimeAndInitialData() {
        var template = new FullProjectTemplateCatalog().load();
        var source = Map.of("view.html", "<main>2048</main>", "styles.css", "main{color:red}", "main.js", "let score=2048;");
        var artifact = new Artifact(1, new Artifact.Manifest("Game", "Old app", "web-static-v1", 1,
                new ObjectMapper().createObjectNode().put("privateData", "must-not-export")), source);
        UUID version = UUID.randomUUID();
        var result = LegacyEngineeringSource.attach(template, new ArtifactService.Version(version, UUID.randomUUID(), 6,
                "a".repeat(64), null, null, Instant.now(), artifact));
        assertThat(result.files()).hasSize(template.files().size() + 4);
        for (var file : template.files())
            assertThat(result.files().stream().filter(f -> f.path().equals(file.path())).findFirst().orElseThrow().content())
                    .isEqualTo(file.content());
        for (var file : result.files()) {
            String text = new String(file.content(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(text).doesNotContain("must-not-export");
            if (file.path().equals("legacy/MIGRATION.md")) assertThat(text).contains(version.toString(), "backend authentication");
            if (file.path().startsWith("legacy/") && !file.path().endsWith(".md"))
                assertThat(text).isEqualTo(source.get(file.path().substring(7)));
        }
    }
}
