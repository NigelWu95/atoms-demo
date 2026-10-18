package dev.atoms.demo.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class ProjectAgentWorkerIT extends AgentIntegrationSupport {
    @Autowired DataSource database;
    private ProjectAgentService service;
    private AgentTaskRepository repository;
    private AgentTaskLifecycle lifecycle;
    @BeforeEach void workerSupport() {
        source.start(database);
        repository = new AgentTaskRepository(jdbc);
        service = new ProjectAgentService(settings, repository, projects, source.gateway(), transactions);
        lifecycle = new AgentTaskLifecycle(service, repository, projects, source.gateway(), transactions);
    }

    @Test void twoRealEditingProcessesCreateSuccessiveGitVersionsAndRetriesDoNotRunAgain() {
        var base = source.create(owner); var adapter = new FixtureAgent("project-edit");
        var worker = new AgentWorker(service, repository, lifecycle, source.gateway(), adapter);
        var first = service.submit(owner, base.projectId(), new ProjectAgentService.Input("first", base.versionId().toString(), 1), "first");
        var saved = worker.execute(first.id());
        assertThat(saved.status()).isEqualTo("SOURCE_SAVED");
        assertThat(worker.execute(first.id())).isEqualTo(saved);
        assertThat(adapter.calls.get()).isEqualTo(1);
        assertThat(source.file(owner, base.projectId(), saved.resultVersionId(), "frontend/App.vue"))
                .isEqualTo("<template>round-1</template>".getBytes(StandardCharsets.UTF_8));
        var next = service.submit(owner, base.projectId(), new ProjectAgentService.Input("second", saved.resultVersionId().toString(), 2), "second");
        var second = worker.execute(next.id());
        assertThat(second.status()).isEqualTo("SOURCE_SAVED");
        assertThat(adapter.calls.get()).isEqualTo(2);
        for (String unchanged : new String[] {"README.md", "backend/db/V001.sql", "backend/db/V002.sql"}) {
            assertThat(source.file(owner, base.projectId(), second.resultVersionId(), unchanged))
                    .isEqualTo(source.file(owner, base.projectId(), saved.resultVersionId(), unchanged));
        }
        assertThat(new String(source.file(owner, base.projectId(), second.resultVersionId(), "backend/App.java"), StandardCharsets.UTF_8))
                .contains("return 2");
        assertThat(source.file(owner, base.projectId(), second.resultVersionId(), "backend/db/V003.sql")).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(3);
        assertThat(service.handle(second.id())).isNull();
        assertThat(repository.get(second.id()).liveReserved()).isFalse();
    }

    @Test void noChangeIsExplicitAndDoesNotCreateADuplicateVersion() {
        var base = source.create(owner); var adapter = new FixtureAgent("success");
        var task = service.submit(owner, base.projectId(), new ProjectAgentService.Input("no changes", base.versionId().toString(), 1), "none");
        var result = new AgentWorker(service, repository, lifecycle, source.gateway(), adapter).execute(task.id());
        assertThat(result.status()).isEqualTo("NO_CHANGES");
        assertThat(result.resultVersionId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(1);
        assertThat(service.handle(task.id())).isNull();
    }

    @Test void invalidFilesAndIncompleteProtocolKeepOldVersionAndReleaseOnlyAfterProcessStops() {
        for (String mode : new String[] {"invalid-source", "truncated"}) {
            var base = source.create(owner); var adapter = new FixtureAgent(mode);
            var task = service.submit(owner, base.projectId(), new ProjectAgentService.Input("test", base.versionId().toString(), 1), mode);
            var result = new AgentWorker(service, repository, lifecycle, source.gateway(), adapter).execute(task.id());
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.errorCode()).startsWith(mode.equals("truncated") ? "AGENT_" : "SOURCE_");
            assertThat(jdbc.queryForObject("SELECT current_source_version_id FROM project_sources WHERE project_id=?", UUID.class, base.projectId()))
                    .isEqualTo(base.versionId());
            assertThat(jdbc.queryForObject("SELECT status FROM source_edit_leases WHERE task_id=?", String.class, task.id())).isEqualTo("RELEASED");
            assertThat(adapter.calls.get()).isEqualTo(1);
        }
    }

    private final class FixtureAgent implements CodingAgent {
        final AtomicInteger calls = new AtomicInteger();
        final String mode;
        FixtureAgent(String mode) { this.mode = mode; }
        @Override public Session prepare(AgentTask task, Path work, AgentDeadline deadline) {
            return new Session() {
                @Override public boolean paid() { return false; }
                @Override public Result execute(UUID execution, java.util.function.BooleanSupplier cancelled) throws IOException {
                    int round = calls.incrementAndGet();
                    var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                            Path.of("target/test-classes").toAbsolutePath().toString(), AgentChildFixture.class.getName(), mode,
                            temporary.resolve(task.id()+".pid").toString(), Integer.toString(round)).directory(work.toFile());
                    command.environment().clear();
                    try {
                        var result = new AgentProcess().run(command, "fixture", deadline, cancelled, task.id(), execution);
                        return new Result(result.summary(), result.stopped());
                    } catch (AgentProcess.Failure error) { throw new CodingAgent.Failure(error.getMessage(), error.stopped()); }
                }
                @Override public void close() {}
            };
        }
    }
}
