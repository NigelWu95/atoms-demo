package dev.atoms.demo.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Allowlisted structured facts only; no free-text event payload or raw model/CLI output API. */
@Repository
class AgentEventRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> STATUSES = Set.of("QUEUED", "PREPARING", "RUNNING", "VALIDATING", "ACCEPTING",
            "SOURCE_SAVED", "NO_CHANGES", "FAILED", "CANCELLED", "TIMED_OUT", "NEEDS_RECONCILIATION");
    private final JdbcTemplate jdbc;
    AgentEventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    boolean stage(AgentTask task) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("status", task.status()); payload.put("cancelRequested", task.cancelRequested());
        if (task.errorCode() != null) payload.put("code", task.errorCode());
        return append(task.id(), task.terminal() ? "RESULT" : "STAGE", payload, task.terminal());
    }

    boolean usage(UUID task, long inputTokens, long outputTokens, int observedPaths) {
        return append(task, "USAGE", Map.of("inputTokens", inputTokens, "outputTokens", outputTokens, "observedPaths", observedPaths), false);
    }

    private boolean append(UUID task, String kind, Map<String, Object> payload, boolean terminal) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Events require a task transaction");
        validate(kind, payload);
        final String json;
        try { json = JSON.writeValueAsString(payload); }
        catch (com.fasterxml.jackson.core.JsonProcessingException impossible) { throw new IllegalStateException("Invalid structured event"); }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM agent_events e JOIN agent_tasks t ON t.id=e.task_id
                  WHERE t.id=? AND e.seq=t.last_event_seq AND e.kind=? AND e.payload=?::jsonb)
                """, Boolean.class, task, kind, json))) return false;
        var sequence = jdbc.query("""
                UPDATE agent_tasks SET last_event_seq=last_event_seq+1,event_bytes=event_bytes+octet_length(?::jsonb::text)
                WHERE id=? AND NOT history_expired AND last_event_seq<? AND (? OR finished_at IS NULL)
                  AND event_bytes+octet_length(?::jsonb::text)<=?
                RETURNING last_event_seq
                """, (r,n) -> r.getInt(1), json, task, terminal ? 4096 : 4095, terminal, json, terminal ? 262144 : 261120);
        if (sequence.isEmpty()) return false;
        jdbc.update("INSERT INTO agent_events(task_id,seq,kind,payload) VALUES(?,?,?,?::jsonb)", task, sequence.get(0), kind, json);
        return true;
    }

    AgentQuery.EventPage page(UUID task, int after, int limit) {
        var metadata = jdbc.queryForMap("SELECT last_event_seq,history_expired FROM agent_tasks WHERE id=?", task);
        if (Boolean.TRUE.equals(metadata.get("history_expired")))
            throw new ApiException(410, "AGENT_EVENT_HISTORY_EXPIRED", "任务事件已过保留期限，请读取任务最终状态。");
        int last = ((Number) metadata.get("last_event_seq")).intValue();
        if (after > last) throw AgentTask.invalid();
        List<AgentQuery.Event> rows = jdbc.query("""
                SELECT seq,kind,payload,created_at FROM agent_events WHERE task_id=? AND seq>? ORDER BY seq LIMIT ?
                """, (r,n) -> new AgentQuery.Event(r.getInt("seq"), r.getString("kind"),
                payload(r.getString("kind"), r.getString("payload")), r.getTimestamp("created_at").toInstant()), task, after, limit+1);
        int expected = after+1;
        for (var row : rows) if (row.sequence()!=expected++) throw unavailable();
        if (rows.isEmpty() && after<last) throw unavailable();
        boolean more = rows.size()>limit;
        var result = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new AgentQuery.EventPage(result, result.isEmpty() ? after : result.get(result.size()-1).sequence(), more, last);
    }

    private static Map<String, Object> payload(String kind, String value) {
        try {
            Map<String, Object> payload = JSON.readValue(value, new TypeReference<>() {});
            validate(kind, payload); return Map.copyOf(payload);
        } catch (java.io.IOException | IllegalArgumentException error) { throw unavailable(); }
    }
    private static void validate(String kind, Map<String, Object> value) {
        if (value == null) throw unavailable();
        if (kind.equals("STAGE") || kind.equals("RESULT")) {
            if (!Set.of("status", "cancelRequested", "code").containsAll(value.keySet())
                    || !(value.get("status") instanceof String status) || !STATUSES.contains(status)
                    || !(value.get("cancelRequested") instanceof Boolean)
                    || value.containsKey("code") && (!(value.get("code") instanceof String code) || !code.matches("(AGENT|SOURCE)_[A-Z_]{1,80}")))
                throw unavailable();
        } else if (kind.equals("USAGE")) {
            if (!value.keySet().equals(Set.of("inputTokens", "outputTokens", "observedPaths"))
                    || !count(value.get("inputTokens"), 1_000_000_000) || !count(value.get("outputTokens"), 1_000_000_000)
                    || !count(value.get("observedPaths"), 512)) throw unavailable();
        } else throw unavailable();
    }
    private static boolean count(Object value, long maximum) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue()>=0 && ((Number) value).longValue()<=maximum;
    }
    private static ApiException unavailable() {
        return new ApiException(503, "AGENT_EVENT_HISTORY_UNAVAILABLE", "任务事件记录不完整，请读取任务状态并联系维护者。");
    }
}
