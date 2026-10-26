package dev.atoms.demo.projectexecution;

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

/** Explicitly approved local application/DB contract: real builds, Spring Boot HTTP and retained business data. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionApplicationContractIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource database;
    static final UUID A = ExecutionBindingContractIT.A, B = ExecutionBindingContractIT.B;

    @Test void twoApplicationsServeIsolatedTodosAndKeepChangesAfterARealProcessRestart() throws Exception {
        try (var app = new ExecutionApplicationFixture(jdbc, projects, transactions, database)) {
            var a = app.build(A, ExecutionTodoFixture.application(app.compatibleVersion(A)));
            var b = app.build(B, ExecutionTodoFixture.application(app.compatibleVersion(B)));
            var first = app.start(a, null); var other = app.start(b, null);
            long aPid = app.pid(first), bPid = app.pid(other);
            assertThat(aPid).isNotEqualTo(bPid);
            assertThat(app.target(A).databaseOid()).isNotEqualTo(app.target(B).databaseOid());
            assertThat(app.target(A).port()).isEqualTo(18181); assertThat(app.target(B).port()).isEqualTo(18182);
            app.assertDelivered(first, a); app.assertDelivered(other, b);

            String marker = UUID.randomUUID().toString();
            var created = app.json(A, "POST", "api/todos", Map.of("title", "A-" + marker), 201);
            var separate = app.json(B, "POST", "api/todos", Map.of("title", "B-" + marker), 201);
            long id = created.path("id").asLong(), revision = created.path("revision").asLong();
            assertThat(id).isPositive(); assertThat(revision).isEqualTo(1);
            var update = Map.of("title", "A-finished-" + marker, "completed", true, "revision", revision);
            var changed = app.json(A, "PUT", "api/todos/" + id, update, 200);
            assertThat(changed.path("revision").asLong()).isEqualTo(revision + 1);
            assertThat(changed.path("completed").asBoolean()).isTrue();
            app.json(A, "PUT", "api/todos/" + id, update, 409);
            app.json(A, "POST", "api/todos", Map.of("title", "  "), 400);
            assertThat(app.json(A, "GET", "api/todos/" + id, null, 200)).isEqualTo(changed);
            assertThat(app.json(B, "GET", "api/todos/" + separate.path("id").asLong(), null, 200)).isEqualTo(separate);
            assertThat(app.allTodos(A)).anyMatch(row -> row.equals(changed)).noneMatch(row -> row.path("title").asText().equals("B-" + marker));
            assertThat(app.allTodos(B)).anyMatch(row -> row.equals(separate)).noneMatch(row -> row.path("title").asText().equals("A-finished-" + marker));

            var history = app.history(A);
            app.stop(first);
            assertThat(app.current(other).status()).isEqualTo("READY");
            assertThat(app.pid(other)).isEqualTo(bPid);
            assertThat(app.json(B, "GET", "api/todos/" + separate.path("id").asLong(), null, 200)).isEqualTo(separate);
            var restarted = app.start(a, null);
            assertThat(restarted.id()).isNotEqualTo(first.id());
            assertThat(restarted.executionId()).isNotEqualTo(first.executionId());
            assertThat(app.pid(restarted)).isNotEqualTo(aPid);
            assertThat(app.json(A, "GET", "api/todos/" + id, null, 200)).isEqualTo(changed);
            assertThat(app.history(A)).isEqualTo(history);
            app.assertDelivered(restarted, a);
            app.stop(restarted); app.stop(other);
            app.assertTestDatabasesUnchanged();
            System.out.printf("execution_application_business a=%s b=%s aPid=%d bPid=%d restarted=%s persistedId=%d isolated=true%n",
                    a.id(), b.id(), aPid, bPid, restarted.id(), id);
        }
    }
}
