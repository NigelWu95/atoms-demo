package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded internal read API. No source I/O, process control or raw diagnostics. */
@Service
public final class ExecutionQuery {
    public enum Kind {
        BUILD("execution_builds", "execution_build_events", "build_id",
                "source_version_id,null::uuid AS build_id,null::uuid AS result_version_id,cancel_requested,false AS stop_requested"),
        PREPARATION("dependency_preparations", "dependency_preparation_events", "task_id",
                "base_version_id AS source_version_id,null::uuid AS build_id,result_version_id,cancel_requested,false AS stop_requested"),
        RUN("execution_runs", "execution_run_events", "task_id",
                "source_version_id,build_id,null::uuid AS result_version_id,false AS cancel_requested,stop_requested");
        private final String table, events, eventKey, columns;
        Kind(String table, String events, String eventKey, String columns) {
            this.table = table; this.events = events; this.eventKey = eventKey; this.columns = columns;
        }
    }
    public record TaskView(UUID id, UUID projectId, Kind kind, UUID sourceVersionId, UUID buildId, UUID resultVersionId,
            String status, boolean cancelRequested, boolean stopRequested, boolean executionPending,
            String errorCode, Instant createdAt, Instant finishedAt) {}
    public record EventView(UUID taskId, int sequence, Instant createdAt, ExecutionDiagnostics.Detail diagnostic) {}
    public record Page<T>(List<T> items, String nextCursor, boolean hasMore) {
        public Page { items = List.copyOf(items); }
    }
    private record Position(Instant time, UUID id) {}
    private final ExecutionSettings settings;
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final TransactionTemplate transaction;

    ExecutionQuery(ExecutionSettings settings, JdbcTemplate jdbc, ProjectService projects, PlatformTransactionManager transactions) {
        this.settings = settings; this.jdbc = jdbc; this.projects = projects;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }

    /** Unique descending keyset; new tasks never shift an offset or duplicate already consumed rows. */
    public Page<TaskView> tasks(UUID owner, UUID project, Kind kind, String cursor, int limit) {
        return read(owner, project, kind, limit, () -> {
            String scope = scope("tasks", owner, project, kind);
            Position after = cursor == null ? null : position(decode(cursor, scope));
            if (after != null) {
                // Resolve the real anchor before binding its timestamp; a forged out-of-range time must not abort PG's transaction.
                var anchor = jdbc.query("SELECT created_at FROM " + kind.table + " WHERE owner_id=? AND project_id=? AND id=?",
                        (rs, row) -> rs.getTimestamp(1).toInstant(), owner, project, after.id());
                if (anchor.size() != 1 || !after.time().equals(anchor.get(0))) throw invalidCursor();
            }
            String sql = "SELECT id,project_id," + kind.columns + ",status,execution_pending,error_code,created_at,finished_at FROM "
                    + kind.table + " WHERE owner_id=? AND project_id=?";
            RowMapper<TaskView> mapper = (rs, row) -> new TaskView(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), kind,
                    rs.getObject("source_version_id", UUID.class), rs.getObject("build_id", UUID.class), rs.getObject("result_version_id", UUID.class),
                    ExecutionDiagnostics.status(rs.getString("status")), rs.getBoolean("cancel_requested"), rs.getBoolean("stop_requested"),
                    rs.getBoolean("execution_pending"), ExecutionDiagnostics.errorCode(rs.getString("error_code")),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
            var rows = after == null
                    ? jdbc.query(sql + " ORDER BY created_at DESC,id DESC LIMIT ?", mapper, owner, project, limit + 1)
                    : jdbc.query(sql + " AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?", mapper,
                            owner, project, Timestamp.from(after.time()), after.id(), limit + 1);
            boolean more = rows.size() > limit; var page = rows.subList(0, Math.min(limit, rows.size()));
            String next = null;
            if (more) { var last = page.get(page.size() - 1); next = encode(scope + last.createdAt() + "|" + last.id()); }
            return new Page<>(page, next, more);
        });
    }

    /** Keep a tail cursor even without more rows, so later polling reads only newly appended events. */
    public Page<EventView> events(UUID owner, UUID project, Kind kind, UUID task, String cursor, int limit) {
        return read(owner, project, kind, limit, () -> {
            if (task == null) throw error(422, "EXECUTION_INVALID_INPUT");
            String scope = scope("events", owner, project, kind) + task + "|";
            int after = cursor == null ? 0 : sequence(decode(cursor, scope));
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT FROM " + kind.table
                    + " WHERE owner_id=? AND project_id=? AND id=?)", Boolean.class, owner, project, task)))
                throw error(404, "EXECUTION_TASK_NOT_FOUND");
            if (after > 0 && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT FROM " + kind.events
                    + " WHERE " + kind.eventKey + "=? AND seq=?)", Boolean.class, task, after))) throw invalidCursor();
            var rows = jdbc.query("SELECT seq,created_at,payload::text FROM " + kind.events + " WHERE " + kind.eventKey
                    + "=? AND seq>? ORDER BY seq LIMIT ?", (rs, row) -> new EventView(task, rs.getInt(1), rs.getTimestamp(2).toInstant(),
                    ExecutionDiagnostics.event(rs.getString(3))), task, after, limit + 1);
            var page = rows.subList(0, Math.min(limit, rows.size()));
            String next = page.isEmpty() ? cursor : encode(scope + page.get(page.size() - 1).sequence());
            return new Page<>(page, next, rows.size() > limit);
        });
    }

    private <T> T read(UUID owner, UUID project, Kind kind, int limit, Supplier<T> action) {
        try {
            authorize(owner);
            if (project == null || kind == null || limit < 1 || limit > ExecutionSettings.PAGE_LIMIT) throw error(422, "EXECUTION_INVALID_INPUT");
            return transaction.execute(tx -> {
                // Archives retain their audit. Ownership changes serialize on this same project row.
                projects.lock(owner, project.toString()); authorize(owner);
                var result = action.get(); authorize(owner); return result;
            });
        } catch (ApiException failure) {
            throw switch (failure.code()) {
                case "EXECUTION_DISABLED", "EXECUTION_FORBIDDEN", "PROJECT_NOT_FOUND", "EXECUTION_INVALID_INPUT",
                     "EXECUTION_INVALID_CURSOR", "EXECUTION_TASK_NOT_FOUND" -> error(failure.status(), failure.code());
                default -> error(503, "EXECUTION_QUERY_UNAVAILABLE");
            };
        } catch (RuntimeException failure) { throw error(503, "EXECUTION_QUERY_UNAVAILABLE"); }
    }
    private void authorize(UUID owner) {
        var config = settings.load();
        if (owner == null || !config.maintainerIds().contains(owner)) throw error(403, "EXECUTION_FORBIDDEN");
    }
    private static String scope(String type, UUID owner, UUID project, Kind kind) { return "1|" + type + "|" + owner + "|" + project + "|" + kind + "|"; }
    private static String encode(String raw) { return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String token, String scope) {
        try {
            if (token.isEmpty() || token.length() > 512) throw invalidCursor();
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!encode(raw).equals(token) || !raw.startsWith(scope)) throw invalidCursor();
            return raw.substring(scope.length());
        } catch (IllegalArgumentException error) { throw invalidCursor(); }
    }
    private static Position position(String raw) {
        try {
            var parts = raw.split("\\|", -1); if (parts.length != 2) throw invalidCursor();
            var time = Instant.parse(parts[0]); var id = UUID.fromString(parts[1]);
            if (!time.toString().equals(parts[0]) || !id.toString().equals(parts[1]) || !Timestamp.from(time).toInstant().equals(time)) throw invalidCursor();
            return new Position(time, id);
        } catch (IllegalArgumentException | java.time.DateTimeException error) { throw invalidCursor(); }
    }
    private static int sequence(String raw) {
        if (!raw.matches("[1-9][0-9]{0,3}")) throw invalidCursor();
        int value = Integer.parseInt(raw); if (value > ExecutionSettings.EVENT_LIMIT) throw invalidCursor(); return value;
    }
    private static ApiException invalidCursor() { return error(400, "EXECUTION_INVALID_CURSOR"); }
    private static ApiException error(int status, String code) { return new ApiException(status, code, "任务查询不可用或分页位置无效，请刷新后重试。"); }
}
