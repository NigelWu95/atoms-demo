package dev.atoms.generated;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Business code in the generated project, backed by its own PostgreSQL table. */
@RestController
@RequestMapping("/api/todos")
public class TodoController {
    public record Todo(long id, String title, boolean completed, long revision) {}
    public record Create(String title) {}
    public record Update(String title, Boolean completed, Long revision) {}
    public record Page(List<Todo> items, Long nextAfter) { public Page { items = List.copyOf(items); } }
    private static final String COLUMNS = "id,title,completed,revision";
    private static final RowMapper<Todo> ROW = (rs, row) -> new Todo(rs.getLong("id"), rs.getString("title"),
            rs.getBoolean("completed"), rs.getLong("revision"));
    private final JdbcTemplate jdbc;

    public TodoController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Page list(@RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "100") int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw invalid();
        var rows = jdbc.query("SELECT " + COLUMNS + " FROM generated_app.todos WHERE id>? ORDER BY id LIMIT ?", ROW, after, limit + 1);
        boolean more = rows.size() > limit;
        var page = rows.subList(0, Math.min(limit, rows.size()));
        return new Page(page, more ? page.get(page.size() - 1).id() : null);
    }

    @GetMapping("/{id}")
    public Todo get(@PathVariable long id) {
        var rows = jdbc.query("SELECT " + COLUMNS + " FROM generated_app.todos WHERE id=?", ROW, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Todo not found");
        return rows.get(0);
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Todo create(@RequestBody Create input) {
        if (input == null) throw invalid();
        return jdbc.queryForObject("INSERT INTO generated_app.todos(title) VALUES (?) RETURNING " + COLUMNS, ROW, title(input.title()));
    }

    @PutMapping("/{id}")
    public Todo update(@PathVariable long id, @RequestBody Update input) {
        if (input == null || input.completed() == null || input.revision() == null || input.revision() < 1) throw invalid();
        String title = title(input.title());
        // Compare and change in one statement, so a stale page cannot overwrite a newer change.
        // https://www.postgresql.org/docs/17/sql-update.html
        var rows = jdbc.query("UPDATE generated_app.todos SET title=?,completed=?,revision=revision+1 WHERE id=? AND revision=? RETURNING " + COLUMNS,
                ROW, title, input.completed(), id, input.revision());
        if (!rows.isEmpty()) return rows.get(0);
        get(id);
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Todo changed; reload before editing");
    }

    private static String title(String value) {
        if (value == null) throw invalid();
        String clean = value.strip();
        if (clean.isEmpty() || clean.codePointCount(0, clean.length()) > 160 || clean.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return clean;
    }
    // EX25_V2_RULES
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid todo input"); }
}
