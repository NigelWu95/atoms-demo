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
            rs.getObject("current_version_id", UUID.class), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), rs.getObject("active_run_id", UUID.class), rs.getBoolean("archived"));
    public ProjectService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Page(List<Project> items, String nextCursor, java.util.Map<UUID, UUID> sourceVersions) {}
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
        return list(owner, cursor, limit, "", "created", false);
    }
    public Project archive(UUID owner, String id, boolean archived) {
        return require(jdbc.query("UPDATE projects SET archived = ?, updated_at = CASE WHEN archived = ? THEN updated_at ELSE now() END WHERE owner_id = ? AND id = ? RETURNING *",
                ROW, archived, archived, owner, id(id)));
    }
    public Page list(UUID owner, String cursor, int limit, String query, String sort, boolean archived) {
        if (limit < 1 || limit > 100) throw new ApiException(422, "INVALID_INPUT", "limit 必须在 1–100 之间。");
        if (query == null || query.length() > 80 || query.codePoints().anyMatch(Character::isISOControl)
                || !("created".equals(sort) || "updated".equals(sort)))
            throw new ApiException(422, "INVALID_INPUT", "搜索词最多 80 字符，排序仅支持 created 或 updated。");
        String search = query.strip();
        String column = "updated".equals(sort) ? "updated_at" : "created_at";
        String scope = sort + "|" + archived + "|" + Base64.getUrlEncoder().withoutPadding().encodeToString(search.getBytes(StandardCharsets.UTF_8));
        String sql = "SELECT * FROM projects WHERE owner_id = ? AND archived = ? AND strpos(lower(name), lower(?)) > 0";
        List<Project> items;
        if (cursor == null) {
            items = jdbc.query(sql + " ORDER BY " + column + " DESC, id DESC LIMIT ?", ROW, owner, archived, search, limit + 1);
        } else {
            Cursor after = decode(cursor, scope);
            items = jdbc.query(sql + " AND (" + column + ", id) < (?, ?) ORDER BY " + column + " DESC, id DESC LIMIT ?",
                    ROW, owner, archived, search, Timestamp.from(after.createdAt()), after.id(), limit + 1);
        }
        boolean hasMore = items.size() > limit;
        var page = List.copyOf(items.subList(0, Math.min(limit, items.size())));
        var sources = new java.util.LinkedHashMap<UUID, UUID>();
        if (!page.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(page.size(), "?"));
            jdbc.query("SELECT project_id,current_source_version_id FROM project_sources WHERE current_source_version_id IS NOT NULL AND project_id IN (" + placeholders + ")",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> sources.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                    page.stream().map(Project::id).toArray());
        }
        return new Page(page, hasMore ? encode(page.get(page.size() - 1), sort, scope) : null, java.util.Map.copyOf(sources));
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
    private ApiException notFound() { return new ApiException(404, "PROJECT_NOT_FOUND", "项目不存在或当前身份无权访问。"); }
    private String encode(Project project, String sort, String scope) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ((sort.equals("updated") ? project.updatedAt() : project.createdAt()) + "|" + project.id() + "|" + scope).getBytes(StandardCharsets.UTF_8));
    }
    private Cursor decode(String value, String scope) {
        try {
            if (value.length() > 800) throw new IllegalArgumentException();
            String[] fields = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", 3);
            if (!(fields.length == 3 && scope.equals(fields[2]))
                    && !(fields.length == 2 && scope.equals("created|false|"))) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(fields[0]), UUID.fromString(fields[1]));
        } catch (IllegalArgumentException | java.time.DateTimeException error) {
            throw new ApiException(400, "INVALID_CURSOR", "分页位置无效，请重新加载列表。");
        }
    }
}
