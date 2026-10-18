package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceRestorationIT extends SourceIntegrationSupport {
    private SourceQuery query() { return new SourceQuery(service, repository, environment); }
    private SourceTree tree(String text) { return new ProjectSourcePolicy().validate(List.of(file("README.md", text),
            file("backend/db/V001.sql", "-- Never executed by source storage\nDROP TABLE app_states;"))); }
    private SourceOperation initial(SourceTree tree) {
        return new SourceWorker(service, repository, environment).execute(
                new SourceInitializer(service).request(owner, project, tree, "initial"), tree);
    }

    @Test void restoringV1CreatesV3WithV2AsParentWithoutTouchingBusinessData() {
        var first = initial(tree("first"));
        var secondTree = tree("second");
        var second = new SourceWorker(service, repository, environment).execute(service.admit(owner, project,
                new SourceRequest("ACCEPT", first.resultVersionId(), 1, null, null, null, null, secondTree.sha256()), "second"), secondTree);
        for (String name : List.of("PREVIEW", "LIVE")) jdbc.update("""
                INSERT INTO app_states(project_id,environment,actor_id,value_json,revision,state_schema_version)
                VALUES (?,?,?,'{"score":42}',1,1)
                """, project, name, owner);
        var states = jdbc.queryForList("SELECT * FROM app_states WHERE project_id=? ORDER BY environment", project);
        var before = projects.get(owner, project.toString());
        var restore = new SourceRestoration(service, repository, query());
        var prepared = restore.prepare(owner, project, first.resultVersionId(), second.resultVersionId(), 2, "restore-first");
        var result = new SourceWorker(service, repository, environment).execute(prepared.operation(), prepared.tree());
        assertThat(result.status()).isEqualTo("COMMITTED");
        var saved = query().snapshot(owner, project, result.resultVersionId());
        assertThat(saved.version().versionNo()).isEqualTo(3);
        assertThat(saved.version().basedOnId()).isEqualTo(second.resultVersionId());
        assertThat(saved.version().restoredFromId()).isEqualTo(first.resultVersionId());
        assertThat(saved.tree().sha256()).isEqualTo(tree("first").sha256());
        assertThat(query().snapshot(owner, project, second.resultVersionId()).tree().sha256()).isEqualTo(secondTree.sha256());
        assertThat(query().versions(owner, project, null, 100)).hasSize(3);
        assertThat(restore.prepare(owner, project, first.resultVersionId(), second.resultVersionId(), 2, "restore-first").operation()).isEqualTo(result);
        assertThat(projects.get(owner, project.toString())).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM app_states WHERE project_id=? ORDER BY environment", project)).isEqualTo(states);
    }

    @Test void restorationChecksOwnerTargetAndBaselineAndCanBeCancelled() {
        var first = initial(tree("first"));
        var restore = new SourceRestoration(service, repository, query());
        assertThatThrownBy(() -> restore.prepare(stranger, project, first.resultVersionId(), first.resultVersionId(), 1, "foreign"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> restore.prepare(owner, project, UUID.randomUUID(), first.resultVersionId(), 1, "missing"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> restore.prepare(owner, project, first.resultVersionId(), first.resultVersionId(), 2, "stale"))
                .isInstanceOf(ApiException.class);
        var prepared = restore.prepare(owner, project, first.resultVersionId(), first.resultVersionId(), 1, "cancel");
        service.cancel(owner, project, prepared.operation().id());
        assertThat(new SourceWorker(service, repository, environment).execute(prepared.operation(), prepared.tree()).status()).isEqualTo("CANCELLED");
        assertThat(repository.state(project).revision()).isEqualTo(1);
    }
}
