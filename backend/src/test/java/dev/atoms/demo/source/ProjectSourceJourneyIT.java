package dev.atoms.demo.source;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceJourneyIT extends SourceIntegrationSupport {
    @Autowired DataSource dataSource;

    @Test void initializesIteratesTwiceRestoresAndReopensThroughOneCoordinator() throws Exception {
        var initial = new ProjectSourcePolicy().validate(List.of(file("frontend/App.vue", "<template>first</template>"),
                file("backend/App.java", "class App {}"), file("backend/db/V001.sql", "create table task(id bigint);"),
                file("README.md", "documentation\n".repeat(11000))));
        assertThat(initial.totalBytes()).isGreaterThan(120 << 10);
        UUID first, latest;
        try (var coordinator = new SourceCoordinator(service, repository, environment, dataSource)) {
            coordinator.start();
            first = coordinator.initialize(owner, project, initial, "journey-initial").completion().get(60, TimeUnit.SECONDS).resultVersionId();
            latest = first;
            var query = new SourceQuery(service, repository, environment);
            var workspaces = new SourceWorkspaceStore(service, query, environment);
            var candidates = new SourceCandidateStore(workspaces, environment);
            var acceptance = new SourceAcceptance(service, candidates);
            for (int revision = 1; revision <= 2; revision++) {
                var work = workspaces.prepare(owner, project, latest, revision, UUID.randomUUID());
                var directory = workspaces.checked(work);
                Files.writeString(directory.resolve("frontend/App.vue"), "<template>iteration " + revision + "</template>");
                Files.writeString(directory.resolve("backend/App.java"), "class App { int value() { return " + revision + "; } }");
                Files.writeString(directory.resolve("backend/db/V00" + (revision + 1) + ".sql"), "-- Source only, never executed\nselect " + revision + ";");
                var candidate = candidates.seal(work);
                var prepared = acceptance.prepare(owner, project, candidate, "iteration-" + revision);
                assertThatThrownBy(() -> coordinator.submit(stranger, project, prepared.operation().id(), prepared.tree()))
                        .isInstanceOf(dev.atoms.demo.common.ApiException.class);
                var submitted = coordinator.submit(owner, project, prepared.operation().id(), prepared.tree());
                var result = submitted.completion().get(60, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo("COMMITTED");
                assertThat(repository.version(project, result.resultVersionId()).basedOnId()).isEqualTo(latest);
                latest = result.resultVersionId();
                var duplicate = acceptance.prepare(owner, project, candidate, "iteration-" + revision);
                assertThat(coordinator.submit(owner, project, duplicate.operation().id(), duplicate.tree())
                        .completion().get(10, TimeUnit.SECONDS)).isEqualTo(result);
            }
            var prepared = new SourceRestoration(service, repository, query).prepare(owner, project, first, latest, 3, "restore");
            var restored = coordinator.submit(owner, project, prepared.operation().id(), prepared.tree()).completion().get(60, TimeUnit.SECONDS);
            assertThat(restored.status()).isEqualTo("COMMITTED");
            latest = restored.resultVersionId();
            assertThat(query.snapshot(owner, project, latest).tree().sha256()).isEqualTo(initial.sha256());
            assertThat(query.versions(owner, project, null, 100)).hasSize(4);
        }
        environment.close();
        environment = new ProjectSourceEnvironment(sourceConfiguration);
        service = new ProjectSourceService(projects, repository, transactions, environment);
        try (var restarted = new SourceCoordinator(service, repository, environment, dataSource)) {
            restarted.start();
            assertThat(restarted.isRunning()).isTrue();
            var query = new SourceQuery(service, repository, environment);
            assertThat(repository.state(project).versionId()).isEqualTo(latest);
            for (var version : query.versions(owner, project, null, 100))
                assertThat(query.snapshot(owner, project, version.id()).tree().sha256()).isEqualTo(version.treeSha256());
            assertThat(query.snapshot(owner, project, first).tree().sha256()).isEqualTo(initial.sha256());
        }
    }
}
