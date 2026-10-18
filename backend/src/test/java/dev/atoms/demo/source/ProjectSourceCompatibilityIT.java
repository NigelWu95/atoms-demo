package dev.atoms.demo.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.engineering.EngineeringSnapshotService;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceCompatibilityIT extends SourceIntegrationSupport {
    @Autowired ApplicationContext context;
    @Autowired ArtifactService artifacts;
    @Autowired EngineeringSnapshotService snapshots;
    @Autowired ObjectMapper json;

    @Test void disabledModuleLeavesLegacySaveRestoreAndEngineeringAssemblyIndependent() throws Exception {
        var disabled = context.getBean(ProjectSourceEnvironment.class);
        assertThat(disabled.enabled()).isFalse();
        assertThat(context.getBeansOfType(SourceCoordinator.class)).isEmpty();
        assertThatThrownBy(disabled::settings).isInstanceOf(dev.atoms.demo.common.ApiException.class)
                .hasMessageContaining("未启用");
        var source = json.createObjectNode().put("formatVersion", 1);
        source.putObject("manifest").put("title", "compatibility").put("summary", "Source disabled fixture")
                .put("runtime", "web-static-v1").put("stateSchemaVersion", 1).putObject("initialState").put("count", 0);
        var files = source.putObject("files").put("view.html", "<h1>first</h1>")
                .put("styles.css", "h1 {color:green}").put("main.js", "");
        var first = artifacts.save(owner, project.toString(), 0, null, source);
        files.put("view.html", "<h1>second</h1>");
        artifacts.save(owner, project.toString(), 1, first.id(), source);
        var restored = artifacts.restore(owner, project.toString(), json.createObjectNode()
                .put("sourceVersionId", first.id().toString()).put("expectedProjectRevision", 2), "disabled-restore");
        assertThat(restored.status()).isEqualTo(201);
        var current = artifacts.get(owner, project.toString(), projects.get(owner, project.toString()).currentVersionId().toString());
        assertThat(current.restoredFromId()).isEqualTo(first.id());
        assertThat(current.artifact()).isEqualTo(first.artifact());
        assertThat(snapshots.assemble(current, snapshots.templateDigest()).bundle().files())
                .extracting(dev.atoms.demo.engineering.EngineeringBundle.File::path)
                .contains("application/view.html", "backend/pom.xml", "frontend/src/App.vue");
        for (var table : java.util.List.of("project_sources", "source_versions", "source_operations"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE project_id=?", Integer.class, project)).isZero();
        try (var children = Files.list(temporary)) { assertThat(children.toList()).isEmpty(); }
    }
}
