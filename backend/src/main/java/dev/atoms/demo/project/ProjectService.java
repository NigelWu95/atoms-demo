package dev.atoms.demo.project;

import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class ProjectService {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Project> ROW = (rs, row) -> new Project(rs.getObject("id", UUID.class),
            rs.getString("name"), rs.getObject("owner_id", UUID.class), rs.getLong("project_revision"),
            rs.getObject("current_version_id", UUID.class), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), rs.getObject("active_run_id", UUID.class));
    public ProjectService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Page(List<Project> items, String nextCursor) {}
    private record Cursor(Instant createdAt, UUID id) {}

    public Project create(UUID owner, String name) {
        return jdbc.queryForObject("INSERT INTO projects(id, owner_id, name) VALUES (?, ?, ?) RETURNING *",
                ROW, UUID.randomUUID(), owner, name);
    }
    public Project get(UUID owner, String id) {
        return require(jdbc.query("SELECT * FROM projects WHERE owner_id = ? AND id = ?", ROW, owner, id(id)));
    }
    /** Caller must hold a Spring transaction: used for source/state pointer transitions. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Project lock(UUID owner, String id) {
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        return require(jdbc.query("SELECT * FROM projects WHERE owner_id = ? AND id = ? FOR UPDATE", ROW, owner, id(id)));
    }
    public Project rename(UUID owner, String id, String name) {
        return require(jdbc.query("""
                UPDATE projects SET name = ?, updated_at = CASE WHEN name = ? THEN updated_at ELSE now() END
                WHERE owner_id = ? AND id = ? RETURNING *
                """, ROW, name, name, owner, id(id)));
    }
    public Page list(UUID owner, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new ApiException(422, "INVALID_INPUT", "limit 必须在 1–100 之间。");
        List<Project> items;
        if (cursor == null) {
            items = jdbc.query("SELECT * FROM projects WHERE owner_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                    ROW, owner, limit + 1);
        } else {
            Cursor after = decode(cursor);
            items = jdbc.query("""
                    SELECT * FROM projects WHERE owner_id = ? AND (created_at, id) < (?, ?)
                    ORDER BY created_at DESC, id DESC LIMIT ?
                    """, ROW, owner, Timestamp.from(after.createdAt()), after.id(), limit + 1);
        }
        boolean hasMore = items.size() > limit;
        var page = List.copyOf(items.subList(0, Math.min(limit, items.size())));
        return new Page(page, hasMore ? encode(page.get(page.size() - 1)) : null);
    }
    private Project require(List<Project> projects) {
        if (projects.isEmpty()) throw notFound();
        return projects.get(0);
    }
    private UUID id(String id) {
        try {
            UUID value = UUID.fromString(id);
            if (!value.toString().equals(id)) throw notFound();
            return value;
        } catch (IllegalArgumentException error) { throw notFound(); }
    }
    private ApiException notFound() { return new ApiException(404, "PROJECT_NOT_FOUND", "项目不存在或当前访客无权访问。"); }
    private String encode(Project project) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (project.createdAt() + "|" + project.id()).getBytes(StandardCharsets.UTF_8));
    }
    private Cursor decode(String value) {
        try {
            if (value.length() > 160) throw new IllegalArgumentException();
            String[] fields = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 2) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(fields[0]), UUID.fromString(fields[1]));
        } catch (IllegalArgumentException | java.time.DateTimeException error) {
            throw new ApiException(400, "INVALID_CURSOR", "分页位置无效，请重新加载列表。");
        }
    }
}
