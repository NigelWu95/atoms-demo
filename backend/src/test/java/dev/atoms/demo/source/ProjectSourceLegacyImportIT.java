package dev.atoms.demo.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.engineering.EngineeringSnapshotService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceLegacyImportIT extends SourceIntegrationSupport {
    @Autowired ArtifactService artifacts;
    @Autowired EngineeringSnapshotService snapshots;
    @Autowired ObjectMapper json;

    @Test void importsAnExplicitLegacyVersionWithoutChangingLegacyPointersOrRuntimeData() throws Exception {
        var first = artifacts.save(owner, project.toString(), 0, null, artifact("one"));
        var second = artifacts.save(owner, project.toString(), 1, first.id(), artifact("two"));
        for (String name : java.util.List.of("PREVIEW", "LIVE")) jdbc.update("""
                INSERT INTO app_states(project_id,environment,actor_id,value_json,revision,state_schema_version)
                VALUES (?,?,?,'{"count":99}',1,1)
                """, project, name, owner);
        var legacy = jdbc.queryForList("SELECT * FROM versions WHERE project_id=? ORDER BY version_no", project);
        var states = jdbc.queryForList("SELECT * FROM app_states WHERE project_id=? ORDER BY environment", project);
        var before = projects.get(owner, project.toString());
        String digest = snapshots.templateDigest();
        for (var file : snapshots.assemble(first, digest).bundle().files()) {
            if (!file.path().equals("bundle-manifest.json"))
                assertThatCode(() -> new ProjectSourcePolicy().validate(java.util.List.of(
                        new SourceTree.File(file.path(), file.content(), 0100644))))
                        .as("controlled template source path %s", file.path()).doesNotThrowAnyException();
        }
        var importer = new LegacySourceImporter(snapshots, service);
        var prepared = importer.prepare(owner, project, first.id(), digest, "import-v1");
        assertThat(prepared.tree().files()).hasSizeGreaterThan(100);
        assertThat(prepared.tree().files()).extracting(SourceTree.File::path)
                .contains("frontend/src/App.vue", "backend/pom.xml", "application/view.html")
                .doesNotContain("bundle-manifest.json");
        assertThat(text(prepared.tree(), "application/view.html")).contains("one").doesNotContain("two");
        assertThat(json.readTree(text(prepared.tree(), "application/manifest.json")).path("initialState").path("count").asInt()).isZero();
        var outcome = new SourceWorker(service, repository, environment).execute(prepared.operation(), prepared.tree());
        assertThat(outcome.status()).isEqualTo("COMMITTED");
        var version = repository.version(project, outcome.resultVersionId());
        assertThat(version.legacyVersionId()).isEqualTo(first.id());
        assertThat(version.templateDigest()).isEqualTo(digest);
        assertThat(version.templateId()).isEqualTo("vue-spring-postgres");
        assertThat(importer.prepare(owner, project, first.id(), digest, "import-v1").operation().id()).isEqualTo(prepared.operation().id());
        assertThat(projects.get(owner, project.toString())).isEqualTo(before);
        assertThat(projects.get(owner, project.toString()).currentVersionId()).isEqualTo(second.id());
        assertThat(jdbc.queryForList("SELECT * FROM versions WHERE project_id=? ORDER BY version_no", project)).isEqualTo(legacy);
        assertThat(jdbc.queryForList("SELECT * FROM app_states WHERE project_id=? ORDER BY environment", project)).isEqualTo(states);
    }

    @Test void ownershipAndFrozenTemplateAreCheckedBeforeCreatingAnIntent() {
        var version = artifacts.save(owner, project.toString(), 0, null, artifact("only"));
        var importer = new LegacySourceImporter(snapshots, service);
        assertThatThrownBy(() -> importer.prepare(stranger, project, version.id(), snapshots.templateDigest(), "foreign"))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class);
        assertThatThrownBy(() -> importer.prepare(owner, project, version.id(), "0".repeat(64), "template-changed"))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE project_id=?", Integer.class, project)).isZero();
        assertThat(java.nio.file.Files.exists(temporary.resolve("source"))).isFalse();
    }

    private com.fasterxml.jackson.databind.JsonNode artifact(String name) {
        var source = json.createObjectNode().put("formatVersion", 1);
        source.putObject("manifest").put("title", name).put("summary", "Legacy import fixture")
                .put("runtime", "web-static-v1").put("stateSchemaVersion", 1).putObject("initialState").put("count", 0);
        source.putObject("files").put("view.html", "<h1>" + name + "</h1>").put("styles.css", "h1 {color:green}").put("main.js", "");
        return source;
    }
    private String text(SourceTree tree, String path) {
        return new String(tree.files().stream().filter(file -> file.path().equals(path)).findFirst().orElseThrow().content(), StandardCharsets.UTF_8);
    }
}
