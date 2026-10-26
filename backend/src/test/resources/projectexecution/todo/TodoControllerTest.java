package dev.atoms.generated;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// DDL and business writes share the test transaction and roll back; never Flyway clean/repair.
// https://docs.spring.io/spring-boot/3.5/reference/testing/spring-boot-applications.html
@SpringBootTest(properties = {"spring.flyway.enabled=false"})
@AutoConfigureMockMvc @Transactional
@ContextConfiguration(initializers = TodoControllerTest.TestDatabaseOnly.class)
@Sql("classpath:db/migration/V002__todos.sql")
class TodoControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    public static final class TestDatabaseOnly implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            var env = context.getEnvironment();
            String name = env.getProperty("APP_DB_USERNAME", "");
            String url = env.getProperty("APP_DB_URL", "");
            if (!Set.of("atoms_exec_a_test_20261020", "atoms_exec_b_test_20261020").contains(name)
                    || !url.equals("jdbc:postgresql://127.0.0.1:5432/" + name + "?currentSchema=generated_app")
                    || !url.equals(env.getProperty("spring.datasource.url")) || !name.equals(env.getProperty("spring.datasource.username")))
                throw new IllegalStateException("Explicit approved todo test database required");
        }
    }

    @BeforeEach void actualTargetIsDedicated() {
        assertThat(jdbc.queryForObject("SELECT current_database()=current_user AND current_database() IN ('atoms_exec_a_test_20261020','atoms_exec_b_test_20261020') AND current_schema()='generated_app'", Boolean.class)).isTrue();
    }

    @Test void createsTrimmedTitleAndReadsTheRowFromAnotherController() throws Exception {
        var todo = create("  买牛奶  "); long id = todo.path("id").asLong();
        assertThat(id).isPositive(); assertThat(todo.path("title").asText()).isEqualTo("买牛奶");
        assertThat(todo.path("completed").asBoolean()).isFalse(); assertThat(todo.path("revision").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM generated_app.todos WHERE id=?", String.class, id)).isEqualTo("买牛奶");
        assertThat(new TodoController(jdbc).get(id).title()).isEqualTo("买牛奶");
        mvc.perform(get("/api/todos/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("买牛奶"));
    }

    @Test void updatesAtomicallyAndRejectsStaleAndMissingRows() throws Exception {
        long id = create("before").path("id").asLong();
        String body = "{\"title\":\"after\",\"completed\":true,\"revision\":1}";
        mvc.perform(put("/api/todos/{id}", id).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true)).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(put("/api/todos/{id}", id).contentType("application/json").content(body)).andExpect(status().isConflict());
        assertThat(jdbc.queryForMap("SELECT title,completed,revision FROM generated_app.todos WHERE id=?", id))
                .containsEntry("title", "after").containsEntry("completed", true).containsEntry("revision", 2L);
        mvc.perform(get("/api/todos/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mvc.perform(put("/api/todos/{id}", Long.MAX_VALUE).contentType("application/json").content(body)).andExpect(status().isNotFound());
    }

    @Test void rejectsInvalidWritesAndCountsUnicodeCharacters() throws Exception {
        for (String title : List.of("", "  ", "x".repeat(161), "😀".repeat(161), "line\nbreak", "nul\u0000"))
            mvc.perform(post("/api/todos").contentType("application/json").content(json.writeValueAsBytes(Map.of("title", title))))
                    .andExpect(status().isBadRequest());
        for (String body : List.of("{}", "{\"title\":null}", "null", "{"))
            mvc.perform(post("/api/todos").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generated_app.todos", Integer.class)).isZero();
        long id = create("😀".repeat(160)).path("id").asLong();
        for (String body : List.of("{}", "{\"title\":\"x\",\"revision\":1}", "{\"title\":\"x\",\"completed\":false,\"revision\":0}"))
            mvc.perform(put("/api/todos/{id}", id).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT char_length(title) FROM generated_app.todos WHERE id=?", Integer.class, id)).isEqualTo(160);
    }

    @Test void keysetPagesAreBoundedAndKeepLaterInsertions() throws Exception {
        jdbc.update("INSERT INTO generated_app.todos(title) SELECT 'task-' || n FROM generate_series(1,102) n");
        var first = json.readTree(mvc.perform(get("/api/todos")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(first.path("items").size()).isEqualTo(100);
        long after = first.path("nextAfter").asLong(); assertThat(after).isPositive();
        long added = create("later").path("id").asLong();
        var second = json.readTree(mvc.perform(get("/api/todos").param("after", Long.toString(after))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(second.path("items").size()).isEqualTo(3); assertThat(second.path("nextAfter").isNull()).isTrue();
        assertThat(second.path("items").get(2).path("id").asLong()).isEqualTo(added);
        for (String limit : List.of("0", "101", "-1", "1.5")) mvc.perform(get("/api/todos").param("limit", limit)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/todos").param("after", "-1")).andExpect(status().isBadRequest());
    }

    @Test void titleIsAlwaysBoundAsData() throws Exception {
        String title = "'); DROP TABLE generated_app.todos; --";
        long id = create(title).path("id").asLong();
        assertThat(jdbc.queryForObject("SELECT title FROM generated_app.todos WHERE id=?", String.class, id)).isEqualTo(title);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generated_app.todos", Integer.class)).isEqualTo(1);
    }

    @Test void databaseRejectsEmptyTitlesEvenOutsideTheController() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO generated_app.todos(title) VALUES (' ')")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // EX25_V2_TESTS

    private JsonNode create(String title) throws Exception {
        return json.readTree(mvc.perform(post("/api/todos").contentType("application/json").content(json.writeValueAsBytes(Map.of("title", title))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
    }
}
