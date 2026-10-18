package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceQueryIT extends SourceIntegrationSupport {
    @Test void emptyReadsNeverInitializeStorageAndPaginationIsBounded() {
        var query = query();
        assertThat(query.versions(owner, project, null, 100)).isEmpty();
        assertThatThrownBy(() -> query.snapshot(owner, project, UUID.randomUUID())).isInstanceOf(ApiException.class);
        for (int limit : new int[]{0, -1, 101})
            assertThatThrownBy(() -> query.versions(owner, project, null, limit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.versions(owner, project, 0L, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.versions(stranger, project, null, 100)).isInstanceOf(ApiException.class);
        assertThat(Files.exists(temporary.resolve("source"))).isFalse();
        assertThat(repository.state(project)).isNull();
    }

    @Test void readsImmutableHistoryWithKeysetPaginationAndRechecksCurrentOwner() {
        var first = publish(null, 0, "one");
        var second = publish(first.resultVersionId(), 1, "two");
        var query = query();
        assertThat(query.versions(owner, project, null, 1)).extracting(SourceVersion::id).containsExactly(second.resultVersionId());
        assertThat(query.versions(owner, project, 2L, 100)).extracting(SourceVersion::id).containsExactly(first.resultVersionId());
        assertThat(query.versions(owner, project, 1L, 100)).isEmpty();
        assertThat(query.file(owner, project, first.resultVersionId(), "README.md").content()).isEqualTo("one".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(query.tree(owner, project, first.resultVersionId())).singleElement().satisfies(entry -> {
            assertThat(entry.path()).isEqualTo("README.md");
            assertThat(entry.bytes()).isEqualTo(3);
            assertThat(entry.sha256()).hasSize(64);
        });
        assertThatThrownBy(() -> query.file(owner, project, first.resultVersionId(), "../.env"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.file(owner, project, first.resultVersionId(), "missing.txt"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("SOURCE_FILE_MISSING"));
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,?)", other, owner, "other-query-project");
        assertThatThrownBy(() -> query.snapshot(owner, other, first.resultVersionId())).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, project);
        assertThatThrownBy(() -> query.snapshot(owner, project, first.resultVersionId())).isInstanceOf(ApiException.class);
        assertThat(query.snapshot(stranger, project, first.resultVersionId()).version().id()).isEqualTo(first.resultVersionId());
        assertThat(repository.state(project).status()).isEqualTo("READY");
    }

    @Test void aMissingStorageRootIsNotRecreatedByReadsAndBlocksFurtherWrites() throws Exception {
        var operation = publish(null, 0, "one");
        environment.close();
        Files.move(temporary.resolve("source"), temporary.resolve("saved-source"));
        var root = temporary.toRealPath();
        environment = new ProjectSourceEnvironment(new MockEnvironment().withProperty("atoms.source.enabled", "true")
                .withProperty("atoms.source.root", root.resolve("source").toString())
                .withProperty("atoms.source.work-root", root.resolve("work").toString())
                .withProperty("atoms.source.git", "/usr/bin/git"));
        service = new ProjectSourceService(projects, repository, transactions, environment);
        assertThatThrownBy(() -> query().snapshot(owner, project, operation.resultVersionId()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("SOURCE_STORAGE_UNAVAILABLE"));
        assertThat(Files.exists(temporary.resolve("source"))).isFalse();
        assertThat(repository.state(project).status()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(repository.state(project).versionId()).isEqualTo(operation.resultVersionId());
    }

    private SourceQuery query() { return new SourceQuery(service, repository, environment); }
    private SourceOperation publish(UUID base, long revision, String text) {
        var tree = new ProjectSourcePolicy().validate(List.of(file("README.md", text)));
        var operation = service.admit(owner, project, new SourceRequest(base == null ? "INITIALIZE" : "ACCEPT",
                base, revision, null, null, null, null, tree.sha256()), "query-" + text);
        var completed = new SourceWorker(service, repository, environment).execute(operation, tree);
        assertThat(completed.status()).isEqualTo("COMMITTED");
        return completed;
    }
}
