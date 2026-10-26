package dev.atoms.demo.projectexecution;

import dev.atoms.demo.project.ProjectService;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Combined worker/storage failures using real PG, Git and owned JVMs, not native npm/Maven builds. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionFailureMatrixIT {
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

    @Test void changingInputsAfterAnObservedProbeFailsOnlyTheNewBuild() throws Exception {
        var saved = successful(); var original = fixture.row(saved);
        for (String kind : List.of("source", "lock", "tool", "policy")) {
            fixture.shared = null; fixture.executed.clear(); var build = fixture.queued();
            Path file = switch (kind) {
                case "source" -> fixture.root.resolve("execution/builds/" + build.id() + "/project/frontend/src/App.vue");
                case "lock" -> fixture.root.resolve("execution/builds/" + build.id() + "/project/frontend/package-lock.json");
                case "tool" -> Path.of(fixture.config.getRequiredProperty("atoms.project-execution.node")).toRealPath();
                default -> fixture.policy;
            };
            byte[][] before = new byte[1][];
            fixture.afterFirstProbe = () -> {
                before[0] = Files.readAllBytes(file);
                Files.writeString(file, "EX28_PRIVATE_DRIFT_CANARY", StandardOpenOption.APPEND);
            };
            try {
                var failed = fixture.run(build, jdbc);
                assertThat(failed.status()).as(kind).isEqualTo("FAILED");
                assertThat(failed.errorCode()).isEqualTo(switch (kind) {
                    case "source", "lock" -> "EXECUTION_SOURCE_CHANGED";
                    case "tool" -> "EXECUTION_TOOL_CHANGED";
                    default -> "EXECUTION_CHECK_FAILED";
                });
                assertThat(fixture.executed).startsWith("NODE").doesNotContain("NPM_CI", "MAVEN_PACKAGE");
                assertThat(fixture.proofs.get(build.id())).isNotNull(); assertSettledWithoutArtifact(build);
                assertThat(fixture.row(saved)).isEqualTo(original); open(saved).verify(budget());
            } finally {
                fixture.afterFirstProbe = () -> {};
                // Only restore the test-owned external tool/policy; failed workspaces retain their changed input.
                if (before[0] != null && (kind.equals("tool") || kind.equals("policy"))) Files.write(file, before[0]);
            }
        }
    }

    @Test void aWrongPinnedTreeIsRejectedAtAdmissionWithoutTaskOrCapacity() {
        assertThatThrownBy(() -> fixture.queued("0".repeat(64))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_builds WHERE project_id=?", Integer.class, fixture.base.projectId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE project_id=?", Integer.class, fixture.base.projectId())).isZero();
        assertThat(fixture.executed).isEmpty(); assertThat(fixture.builds).isEmpty();
    }

    @Test void replacedRootAndInsufficientDiskPreserveTheSuccessfulArtifactAndDoNotStartTools() throws Exception {
        var saved = successful(); var original = fixture.row(saved); fixture.executed.clear(); fixture.shared = null;
        var rejected = fixture.queued(); var queued = fixture.row(rejected);
        Path root = fixture.root.resolve("execution"), retained = fixture.root.resolve("retained-execution");
        Files.move(root, retained); Files.createDirectory(root);
        try {
            assertThatThrownBy(() -> fixture.run(rejected, jdbc)).hasMessage("EXECUTION_STATE_UNAVAILABLE");
            assertThat(fixture.row(rejected)).isEqualTo(queued); assertThat(fixture.executed).isEmpty();
        } finally {
            Files.move(root, fixture.root.resolve("rejected-root")); Files.move(retained, root);
        }
        var service = new ProjectExecutionService(new ExecutionSettings(fixture.config), new ExecutionBuildRepository(jdbc),
                projects, fixture.source.readGateway(), transactions);
        assertThat(service.cancel(fixture.owner, rejected.projectId(), rejected.id()).status()).isEqualTo("CANCELLED");
        fixture.paths.close();
        fixture.paths = new ExecutionPaths(root, List.of(fixture.root.resolve("source"), fixture.root.resolve("work")),
                ExecutionSettings.ROOT_MAX_BYTES, Long.MAX_VALUE);
        var full = fixture.queued(); var result = fixture.run(full, jdbc);
        assertThat(result.status()).isEqualTo("FAILED"); assertThat(result.errorCode()).isEqualTo("EXECUTION_STORAGE_CAPACITY");
        assertThat(fixture.executed).isEmpty(); assertSettledWithoutArtifact(full);
        fixture.paths.close();
        fixture.paths = new ExecutionPaths(root, List.of(fixture.root.resolve("source"), fixture.root.resolve("work")));
        assertThat(fixture.row(saved)).isEqualTo(original); open(saved).verify(budget());
    }

    @Test void replacingOneSealedJarRevokesOnlyThatArtifactWithoutRewritingItsSuccessRecord() throws Exception {
        var first = successful(); var original = fixture.row(first); fixture.shared = null;
        var second = successful(); var secondState = fixture.row(second); var opened = open(second);
        Files.writeString(opened.jar(), "EX28_PRIVATE_JAR_CANARY");
        assertThatThrownBy(() -> opened.verify(budget())).hasMessage("EXECUTION_ARTIFACT_INVALID");
        assertThatThrownBy(() -> open(second)).hasMessage("EXECUTION_ARTIFACT_INVALID");
        assertThat(fixture.row(second)).isEqualTo(secondState);
        assertThat(fixture.row(first)).isEqualTo(original); open(first).verify(budget());
    }

    private ExecutionBuild successful() throws Exception {
        var result = fixture.run(fixture.queued(), jdbc); assertThat(result.status()).isEqualTo("SUCCEEDED"); return result;
    }
    private ExecutionArtifact open(ExecutionBuild build) throws Exception {
        var row = fixture.row(build);
        return ExecutionArtifact.openSuccessful(fixture.paths, build, (String) row.get("jar_sha256"), (String) row.get("manifest_sha256"), budget());
    }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
    private void assertSettledWithoutArtifact(ExecutionBuild build) {
        assertThat(fixture.row(build)).containsEntry("execution_pending", false).containsEntry("worker_slot", null).containsEntry("jar_sha256", null);
        assertThat(fixture.row(build).get("finished_at")).isNotNull();
        assertThat(fixture.root.resolve("execution/artifacts/" + build.id())).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE task_id=?", Integer.class, build.id())).isZero();
    }
}
