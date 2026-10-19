package dev.atoms.demo.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.engineering.EngineeringBundle;
import dev.atoms.demo.engineering.EngineeringSnapshotService;
import dev.atoms.demo.project.ProjectService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(properties={"atoms.agent.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ProjectAgentDisabledCompatibilityIT {
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired EngineeringSnapshotService snapshots;
    @Autowired ObjectMapper json;
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID();

    @BeforeEach void createLegacyProject() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("atoms_demo_test");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("atoms_test");
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,?)", project, owner, "agent-disabled-compatibility");
    }

    @Test void disabledSpringWiringRejectsAgentEntryPointsWithoutStartingBackgroundWork() {
        assertThat(context.getBean(AgentSettings.class).enabled()).isFalse();
        assertThat(context.getBeansOfType(AgentCoordinator.class)).isEmpty();
        assertThat(context.getBeansOfType(AgentRetention.class)).isEmpty();
        var tasks = context.getBean(ProjectAgentService.class);
        UUID task = UUID.randomUUID();
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> calls = List.of(
                () -> tasks.submit(owner, project, null, "disabled"),
                () -> tasks.get(owner, project, task),
                () -> tasks.cancel(owner, project, task),
                () -> context.getBean(ProjectAgentInitialization.class).initialize(owner, project, "disabled"),
                () -> context.getBean(AgentQuery.class).list(owner, project, 100),
                () -> context.getBean(AgentQuery.class).events(owner, project, task, 0, 100),
                () -> context.getBean(AgentEnvironment.class).prepare(task));
        for (var call : calls) assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(503);
            assertThat(error.code()).isEqualTo("AGENT_DISABLED");
        });
        assertNoNewModuleRecords();
    }

    @Test void disabledAgentLeavesLegacyVersionsRestorationAndEngineeringExportWorking() throws Exception {
        var artifact = json.createObjectNode().put("formatVersion", 1);
        artifact.putObject("manifest").put("title", "Legacy application").put("summary", "Agent disabled fixture")
                .put("runtime", "web-static-v1").put("stateSchemaVersion", 1).putObject("initialState").put("count", 0);
        var files = artifact.putObject("files").put("view.html", "<h1>first</h1>")
                .put("styles.css", "h1 {color:green}").put("main.js", "");
        var first = artifacts.save(owner, project.toString(), 0, null, artifact);
        files.put("view.html", "<h1>second</h1>");
        artifacts.save(owner, project.toString(), 1, first.id(), artifact);
        assertThat(artifacts.restore(owner, project.toString(), json.createObjectNode()
                .put("sourceVersionId", first.id().toString()).put("expectedProjectRevision", 2), "disabled-agent-restore").status())
                .isEqualTo(201);
        var current = artifacts.get(owner, project.toString(), projects.get(owner, project.toString()).currentVersionId().toString());
        assertThat(current.restoredFromId()).isEqualTo(first.id());
        assertThat(current.artifact()).isEqualTo(first.artifact());
        assertThat(snapshots.assemble(current, snapshots.templateDigest()).bundle().files())
                .extracting(EngineeringBundle.File::path)
                .contains("application/view.html", "backend/pom.xml", "frontend/src/App.vue");
        assertNoNewModuleRecords();
    }

    private void assertNoNewModuleRecords() {
        for (var table : List.of("agent_tasks", "source_edit_leases", "project_sources", "source_versions", "source_operations"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE project_id=?", Integer.class, project))
                    .as(table).isZero();
    }
}
