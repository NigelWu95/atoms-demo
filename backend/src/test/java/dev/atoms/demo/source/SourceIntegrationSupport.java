package dev.atoms.demo.source;

import dev.atoms.demo.project.ProjectService;
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
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
abstract class SourceIntegrationSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ProjectService projects;
    @Autowired SourceRepository repository;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID(), project = UUID.randomUUID();
    ProjectSourceEnvironment environment;
    MockEnvironment sourceConfiguration;
    ProjectSourceService service;

    @BeforeEach void setup() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("atoms_demo_test");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("atoms_test");
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        jdbc.update("INSERT INTO guest_users(id) VALUES (?),(?)", owner, stranger);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,?)", project, owner, "source-integration");
        Path root = temporary.toRealPath();
        sourceConfiguration = new MockEnvironment().withProperty("atoms.source.enabled", "true")
                .withProperty("atoms.source.root", root.resolve("source").toString())
                .withProperty("atoms.source.work-root", root.resolve("work").toString()).withProperty("atoms.source.git", "/usr/bin/git")
                .withProperty("atoms.source.python", ProjectSourceFileProcessTest.python().toString());
        environment = new ProjectSourceEnvironment(sourceConfiguration);
        service = new ProjectSourceService(projects, repository, transactions, environment);
    }

    @AfterEach void finishOnlyThisTestsPendingEvidence() throws Exception {
        if (environment != null) environment.close();
        jdbc.update("""
                UPDATE source_operations SET status='FAILED',error_code='SOURCE_TEST_FINISHED',finished_at=now()
                WHERE project_id=? AND status IN ('PREPARING','CANDIDATE_READY','NEEDS_RECONCILIATION')
                """, project);
    }
}
