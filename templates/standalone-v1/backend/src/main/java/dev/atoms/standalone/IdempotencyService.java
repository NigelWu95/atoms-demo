package dev.atoms.standalone;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    IdempotencyService(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.json = json;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private record Stored(String hash, String response) {}

    /** Callback performs local JDBC work only. The guest lock also serializes the absent-state case. */
    StateDocument execute(UUID guest, StateWriteRequest request, Supplier<StateDocument> operation) {
        return transaction.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '3s'");
            // Cross-process lock, not a JVM mutex. At READ COMMITTED the subsequent query sees the winner's commit.
            // https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS
            var locked = jdbc.queryForList("SELECT id FROM standalone_app.guest_users WHERE id = ? FOR UPDATE", UUID.class, guest);
            if (locked.isEmpty()) throw new ApiException(ApiException.Code.SESSION_REQUIRED);
            var stored = jdbc.query("""
                    SELECT request_hash, response_body::text FROM standalone_app.idempotency_records
                    WHERE guest_id = ? AND method = 'PUT' AND resource = '/api/state' AND intent_key = ?
                    """, (rs, row) -> new Stored(rs.getString(1), rs.getString(2)), guest, request.key());
            if (!stored.isEmpty()) {
                if (!stored.get(0).hash().equals(request.hash())) throw new ApiException(ApiException.Code.IDEMPOTENCY_PAYLOAD_MISMATCH);
                try { return json.readValue(stored.get(0).response(), StateDocument.class); }
                catch (JsonProcessingException ignored) { throw new ApiException(ApiException.Code.INTERNAL_ERROR); }
            }
            StateDocument result = operation.get(); // New intents alone check the current runtime/revision.
            try {
                jdbc.update("""
                        INSERT INTO standalone_app.idempotency_records(guest_id, method, resource, intent_key, request_hash, response_body)
                        VALUES (?, 'PUT', '/api/state', ?, ?, ?::json)
                        """, guest, request.key(), request.hash(), json.writeValueAsString(result));
            } catch (JsonProcessingException ignored) { throw new ApiException(ApiException.Code.INTERNAL_ERROR); }
            return result; // Only returned to the controller after the transaction commits.
        });
    }
}
