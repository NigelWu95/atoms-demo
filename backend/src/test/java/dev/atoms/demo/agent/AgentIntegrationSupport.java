package dev.atoms.demo.agent;

import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.SourceAgentFixture;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

@ActiveProfiles("test")
@SpringBootTest(properties={"atoms.agent.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
abstract class AgentIntegrationSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
    SourceAgentFixture source;
    MockEnvironment configuration;
    AgentSettings settings;

    @BeforeEach void fixture() throws Exception {
        source = new SourceAgentFixture(jdbc, projects, transactions, temporary.toRealPath());
        jdbc.update("INSERT INTO guest_users(id) VALUES (?),(?)", owner, stranger);
        configuration = new MockEnvironment().withProperty("atoms.agent.enabled", "true")
                .withProperty("atoms.agent.cli", temporary.resolve("not-started-cli").toString())
                .withProperty("atoms.agent.state-root", temporary.resolve("agent-state").toString())
                .withProperty("atoms.agent.maintainer-ids", owner.toString());
        settings = new AgentSettings(configuration);
    }

    @AfterEach void closeFixture() throws Exception {
        if (source == null) return;
        source.close();
        for (var project : source.projectIds()) {
            jdbc.update("""
                    UPDATE agent_tasks SET status='FAILED',error_code='AGENT_TEST_FINISHED',finished_at=now()
                    WHERE project_id=? AND status NOT IN ('SOURCE_SAVED','NO_CHANGES','FAILED','CANCELLED','TIMED_OUT')
                    AND NOT EXISTS (SELECT 1 FROM source_edit_leases e WHERE e.id=edit_lease_id AND e.execution_pending)
                    """, project);
            jdbc.update("""
                    UPDATE source_edit_leases SET status='RELEASED',released_at=now()
                    WHERE project_id=? AND status<>'RELEASED' AND NOT execution_pending
                    AND NOT EXISTS (SELECT 1 FROM source_operations s WHERE s.edit_lease_id=source_edit_leases.id
                        AND (s.status NOT IN ('COMMITTED','FAILED','CANCELLED') OR s.execution_pending))
                    """, project);
        }
    }
}
