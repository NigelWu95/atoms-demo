package dev.atoms.demo.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RunRepository {
    public record Message(UUID id, UUID projectId, UUID runId, String role, String content, Instant createdAt) {}
    public record Page<T>(List<T> items, String nextCursor) {}
    private record Positioned<T>(long ordinal, T value) {}
    static final RowMapper<Run> ROW = (rs, n) -> new Run(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
            rs.getObject("owner_id", UUID.class), rs.getString("prompt"), rs.getString("intent"), rs.getObject("base_version_id", UUID.class),
            rs.getLong("expected_project_revision"), rs.getString("status"), rs.getString("stage"), rs.getObject("result_version_id", UUID.class),
            rs.getString("error_code"), rs.getString("error_message"), rs.getLong("last_event_seq"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant());
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final ObjectMapper json;
    public RunRepository(JdbcTemplate jdbc, ProjectService projects, ObjectMapper json) { this.jdbc = jdbc; this.projects = projects; this.json = json; }

    public Run get(UUID owner, String id) {
        var rows = jdbc.query("SELECT * FROM runs WHERE id = ? AND owner_id = ?", ROW, id(id), owner);
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }
    Run lock(UUID owner, String id) {
        Run read = get(owner, id);
        projects.lock(owner, read.projectId().toString());
        return jdbc.queryForObject("SELECT * FROM runs WHERE id = ? FOR UPDATE", ROW, read.id());
    }
    long lockBudget() {
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        return jdbc.queryForObject("SELECT requests_reserved FROM generation_budget WHERE id = 1 FOR UPDATE", Long.class);
    }
    void message(Run run, String role, String content) {
        jdbc.update("INSERT INTO messages(id, project_id, run_id, role, content) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), run.projectId(), run.id(), role, content);
    }
    void event(Run run, String type, Map<String, ?> data) {
        long seq = jdbc.queryForObject("UPDATE runs SET last_event_seq = last_event_seq + 1, updated_at = now() WHERE id = ? RETURNING last_event_seq", Long.class, run.id());
        jdbc.update("INSERT INTO run_events(run_id, seq, type, data_json) VALUES (?, ?, ?, ?)", run.id(), seq, type, json.valueToTree(data).toString());
    }
    void terminal(Run run, String status, UUID version, String code, String message) {
        jdbc.update("UPDATE runs SET status = ?, stage = ?, result_version_id = ?, error_code = ?, error_message = ?, updated_at = now() WHERE id = ?",
                status, status, version, code, message, run.id());
        jdbc.update("UPDATE projects SET active_run_id = null WHERE id = ? AND active_run_id = ?", run.projectId(), run.id());
        message(run, "SUCCEEDED".equals(status) ? "BUILDER" : "SYSTEM", message == null ? "代码版本已保存，请在右侧预览应用。" : message);
        event(run, "run.completed", Map.of("status", status, "versionId", version == null ? "" : version.toString()));
    }
    public Page<Run> runs(UUID owner, String project, String cursor, int limit) {
        projects.get(owner, project);
        long before = cursor(cursor, limit);
        return page(jdbc.query("SELECT * FROM runs WHERE project_id = ? AND ordinal < ? ORDER BY ordinal DESC LIMIT ?",
                (rs, n) -> new Positioned<>(rs.getLong("ordinal"), ROW.mapRow(rs, n)), UUID.fromString(project), before, limit + 1), limit);
    }
    public Page<Message> messages(UUID owner, String project, String cursor, int limit) {
        projects.get(owner, project);
        long before = cursor(cursor, limit);
        return page(jdbc.query("SELECT * FROM messages WHERE project_id = ? AND ordinal < ? ORDER BY ordinal DESC LIMIT ?",
                (rs, n) -> new Positioned<>(rs.getLong("ordinal"), new Message(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                        rs.getObject("run_id", UUID.class), rs.getString("role"), rs.getString("content"), rs.getTimestamp("created_at").toInstant())),
                UUID.fromString(project), before, limit + 1), limit);
    }
    private <T> Page<T> page(List<Positioned<T>> rows, int limit) {
        var kept = rows.subList(0, Math.min(limit, rows.size()));
        return new Page<>(kept.stream().map(Positioned::value).toList(), rows.size() > limit ? Long.toString(kept.get(kept.size() - 1).ordinal()) : null);
    }
    private long cursor(String value, int limit) {
        if (limit < 1 || limit > 100) throw new ApiException(422, "INVALID_INPUT", "limit 必须在 1–100 之间。");
        if (value == null) return Long.MAX_VALUE;
        try { long n = Long.parseLong(value); if (n < 1 || !Long.toString(n).equals(value)) throw new NumberFormatException(); return n; }
        catch (NumberFormatException invalid) { throw new ApiException(400, "INVALID_CURSOR", "分页位置无效。"); }
    }
    private UUID id(String value) {
        try { UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw missing(); return id; }
        catch (IllegalArgumentException error) { throw missing(); }
    }
    private ApiException missing() { return new ApiException(404, "RUN_NOT_FOUND", "任务不存在或当前访客无权访问。"); }
}
