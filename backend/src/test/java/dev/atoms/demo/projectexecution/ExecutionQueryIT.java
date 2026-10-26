package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.jdbc.core.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import static dev.atoms.demo.projectexecution.ExecutionEventSchemaIT.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Read/query contract against rolled-back PG metadata, not a generated application execution. */
@ActiveProfiles("test") @Transactional @ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionQueryIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ProjectService projects;
    @Autowired ExecutionQuery disabled;
    Fixture fixture;
    MockEnvironment configuration;
    ExecutionQuery query;

    @BeforeEach void metadata() {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class)).isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
        fixture = seed(jdbc);
        configuration = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", fixture.owner().toString());
        for (String name : List.of("root", "node", "npm", "java", "maven", "policy"))
            configuration.withProperty("atoms.project-execution." + name, "/unused/query/" + name);
        query = new ExecutionQuery(new ExecutionSettings(configuration), jdbc, projects, transactions);
    }

    @ParameterizedTest @EnumSource(ExecutionQuery.Kind.class)
    void keysetPaginationKeepsExistingRowsOnceWhenTasksAreInsertedBetweenPages(ExecutionQuery.Kind kind) {
        var original = new ArrayList<UUID>();
        var created = new HashMap<UUID, java.time.Instant>();
        for (int i = 0; i < 5; i++) {
            var task = task(jdbc, fixture, kind.name()); original.add(task.id()); finish(jdbc, task);
            created.put(task.id(), jdbc.queryForObject("SELECT created_at FROM " + task.table() + " WHERE id=?", java.sql.Timestamp.class, task.id()).toInstant());
        }
        var first = query.tasks(fixture.owner(), fixture.project(), kind, null, 2);
        assertThat(first.items()).hasSize(2); assertThat(first.hasMore()).isTrue(); assertThat(first.nextCursor()).isNotBlank();
        var inserted = task(jdbc, fixture, kind.name()); finish(jdbc, inserted);
        var observed = new ArrayList<>(first.items().stream().map(ExecutionQuery.TaskView::id).toList());
        String cursor = first.nextCursor(); int pages = 1;
        while (cursor != null) {
            var next = query.tasks(fixture.owner(), fixture.project(), kind, cursor, 2);
            assertThat(next.items()).hasSizeLessThanOrEqualTo(2);
            observed.addAll(next.items().stream().map(ExecutionQuery.TaskView::id).toList()); cursor = next.nextCursor();
            assertThat(++pages).isLessThanOrEqualTo(4);
        }
        assertThat(observed).doesNotHaveDuplicates().containsAll(original);
        var expected = new ArrayList<>(original);
        expected.sort(Comparator.<UUID, java.time.Instant>comparing(created::get).thenComparing(UUID::toString).reversed());
        assertThat(observed.stream().filter(original::contains).toList()).containsExactlyElementsOf(expected);
        assertThat(first.items()).allSatisfy(row -> { assertThat(row.kind()).isEqualTo(kind); assertThat(row.sourceVersionId()).isEqualTo(fixture.source()); });
        assertThatThrownBy(() -> first.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        code("EXECUTION_INVALID_CURSOR", () -> query.tasks(fixture.owner(), fixture.project(), kind,
                changed(first.nextCursor(), 5, "1970-01-01T00:00:00Z"), 2));
        var otherProject = projects.create(fixture.owner(), "other-query-project").id();
        code("EXECUTION_INVALID_CURSOR", () -> query.tasks(fixture.owner(), otherProject, kind, first.nextCursor(), 2));
    }

    @ParameterizedTest @EnumSource(ExecutionQuery.Kind.class)
    void eventCursorCanResumeAfterTheTailAndCannotBeReusedForAnotherScope(ExecutionQuery.Kind kind) {
        var task = task(jdbc, fixture, kind.name());
        var first = query.events(fixture.owner(), fixture.project(), kind, task.id(), null, 1);
        assertThat(first.items()).hasSize(1); assertThat(first.hasMore()).isFalse(); assertThat(first.nextCursor()).isNotBlank();
        assertThat(first.items().get(0).diagnostic().type()).isEqualTo("STAGE");
        var empty = query.events(fixture.owner(), fixture.project(), kind, task.id(), first.nextCursor(), 1);
        assertThat(empty.items()).isEmpty(); assertThat(empty.nextCursor()).isEqualTo(first.nextCursor());
        finish(jdbc, task);
        var next = query.events(fixture.owner(), fixture.project(), kind, task.id(), first.nextCursor(), 100);
        assertThat(next.items()).extracting(ExecutionQuery.EventView::sequence).containsExactly(2);
        assertThat(next.items().get(0).diagnostic().status()).isEqualTo("CANCELLED");
        var second = task(jdbc, fixture, kind.name()); finish(jdbc, second);
        var list = query.tasks(fixture.owner(), fixture.project(), kind, null, 1);
        code("EXECUTION_INVALID_CURSOR", () -> query.events(fixture.owner(), fixture.project(), kind, second.id(), first.nextCursor(), 1));
        code("EXECUTION_INVALID_CURSOR", () -> query.tasks(fixture.owner(), fixture.project(), kind, first.nextCursor(), 1));
        code("EXECUTION_INVALID_CURSOR", () -> query.events(fixture.owner(), fixture.project(), kind, task.id(), list.nextCursor(), 1));
        for (String position : List.of("0", "1001", "999", "-1", "1.5", "01"))
            code("EXECUTION_INVALID_CURSOR", () -> query.events(fixture.owner(), fixture.project(), kind, task.id(), changed(first.nextCursor(), 6, position), 1));
        var otherKind = kind == ExecutionQuery.Kind.BUILD ? ExecutionQuery.Kind.RUN : ExecutionQuery.Kind.BUILD;
        code("EXECUTION_INVALID_CURSOR", () -> query.tasks(fixture.owner(), fixture.project(), otherKind, list.nextCursor(), 1));
        var all = query.events(fixture.owner(), fixture.project(), kind, task.id(), null, 1);
        assertThat(all.hasMore()).isTrue();
    }

    @Test void enforcesPageSizeAndEveryPageRechecksOwnerAndMaintainerWhileArchiveRemainsReadable() {
        for (int i = 0; i < 102; i++) finish(jdbc, task(jdbc, fixture, "BUILD"));
        var page = query.tasks(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, null, 100);
        assertThat(page.items()).hasSize(100); assertThat(page.hasMore()).isTrue();
        assertThat(query.tasks(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, page.nextCursor(), 100).items()).hasSize(2);
        UUID id = page.items().get(0).id();
        jdbc.update("INSERT INTO execution_build_events(build_id,seq,payload) SELECT ?,n,'{}'::jsonb FROM generate_series(3,103) n", id);
        var events = query.events(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, id, null, 100);
        assertThat(events.items()).hasSize(100); assertThat(events.hasMore()).isTrue();
        assertThat(query.events(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, id, events.nextCursor(), 100).items()).hasSize(3);
        for (int size : new int[]{0, -1, 101, Integer.MAX_VALUE}) {
            code("EXECUTION_INVALID_INPUT", () -> query.tasks(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, null, size));
            code("EXECUTION_INVALID_INPUT", () -> query.events(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, id, null, size));
        }
        for (String cursor : List.of("", "x".repeat(1025), "invalid\n", page.nextCursor() + "=", "e30"))
            code("EXECUTION_INVALID_CURSOR", () -> query.tasks(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, cursor, 2));
        code("EXECUTION_INVALID_CURSOR", () -> query.tasks(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD,
                changed(page.nextCursor(), 5, "+400000-01-01T00:00:00Z"), 2));
        code("EXECUTION_DISABLED", () -> disabled.tasks(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, null, 1));
        UUID other = UUID.randomUUID(); jdbc.update("INSERT INTO guest_users(id) VALUES (?)", other);
        code("EXECUTION_FORBIDDEN", () -> query.tasks(other, fixture.project(), ExecutionQuery.Kind.BUILD, null, 1));
        configuration.withProperty("atoms.project-execution.maintainer-ids", fixture.owner() + "," + other);
        projects.archive(fixture.owner(), fixture.project().toString(), true);
        assertThat(query.tasks(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, null, 1).items()).hasSize(1);
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", other, fixture.project());
        code("PROJECT_NOT_FOUND", () -> query.tasks(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, page.nextCursor(), 1));
        code("PROJECT_NOT_FOUND", () -> query.events(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, id, null, 1));
        assertThat(query.tasks(other, fixture.project(), ExecutionQuery.Kind.BUILD, null, 1).items()).isEmpty();
        code("EXECUTION_TASK_NOT_FOUND", () -> query.events(other, fixture.project(), ExecutionQuery.Kind.BUILD, id, null, 1));
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", fixture.owner(), fixture.project());
        configuration.withProperty("atoms.project-execution.maintainer-ids", other.toString());
        code("EXECUTION_FORBIDDEN", () -> query.tasks(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, page.nextCursor(), 1));
    }

    @Test void neverReturnsOrLogsRawPayloadUnknownCodesOrDriverDetails(CapturedOutput output) {
        var task = task(jdbc, fixture, "BUILD");
        jdbc.update("UPDATE execution_builds SET error_code='EXECUTION_PRIVATE_CANARY' WHERE id=?", task.id());
        jdbc.update("INSERT INTO execution_build_events(build_id,seq,payload) VALUES (?,3,?::jsonb)", task.id(),
                "{\"status\":\"QUEUED\",\"code\":\"EXECUTION_PRIVATE_CANARY\",\"stderr\":\"CANARY\",\"source\":\"CANARY\",\"path\":\"/private/CANARY\"}");
        var items = query.events(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, task.id(), null, 100);
        assertThat(items.items()).hasSize(3);
        assertThat(items.items().get(2).diagnostic().code()).isEqualTo("EXECUTION_DIAGNOSTIC_UNAVAILABLE");
        assertThat(items.toString()).doesNotContain("CANARY", "/private");
        var list = query.tasks(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, null, 1);
        assertThat(list.items().get(0).errorCode()).isEqualTo("EXECUTION_DIAGNOSTIC_UNAVAILABLE");
        var broken = mock(JdbcTemplate.class);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("jdbc:CANARY /private/CANARY"))
                .when(broken).query(anyString(), any(RowMapper.class), any(Object[].class));
        var failed = new ExecutionQuery(new ExecutionSettings(configuration), broken, projects, transactions);
        code("EXECUTION_QUERY_UNAVAILABLE", () -> failed.tasks(fixture.owner(), fixture.project(), ExecutionQuery.Kind.BUILD, null, 1));
        assertThat(output.getAll()).doesNotContain("CANARY", "/private/CANARY");
    }

    private String changed(String cursor, int index, String value) {
        var fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
        fields[index] = value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("|", fields).getBytes(StandardCharsets.UTF_8));
    }
    private void code(String expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo(expected); assertThat(error).hasNoCause();
            assertThat(error.getMessage()).doesNotContain("CANARY", "jdbc:", "/private", "/unused");
        });
    }
}
