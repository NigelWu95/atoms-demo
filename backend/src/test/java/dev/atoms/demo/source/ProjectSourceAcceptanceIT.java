package dev.atoms.demo.source;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceAcceptanceIT extends SourceIntegrationSupport {
    private SourceQuery query() { return new SourceQuery(service, repository, environment); }
    private SourceTree initial() { return new ProjectSourcePolicy().validate(List.of(file("frontend/App.vue", "<template>one</template>"),
            file("backend/App.java", "class App {}"), file("backend/db/V001.sql", "create table a(id bigint);"), file("README.md", "unchanged"))); }
    private UUID initialize() {
        var tree = initial();
        return new SourceWorker(service, repository, environment).execute(
                new SourceInitializer(service).request(owner, project, tree, "initial"), tree).resultVersionId();
    }

    @Test void commitsFrontendBackendAndMigrationChangesWhileKeepingHistoryAndIdempotency() throws Exception {
        UUID base = initialize();
        var workspaces = new SourceWorkspaceStore(service, query(), environment);
        var candidates = new SourceCandidateStore(workspaces, environment);
        var acceptance = new SourceAcceptance(service, candidates);
        var work = workspaces.prepare(owner, project, base, 1, UUID.randomUUID());
        var directory = workspaces.checked(work);
        Files.writeString(directory.resolve("frontend/App.vue"), "<template>two</template>");
        Files.writeString(directory.resolve("backend/App.java"), "class App { int count() { return 2; } }");
        Files.writeString(directory.resolve("backend/db/V002.sql"), "alter table a add column name text;");
        var candidate = candidates.seal(work);
        var prepared = acceptance.prepare(owner, project, candidate, "second");
        var worker = new SourceWorker(service, repository, environment);
        var result = worker.execute(prepared.operation(), prepared.tree());
        assertThat(result.status()).isEqualTo("COMMITTED");
        var saved = query().snapshot(owner, project, result.resultVersionId());
        assertThat(saved.version().basedOnId()).isEqualTo(base);
        assertThat(saved.tree().sha256()).isEqualTo(candidate.treeSha256());
        assertThat(query().snapshot(owner, project, base).tree().sha256()).isEqualTo(initial().sha256());
        assertThat(query().file(owner, project, result.resultVersionId(), "README.md").content()).isEqualTo(
                query().file(owner, project, base, "README.md").content());
        assertThat(acceptance.prepare(owner, project, candidate, "second").operation()).isEqualTo(result);
        assertThat(repository.state(project).revision()).isEqualTo(2);
        assertThat(query().versions(owner, project, null, 100)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM projects WHERE id=?", UUID.class, project)).isNull();
    }

    @Test void sameBaseConcurrentSubmissionsOnlyPublishOneVersion() throws Exception {
        UUID base = initialize();
        var workspaces = new SourceWorkspaceStore(service, query(), environment);
        var candidates = new SourceCandidateStore(workspaces, environment);
        var acceptance = new SourceAcceptance(service, candidates);
        var one = workspaces.prepare(owner, project, base, 1, UUID.randomUUID());
        var two = workspaces.prepare(owner, project, base, 1, UUID.randomUUID());
        Files.writeString(workspaces.checked(one).resolve("README.md"), "one");
        Files.writeString(workspaces.checked(two).resolve("README.md"), "two");
        var firstCandidate = candidates.seal(one);
        var secondCandidate = candidates.seal(two);
        var first = acceptance.prepare(owner, project, firstCandidate, "one");
        var second = acceptance.prepare(owner, project, secondCandidate, "two");
        assertThatThrownBy(() -> acceptance.prepare(owner, project, secondCandidate, "one"))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class);
        var worker = new SourceWorker(service, repository, environment);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var a = threads.submit(() -> worker.execute(first.operation(), first.tree()));
            var b = threads.submit(() -> worker.execute(second.operation(), second.tree()));
            var results = List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
            assertThat(results).extracting(SourceOperation::status).containsExactlyInAnyOrder("COMMITTED", "FAILED");
            assertThat(results.stream().filter(value -> value.status().equals("FAILED")).findFirst().orElseThrow().errorCode())
                    .isEqualTo("SOURCE_BASE_CONFLICT");
            assertThat(repository.state(project).revision()).isEqualTo(2);
            assertThat(repository.state(project).status()).isEqualTo("READY");
        } finally { threads.shutdownNow(); }
    }

    @Test void changedOwnershipBeforePublicationCannotPromoteTheCandidate() throws Exception {
        UUID base = initialize();
        var workspaces = new SourceWorkspaceStore(service, query(), environment);
        var candidates = new SourceCandidateStore(workspaces, environment);
        var work = workspaces.prepare(owner, project, base, 1, UUID.randomUUID());
        var candidate = candidates.seal(work);
        var acceptance = new SourceAcceptance(service, candidates);
        assertThatThrownBy(() -> acceptance.prepare(stranger, project, candidate, "foreign"))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class);
        var prepared = acceptance.prepare(owner, project, candidate, "changed-owner");
        var result = new SourceWorker(service, repository, environment, stage -> {
            if (stage == SourceWorker.Stage.AFTER_GIT) jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, project);
        }).execute(prepared.operation(), prepared.tree());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("SOURCE_ACCESS_CHANGED");
        assertThat(repository.state(project).versionId()).isEqualTo(base);
        assertThat(query().versions(stranger, project, null, 100)).hasSize(1);
    }
}
