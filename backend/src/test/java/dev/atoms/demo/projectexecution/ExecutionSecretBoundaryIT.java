package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.project.ProjectService;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Real output/report canaries through worker, persisted events and query DTOs; controlled tool artifacts only. */
@Tag("project-execution-contract") @ActiveProfiles("test") @ExtendWith(OutputCaptureExtension.class)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionSecretBoundaryIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    ExecutionBuildWorkerIT fixture;

    @BeforeEach void setup() throws Exception {
        fixture = new ExecutionBuildWorkerIT(); fixture.jdbc = jdbc; fixture.projects = projects;
        fixture.transactions = transactions; fixture.temporary = temporary; fixture.setup();
    }
    @AfterEach void close() throws Exception { if (fixture != null) fixture.close(); }

    @Test void outputAndReportCanariesDoNotEnterSourceArtifactsPersistedEventsOrQueryPayloads(CapturedOutput output) throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var query = new ExecutionQuery(new ExecutionSettings(fixture.config), jdbc, projects, transactions);
        var source = fixture.source.snapshot(fixture.owner, fixture.base.projectId(), fixture.base.versionId());
        for (String mode : List.of("success", "lint-failure")) {
            fixture.mode = mode; fixture.shared = null; fixture.executed.clear();
            var result = fixture.run(fixture.queued(), jdbc);
            assertThat(result.status()).isEqualTo(mode.equals("success") ? "SUCCEEDED" : "FAILED");
            assertThat(fixture.row(result)).containsEntry("execution_pending", false).containsEntry("worker_slot", null);
            assertThat(fixture.proofs.get(result.id())).isNotNull();
            assertThat(jdbc.queryForList("SELECT payload::text FROM execution_build_events WHERE build_id=? ORDER BY seq", String.class, result.id()).toString())
                    .doesNotContain("CANARY", "APP_DB_PASSWORD", "jdbc:", fixture.root.toString());
            String events = json.writeValueAsString(query.events(fixture.owner, result.projectId(), ExecutionQuery.Kind.BUILD, result.id(), null, 100));
            assertThat(events).doesNotContain("CANARY", "APP_DB_PASSWORD", "jdbc:", fixture.root.toString());
            assertThat(json.writeValueAsString(query.tasks(fixture.owner, result.projectId(), ExecutionQuery.Kind.BUILD, null, 100)))
                    .doesNotContain("CANARY", "APP_DB_PASSWORD", "jdbc:", fixture.root.toString());
            var current = fixture.source.snapshot(fixture.owner, result.projectId(), result.sourceVersionId());
            assertThat(current.sha256()).isEqualTo(source.sha256());
            for (var file : current.files()) assertThat(new String(file.content(), StandardCharsets.UTF_8)).doesNotContain("PRIVATE_OUTPUT_CANARY");
            if (mode.equals("success")) {
                Path work = fixture.root.resolve("execution/builds/" + result.id() + "/project");
                assertThat(Files.readString(work.resolve("frontend/test-results/unit.json"))).contains("CANARY");
                assertThat(Files.readString(work.resolve("backend/target/surefire-reports/TEST-Example.xml"))).contains("PRIVATE_CANARY");
                Path artifact = fixture.root.resolve("execution/artifacts/" + result.id());
                assertThat(Files.readString(artifact.resolve("manifest.json"))).doesNotContain("CANARY", "jdbc:", fixture.root.toString());
                assertThat(Files.readString(artifact.resolve("app.jar"))).doesNotContain("CANARY");
            }
        }
        assertThat(output.getAll()).doesNotContain("PRIVATE_OUTPUT_CANARY", "PRIVATE_CANARY");
    }
}
