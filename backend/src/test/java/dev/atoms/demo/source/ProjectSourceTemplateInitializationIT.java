package dev.atoms.demo.source;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceTemplateInitializationIT extends SourceIntegrationSupport {
    @org.springframework.beans.factory.annotation.Autowired dev.atoms.demo.artifact.ArtifactService artifacts;
    @org.springframework.beans.factory.annotation.Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    @Test void existingLegacyApplicationMustUseExplicitImport() {
        var legacy = artifacts.save(owner, project.toString(), 0, null, legacyArtifact());
        var before = jdbc.queryForList("SELECT * FROM versions WHERE project_id=?", project);
        var tree = ProjectSourceInitializationIT.tree();
        assertThatThrownBy(() -> new SourceInitializer(service).request(owner, project, tree, "full-project-v1", tree.sha256(), "not-import"))
                .isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_TEMPLATE_REQUIRES_IMPORT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE project_id=?", Integer.class, project)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM versions WHERE project_id=?", project)).isEqualTo(before);
        assertThat(projects.get(owner, project.toString()).currentVersionId()).isEqualTo(legacy.id());
    }

    @Test void legacyPublicationDuringInitializationPreventsTheNativeFirstVersionFromPublishing() {
        var tree = ProjectSourceInitializationIT.tree();
        var operation = new SourceInitializer(service).request(owner, project, tree, "full-project-v1", tree.sha256(), "race-legacy");
        var worker = new SourceWorker(service, repository, environment, stage -> {
            if (stage == SourceWorker.Stage.AFTER_GIT) artifacts.save(owner, project.toString(), 0, null, legacyArtifact());
        });
        var result = worker.execute(operation, tree);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("SOURCE_TEMPLATE_REQUIRES_IMPORT");
        assertThat(repository.state(project).versionId()).isNull();
        assertThat(projects.get(owner, project.toString()).currentVersionId()).isNotNull();
    }

    @Test void originIsPersistedWithFirstVersionAndSameKeyIsIdempotent() {
        var tree = ProjectSourceInitializationIT.tree();
        var initializer = new SourceInitializer(service);
        var operation = initializer.request(owner, project, tree, "full-project-v1", tree.sha256(), "template-initial");
        var result = new SourceWorker(service, repository, environment).execute(operation, tree);
        assertThat(result.status()).isEqualTo("COMMITTED");
        assertThat(result.templateId()).isEqualTo("full-project-v1");
        assertThat(result.templateDigest()).isEqualTo(tree.sha256());
        var version = repository.version(project, result.resultVersionId());
        assertThat(version.templateId()).isEqualTo("full-project-v1");
        assertThat(version.templateDigest()).isEqualTo(tree.sha256());
        assertThat(version.legacyVersionId()).isNull();
        assertThat(initializer.request(owner, project, tree, "full-project-v1", tree.sha256(), "template-initial").id()).isEqualTo(result.id());
        assertThatThrownBy(() -> initializer.request(owner, project, tree, "another-template", tree.sha256(), "template-initial"))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class);
        assertThat(projects.get(owner, project.toString()).currentVersionId()).isNull();
    }

    @Test void falseOrIncompleteOriginCannotCreateAnIntent() {
        var tree = ProjectSourceInitializationIT.tree();
        var initializer = new SourceInitializer(service);
        assertThatThrownBy(() -> initializer.request(owner, project, tree, "full-project-v1", "0".repeat(64), "wrong-digest"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> initializer.request(owner, project, tree, null, tree.sha256(), "missing-id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE project_id=?", Integer.class, project)).isZero();
        assertThat(temporary.resolve("source")).doesNotExist();
    }

    private com.fasterxml.jackson.databind.JsonNode legacyArtifact() {
        var source = json.createObjectNode().put("formatVersion", 1);
        source.putObject("manifest").put("title", "Existing application").put("summary", "Explicit import required")
                .put("runtime", "web-static-v1").put("stateSchemaVersion", 1).putObject("initialState").put("count", 0);
        source.putObject("files").put("view.html", "<h1>Existing application</h1>").put("styles.css", "").put("main.js", "");
        return source;
    }
}
