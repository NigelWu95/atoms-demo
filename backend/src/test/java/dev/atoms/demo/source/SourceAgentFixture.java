package dev.atoms.demo.source;

import dev.atoms.demo.project.ProjectService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Test-only cross-module fixture: production storage internals stay package-private. */
public final class SourceAgentFixture implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final SourceRepository repository;
    private final ProjectSourceService source;
    private final ProjectSourceEnvironment environment;
    private final ProjectSourceEditGateway gateway;
    private final List<UUID> projects = new ArrayList<>();
    private SourceCoordinator coordinator;

    public SourceAgentFixture(JdbcTemplate jdbc, ProjectService projects, PlatformTransactionManager transactions, Path root) throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("atoms_demo_test");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("atoms_test");
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        this.jdbc = jdbc;
        var config = new MockEnvironment().withProperty("atoms.source.enabled", "true")
                .withProperty("atoms.source.root", root.resolve("source").toString())
                .withProperty("atoms.source.work-root", root.resolve("work").toString())
                .withProperty("atoms.source.git", "/usr/bin/git")
                .withProperty("atoms.source.python", ProjectSourceFileProcessTest.python().toString());
        this.environment = new ProjectSourceEnvironment(config);
        this.repository = new SourceRepository(jdbc);
        this.source = new ProjectSourceService(projects, repository, transactions, environment);
        this.gateway = new ProjectSourceEditGateway(source, repository, environment, transactions,
                (java.util.function.Supplier<SourceCoordinator>) () -> coordinator);
    }

    public record Base(UUID projectId, UUID versionId, long revision) {}
    public UUID createEmpty(UUID owner) {
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,?)", project, owner, "agent-test");
        projects.add(project);
        return project;
    }
    public Base create(UUID owner) {
        UUID project = createEmpty(owner);
        var tree = new ProjectSourcePolicy().validate(List.of(
                ProjectSourceInitializationIT.file("frontend/App.vue", "<template>one</template>"),
                ProjectSourceInitializationIT.file("backend/App.java", "class App {}"),
                ProjectSourceInitializationIT.file("backend/db/V001.sql", "create table items(id bigint);"),
                ProjectSourceInitializationIT.file("README.md", "unchanged")));
        var result = new SourceWorker(source, repository, environment).execute(
                new SourceInitializer(source).request(owner, project, tree, "initial"), tree);
        assertThat(result.status()).isEqualTo("COMMITTED");
        return new Base(project, result.resultVersionId(), 1);
    }
    public ProjectSourceEditGateway gateway() { return gateway; }
    public ProjectSourceReadGateway readGateway() {
        return new ProjectSourceReadGateway(source, repository, environment,
                (java.util.function.Supplier<SourceCoordinator>) () -> coordinator);
    }
    public ProjectSourceEnvironment environment() { return environment; }
    public Base accept(UUID owner, Base base, SourceTree tree) {
        var intent = source.admit(owner, base.projectId(), new SourceRequest("ACCEPT", base.versionId(), base.revision(),
                null, null, null, null, tree.sha256()), "fixture-accept-" + base.revision());
        var result = new SourceWorker(source, repository, environment).execute(intent, tree);
        assertThat(result.status()).isEqualTo("COMMITTED");
        return new Base(base.projectId(), result.resultVersionId(), base.revision() + 1);
    }
    public void start(DataSource database) {
        start(database, stage -> {});
    }
    public void start(DataSource database, java.util.function.Consumer<String> observer) {
        coordinator = new SourceCoordinator(source, repository, environment, database, stage -> observer.accept(stage.name())); coordinator.start();
        assertThat(coordinator.isRunning()).isTrue();
    }
    public byte[] file(UUID owner, UUID project, UUID version, String path) {
        return new SourceQuery(source, repository, environment).file(owner, project, version, path).content();
    }
    public SourceTree snapshot(UUID owner, UUID project, UUID version) {
        return new SourceQuery(source, repository, environment).snapshot(owner, project, version).tree();
    }
    public java.util.Map<String, String> changedPaths(UUID owner, UUID project, UUID before, UUID after) {
        return new SourceQuery(source, repository, environment).diff(owner, project, before, after).changes().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(SourceDiff.Change::path, SourceDiff.Change::kind));
    }
    @Override public void close() throws Exception {
        if (coordinator != null) coordinator.close();
        for (var project : projects) {
            // Retain audit rows. Only this test's never-running/confirmed-stopped work can be settled.
            jdbc.update("""
                    UPDATE source_operations SET status='FAILED',error_code='SOURCE_TEST_FINISHED',finished_at=now()
                    WHERE project_id=? AND NOT execution_pending AND status IN ('PREPARING','CANDIDATE_READY')
                    """, project);
        }
        environment.close();
    }
    public List<UUID> projectIds() { return List.copyOf(projects); }
}
