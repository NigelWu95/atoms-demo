package dev.atoms.demo.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public record Result(int status, JsonNode body) {}
    private record Stored(String hash, Result result) {}

    /** Callback must contain only local database work on this transaction, never network calls. */
    @Transactional(timeout = 5)
    public Result execute(UUID actor, String method, String resource, String key, Object normalizedBody,
                          Supplier<Result> operation) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ApiException(422, "INVALID_INPUT", "缺少或无效的 Idempotency-Key。");
        }
        String hash = hash(canonical(json.valueToTree(normalizedBody)).toString());
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        // The unique INSERT waits for a concurrent transaction, then replays its committed result.
        // https://www.postgresql.org/docs/17/sql-insert.html#SQL-ON-CONFLICT
        int claimed = jdbc.update("""
                INSERT INTO idempotency_records(actor_id, method, resource, intent_key, request_hash)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (actor_id, method, resource, intent_key) DO NOTHING
                """, actor, method, resource, key, hash);
        if (claimed == 0) {
            var existing = jdbc.queryForObject("""
                    SELECT request_hash, response_status, response_body FROM idempotency_records
                    WHERE actor_id = ? AND method = ? AND resource = ? AND intent_key = ?
                    """, (rs, row) -> new Stored(rs.getString("request_hash"),
                    new Result(rs.getInt("response_status"), parse(rs.getString("response_body")))),
                    actor, method, resource, key);
            if (existing == null) throw new IllegalStateException("Idempotency result missing");
            if (!existing.hash().equals(hash)) throw new ApiException(422, "IDEMPOTENCY_PAYLOAD_MISMATCH",
                    "同一次操作标识不能用于不同内容。");
            return existing.result();
        }
        Result result = operation.get();
        jdbc.update("""
                UPDATE idempotency_records SET response_status = ?, response_body = ?
                WHERE actor_id = ? AND method = ? AND resource = ? AND intent_key = ?
                """, result.status(), result.body().toString(), actor, method, resource, key);
        return result;
    }
    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var sorted = json.createObjectNode();
            var names = new ArrayList<String>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            var array = json.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value;
    }
    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Stored result is invalid", error); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
