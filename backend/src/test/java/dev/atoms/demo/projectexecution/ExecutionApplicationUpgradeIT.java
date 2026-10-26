package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.demo.project.ProjectService;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Real append-only migration from retained V1 artifacts; never resets business history to rerun an upgrade. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionApplicationUpgradeIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource database;
    static final UUID A = ExecutionBindingContractIT.A, B = ExecutionBindingContractIT.B;

    @Test void bothProjectsRequireExactApprovalAndPreserveDataAcrossVersionTwoAndRestart() throws Exception {
        try (var app = new ExecutionApplicationFixture(jdbc, projects, transactions, database)) {
            for (UUID project : List.of(A, B)) assertThat(app.compatibleVersion(project))
                    .as("Upgrade requires retained V1 history; never erase an already migrated database").isEqualTo(1);
            var old = Map.of(A, app.retainedBuild(A, "execution.application-baseline-a"),
                    B, app.retainedBuild(B, "execution.application-baseline-b"));
            var running = new HashMap<UUID, ExecutionRun>();
            var before = new HashMap<UUID, JsonNode>();
            for (UUID project : List.of(A, B)) {
                running.put(project, app.start(old.get(project), null));
                before.put(project, app.json(project, "POST", "api/todos", Map.of("title", "upgrade-" + project + "-" + UUID.randomUUID()), 201));
            }
            var updated = Map.of(A, app.build(A, ExecutionTodoFixture.application(2)),
                    B, app.build(B, ExecutionTodoFixture.application(2)));
            for (UUID project : List.of(A, B)) {
                UUID other = project.equals(A) ? B : A;
                var next = updated.get(project); var previous = old.get(project);
                assertThat(next.sourceVersionId()).isNotEqualTo(previous.sourceVersionId());
                assertThat(next.treeSha256()).isNotEqualTo(previous.treeSha256());
                assertThat(app.artifact(next).summary().jarSha256()).isNotEqualTo(app.artifact(previous).summary().jarSha256());
                var history = app.history(project);
                var review = app.migrationView(next);
                assertThat(review.decision()).isEqualTo(ExecutionMigrationPolicy.Decision.REVIEW_REQUIRED);
                assertThat(review.historySha256()).isEqualTo(history.state().sha256());
                long otherPid = app.pid(running.get(other));
                app.stop(running.get(project));
                app.assertStartRejected(next, null, "RUN_MIGRATION_REVIEW_REQUIRED");
                assertThat(app.history(project)).isEqualTo(history);
                UUID approval = app.approveMigration(next, review);
                assertThat(app.approveMigration(next, review)).isEqualTo(approval);
                var upgraded = app.start(next, approval);
                running.put(project, upgraded);
                var migrated = app.history(project);
                assertThat(migrated.state().applied()).hasSize(history.state().applied().size() + 1)
                        .startsWith(history.state().applied().toArray(ExecutionMigrationPolicy.Applied[]::new));
                assertThat(app.compatibleVersion(project)).isEqualTo(2);
                long id = before.get(project).path("id").asLong();
                var inherited = app.json(project, "GET", "api/todos/" + id, null, 200);
                before.get(project).fields().forEachRemaining(field -> assertThat(inherited.get(field.getKey())).isEqualTo(field.getValue()));
                assertThat(inherited.path("priority").asInt(-1)).isZero();
                var changed = app.json(project, "PUT", "api/todos/" + id, Map.of("title", inherited.path("title").asText(),
                        "completed", true, "revision", inherited.path("revision").asLong(), "priority", 2), 200);
                assertThat(changed.path("priority").asInt()).isEqualTo(2);
                assertThat(changed.path("revision").asLong()).isEqualTo(inherited.path("revision").asLong() + 1);
                changed = app.json(project, "PUT", "api/todos/" + id, Map.of("title", changed.path("title").asText(),
                        "completed", true, "revision", changed.path("revision").asLong()), 200);
                assertThat(changed.path("priority").asInt()).isEqualTo(2); // Older clients do not erase the new field.
                app.json(project, "POST", "api/todos", Map.of("title", "invalid-priority", "priority", 3), 400);
                app.assertDelivered(upgraded, next);
                app.stop(upgraded);
                app.assertStartRejected(next, approval, "RUN_MIGRATION_REVIEW_REQUIRED");
                app.assertStartRejected(previous, null, "RUN_MIGRATION_INCOMPATIBLE");
                assertThat(app.history(project)).isEqualTo(migrated);
                var restarted = app.start(next, null);
                running.put(project, restarted);
                assertThat(restarted.id()).isNotEqualTo(upgraded.id());
                assertThat(app.json(project, "GET", "api/todos/" + id, null, 200)).isEqualTo(changed);
                assertThat(app.history(project)).isEqualTo(migrated);
                app.assertDelivered(restarted, next);
                assertThat(app.pid(running.get(other))).isEqualTo(otherPid);
                assertThat(app.current(running.get(other)).status()).isEqualTo("READY");
                assertThat(app.json(other, "GET", "api/todos/" + before.get(other).path("id").asLong(), null, 200)
                        .path("title")).isEqualTo(before.get(other).path("title"));
                System.out.printf("execution_application_upgrade project=%s oldBuild=%s build=%s approval=%s historyBefore=%s historyAfter=%s run=%s restarted=%s priority=2%n",
                        project, previous.id(), next.id(), approval, history.state().sha256(), migrated.state().sha256(), upgraded.id(), restarted.id());
            }
            app.stop(running.get(A)); app.stop(running.get(B));
            app.assertTestDatabasesUnchanged();
        }
    }
}
