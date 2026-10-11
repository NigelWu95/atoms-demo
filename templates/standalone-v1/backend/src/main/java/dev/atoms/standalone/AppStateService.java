package dev.atoms.standalone;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
final class AppStateService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RuntimeResources resources;
    private final IdempotencyService idempotency;

    AppStateService(JdbcTemplate jdbc, ObjectMapper json, RuntimeResources resources, IdempotencyService idempotency) {
        this.jdbc = jdbc; this.json = json; this.resources = resources; this.idempotency = idempotency;
    }

    StateDocument save(UUID guest, StateWriteRequest request) {
        return idempotency.execute(guest, request, () -> {
            if (!resources.artifact().loadedRuntimeId().equals(request.loadedRuntimeId()))
                throw new ApiException(ApiException.Code.STALE_APP_VERSION);
            if (get(guest).revision() != request.expectedRevision()) throw new ApiException(ApiException.Code.STATE_CONFLICT);
            long revision = request.expectedRevision() + 1;
            int changed = jdbc.update("""
                    INSERT INTO standalone_app.app_state(guest_id, value, revision, state_schema_version)
                    VALUES (?, ?::json, ?, 1)
                    ON CONFLICT (guest_id) DO UPDATE SET value = EXCLUDED.value, revision = EXCLUDED.revision, updated_at = now()
                    WHERE app_state.revision = ?
                    """, guest, request.valueJson(), revision, request.expectedRevision());
            if (changed != 1) throw new ApiException(ApiException.Code.STATE_CONFLICT);
            return parse(request.valueJson(), revision, 1);
        });
    }

    StateDocument get(UUID guest) {
        var stored = jdbc.query("SELECT value::text, revision, state_schema_version FROM standalone_app.app_state WHERE guest_id = ?",
                (rs, row) -> parse(rs.getString(1), rs.getLong(2), rs.getInt(3)), guest);
        return stored.isEmpty() ? new StateDocument(resources.artifact().initialState(), 0, 1) : stored.get(0);
    }

    private StateDocument parse(String value, long revision, int schema) {
        try { return new StateDocument(json.readTree(value), revision, schema); }
        catch (JsonProcessingException ignored) { throw new ApiException(ApiException.Code.INTERNAL_ERROR); }
    }
}
