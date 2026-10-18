package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceDiffIT extends SourceIntegrationSupport {
    @Test void bothComparedVersionsMustBelongToTheCurrentlyAuthorizedProject() {
        var worker = new SourceWorker(service, repository, environment);
        var firstTree = new ProjectSourcePolicy().validate(List.of(file("README.md", "one")));
        var first = worker.execute(new SourceInitializer(service).request(owner, project, firstTree, "first"), firstTree);
        var secondTree = new ProjectSourcePolicy().validate(List.of(file("README.md", "two")));
        var second = worker.execute(service.admit(owner, project, new SourceRequest("ACCEPT", first.resultVersionId(), 1,
                null, null, null, null, secondTree.sha256()), "second"), secondTree);
        var query = new SourceQuery(service, repository, environment);
        assertThat(query.diff(owner, project, first.resultVersionId(), second.resultVersionId()).changes()).hasSize(1);
        assertThatThrownBy(() -> query.diff(stranger, project, first.resultVersionId(), second.resultVersionId())).isInstanceOf(ApiException.class);
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,?)", other, owner, "other diff");
        assertThatThrownBy(() -> query.diff(owner, other, first.resultVersionId(), second.resultVersionId())).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, project);
        assertThatThrownBy(() -> query.diff(owner, project, first.resultVersionId(), second.resultVersionId())).isInstanceOf(ApiException.class);
        assertThat(query.diff(stranger, project, first.resultVersionId(), second.resultVersionId()).changes()).hasSize(1);
    }
}
