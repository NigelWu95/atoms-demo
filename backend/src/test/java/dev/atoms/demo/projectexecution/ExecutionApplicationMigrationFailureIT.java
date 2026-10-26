package dev.atoms.demo.projectexecution;

import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Real failed Flyway migration, original worker receipt, explicit acceptance and compatible restart. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionApplicationMigrationFailureIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource database;
    static final UUID OWNER = ExecutionBindingContractIT.OWNER, A = ExecutionBindingContractIT.A, B = ExecutionBindingContractIT.B;

    @Test void failedTransactionalMigrationRequiresExplicitAcceptanceBeforeCompatibleRestart() throws Exception {
        List<com.fasterxml.jackson.databind.JsonNode> before;
        ExecutionMigrationHistory.Snapshot history;
        UUID failedVersion, restoredVersion;
        try (var app = new ExecutionApplicationFixture(jdbc, projects, transactions, database)) {
            assertThat(app.compatibleVersion(A)).isEqualTo(2);
            var retained = app.retainedBuild(A, "execution.application-v2-a", 2);
            var baseline = app.start(retained, null); before = app.allTodos(A); assertThat(before).isNotEmpty(); app.stop(baseline);
            history = app.history(A); var otherHistory = app.history(B);
            try (var probe = new ExecutionMigrationFailureProbe(app.target(A))) {
                var originalData = probe.snapshot(); assertThat(originalData.history()).isEqualTo(history.state());
                var files = new ArrayList<>(ExecutionTodoFixture.application(2).files());
                files.add(new SourceTree.File(ExecutionMigrations.SOURCE + "V004__intentional_failure.sql",
                        ExecutionMigrationFailureProbe.SQL.getBytes(StandardCharsets.UTF_8), 0100644));
                var tree = new ProjectSourcePolicy().validate(files); var failedBuild = app.build(A, tree);
                failedVersion = failedBuild.sourceVersionId();
                var view = app.migrationView(failedBuild); assertThat(view.decision()).isEqualTo(ExecutionMigrationPolicy.Decision.REVIEW_REQUIRED);
                UUID review = app.approveMigration(failedBuild, view);
                probe.holdGate();
                var admitted = app.admit(failedBuild, review);
                probe.awaitMigrationAtGate(); // Actual Flyway reached V004 in the original application's DB session.
                var process = app.coordinator.accessScope().ownedProcess(admitted.id()); assertThat(process).isNotNull();
                probe.releaseGate();
                var receipt = process.await(Duration.ofSeconds(30)).stopped(); assertThat(receipt).isNotNull();
                ExecutionApplicationFixture.await(() -> app.current(admitted).status().equals("NEEDS_RECONCILIATION") && !app.coordinator.isRunning(), 30);
                var failed = app.current(admitted);
                assertThat(receipt.matches(failed.id(), failed.executionId(), app.root.resolve("execution/runs/" + failed.id()))).isTrue();
                assertThat(failed.executionPending()).isFalse(); assertThat(failed.readyAt()).isNull(); assertThat(failed.finishedAt()).isNull();
                assertThat(failed.stoppedSha256()).isEqualTo(receipt.digest()); assertThat(failed.errorCode()).isEqualTo("RUN_PROCESS_FAILED");
                assertThat(probe.snapshot()).isEqualTo(originalData); assertThat(app.history(B)).isEqualTo(otherHistory);
                app.assertStartRejected(retained, null, "EXECUTION_COORDINATOR_UNAVAILABLE");
                app.awaitStoppedFailure(failed);
                var inspected = app.reconciliation.inspectStoppedFailure(OWNER, A, failed.id());
                assertThat(inspected.run()).isEqualTo(failed); assertThat(inspected.historySha256()).isEqualTo(history.state().sha256());
                String key = "accept-failed-migration-" + failed.id();
                var audit = app.reconciliation.acknowledgeStoppedFailure(OWNER, inspected, key);
                assertThat(app.reconciliation.acknowledgeStoppedFailure(OWNER, inspected, key)).isEqualTo(audit);
                var ended = app.current(failed); assertThat(ended.status()).isEqualTo("FAILED"); assertThat(ended.finishedAt()).isNotNull();
                assertThat(ended.errorCode()).isEqualTo(failed.errorCode()); assertThat(ended.stoppedSha256()).isEqualTo(failed.stoppedSha256());
                assertThat(probe.snapshot()).isEqualTo(originalData); app.coordinator.close();
                restoredVersion = app.acceptSource(A, ExecutionTodoFixture.application(2)).versionId();
                assertThat(restoredVersion).isNotEqualTo(failedVersion);
                assertThat(app.source.snapshot(OWNER, A, failedVersion).sha256()).isEqualTo(tree.sha256());
                System.out.printf("execution_failed_migration run=%s execution=%s source=%s build=%s receipt=%s history=%s audit=%s restored=%s%n",
                        failed.id(), failed.executionId(), failedVersion, failedBuild.id(), receipt.digest(), inspected.historySha256(), audit.id(), restoredVersion);
            }
        }
        try (var restarted = new ExecutionApplicationFixture(jdbc, projects, transactions, database)) {
            var retained = restarted.retainedBuild(A, "execution.application-v2-a", 2);
            var running = restarted.start(retained, null);
            assertThat(restarted.allTodos(A)).isEqualTo(before); assertThat(restarted.history(A)).isEqualTo(history);
            restarted.assertDelivered(running, retained); restarted.stop(running);
            assertThat(restarted.source.snapshot(OWNER, A, restoredVersion).sha256()).isEqualTo(ExecutionTodoFixture.application(2).sha256());
            assertThat(restarted.source.snapshot(OWNER, A, failedVersion).sha256()).isNotEqualTo(retained.treeSha256());
            System.out.printf("execution_failed_migration_restarted run=%s build=%s restored=%s history=%s%n",
                    running.id(), retained.id(), restoredVersion, history.state().sha256());
        }
    }
}
