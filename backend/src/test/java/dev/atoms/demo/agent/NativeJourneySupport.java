package dev.atoms.demo.agent;

import dev.atoms.demo.source.SourceAgentFixture;
import dev.atoms.demo.source.SourceTree;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

abstract class NativeJourneySupport extends AgentIntegrationSupport {
    @Autowired DataSource database;
    ProjectAgentService tasks;
    private AgentTaskRepository repository;
    private AgentTaskLifecycle lifecycle;

    @BeforeEach void nativeServices() {
        source.start(database);
        repository = new AgentTaskRepository(jdbc);
        tasks = new ProjectAgentService(settings, repository, projects, source.gateway(), transactions);
        lifecycle = new AgentTaskLifecycle(tasks, repository, projects, source.gateway(), transactions);
    }
    SourceAgentFixture.Base initialize() throws Exception {
        UUID project = source.createEmpty(owner);
        var initial = new ProjectAgentInitialization(tasks, projects, source.gateway())
                .initialize(owner, project, "native-journey").completion().get(30, TimeUnit.SECONDS);
        assertThat(initial.status()).isEqualTo("COMMITTED");
        assertThat(initial.templateId()).isEqualTo("full-project-v1");
        return new SourceAgentFixture.Base(project, initial.resultVersionId(), 1);
    }
    AgentWorker worker(CodingAgent agent) { return new AgentWorker(tasks, repository, lifecycle, source.gateway(), agent); }
    ProjectAgentService.Input input(String prompt, UUID version, long revision) {
        return new ProjectAgentService.Input(prompt, version.toString(), revision);
    }

    void twoRounds(CodingAgent agent, String evidenceKind) throws Exception {
        var base = initialize();
        UUID version = base.versionId();
        var original = source.snapshot(owner, base.projectId(), version);
        assertThat(original.sha256()).isEqualTo(new FullProjectTemplateCatalog().load().sha256());
        var previous = original;
        var worker = worker(agent);
        UUID lastTask = null;
        for (int round = 1; round <= 2; round++) {
            var task = tasks.submit(owner, base.projectId(), input(NativeProjectEdits.after(round), version, round), "round-" + round);
            assertThat(task.baseSourceVersionId()).isEqualTo(version);
            var saved = worker.execute(task.id());
            assertThat(saved.status()).as("native round %s: %s", round, saved.errorCode()).isEqualTo("SOURCE_SAVED");
            assertThat(saved.resultVersionId()).isNotEqualTo(version);
            assertThat(worker.execute(task.id())).isEqualTo(saved);
            var snapshot = source.snapshot(owner, base.projectId(), saved.resultVersionId());
            var changed = source.changedPaths(owner, base.projectId(), version, saved.resultVersionId());
            assertThat(changed).containsOnlyKeys(NativeProjectEdits.VUE, NativeProjectEdits.JAVA, NativeProjectEdits.sqlPath(round));
            assertThat(changed).containsEntry(NativeProjectEdits.VUE, "MODIFIED")
                    .containsEntry(NativeProjectEdits.JAVA, round == 1 ? "ADDED" : "MODIFIED")
                    .containsEntry(NativeProjectEdits.sqlPath(round), "ADDED");
            var files = index(snapshot);
            for (var file : previous.files()) if (!changed.containsKey(file.path())) {
                assertThat(files.get(file.path()).content()).as("unchanged %s", file.path()).isEqualTo(file.content());
                assertThat(files.get(file.path()).mode()).isEqualTo(file.mode());
            }
            assertThat(new String(files.get(NativeProjectEdits.VUE).content(), StandardCharsets.UTF_8)).contains(NativeProjectEdits.after(round));
            assertThat(new String(files.get(NativeProjectEdits.JAVA).content(), StandardCharsets.UTF_8)).isEqualTo(NativeProjectEdits.javaContent(round));
            assertThat(new String(files.get(NativeProjectEdits.sqlPath(round)).content(), StandardCharsets.UTF_8)).isEqualTo(NativeProjectEdits.sqlContent(round));
            assertThat(snapshot.files()).hasSize(original.files().size() + round + 1);
            assertStopped(task.id());
            assertThat(saved.liveReserved()).isFalse();
            assertCurrent(base.projectId(), saved.resultVersionId(), round + 1);
            assertThat(query().events(owner, base.projectId(), task.id(), 0, 100).events().stream()
                    .filter(event -> !event.kind().equals("USAGE")).map(event -> event.payload().get("status")))
                    .containsExactly("QUEUED", "PREPARING", "RUNNING", "VALIDATING", "ACCEPTING", "SOURCE_SAVED");
            System.out.printf("native_journey kind=%s task=%s base=%s version=%s tree=%s stopped=true%n",
                    evidenceKind, task.id(), version, saved.resultVersionId(), snapshot.sha256());
            version = saved.resultVersionId(); previous = snapshot; lastTask = task.id();
        }
        source.close();
        try (var reopened = new SourceAgentFixture(jdbc, projects, transactions, temporary.toRealPath())) {
            reopened.start(database);
            assertThat(reopened.snapshot(owner, base.projectId(), version).sha256()).isEqualTo(previous.sha256());
            assertThat(reopened.snapshot(owner, base.projectId(), base.versionId()).sha256()).isEqualTo(original.sha256());
            assertThat(query().list(owner, base.projectId(), 10)).hasSize(2).allSatisfy(task -> assertThat(task.status()).isEqualTo("SOURCE_SAVED"));
            assertThat(new ProjectAgentService(settings, new AgentTaskRepository(jdbc), projects, reopened.gateway(), transactions)
                    .get(owner, base.projectId(), lastTask).resultVersionId()).isEqualTo(version);
        }
    }
    void assertCurrent(UUID project, UUID version, int count) {
        assertThat(jdbc.queryForObject("SELECT current_source_version_id FROM project_sources WHERE project_id=?", UUID.class, project)).isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT source_revision FROM project_sources WHERE project_id=?", Integer.class, project)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, project)).isEqualTo(count);
    }
    void assertStopped(UUID task) {
        assertThat(jdbc.queryForObject("SELECT status FROM source_edit_leases WHERE task_id=?", String.class, task)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT execution_pending FROM source_edit_leases WHERE task_id=?", Boolean.class, task)).isFalse();
        assertThat(jdbc.queryForObject("SELECT stopped_sha256 FROM source_edit_leases WHERE task_id=?", String.class, task)).matches("[a-f0-9]{64}");
        assertThat(tasks.handle(task)).isNull();
    }
    private AgentQuery query() { return new AgentQuery(settings, projects, new AgentTaskRepository(jdbc), new AgentEventRepository(jdbc), transactions); }
    private static Map<String, SourceTree.File> index(SourceTree tree) {
        return tree.files().stream().collect(Collectors.toMap(SourceTree.File::path, Function.identity()));
    }
}
