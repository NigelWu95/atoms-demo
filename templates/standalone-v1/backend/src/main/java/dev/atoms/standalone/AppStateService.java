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

    AppStateService(JdbcTemplate jdbc, ObjectMapper json, RuntimeResources resources) {
        this.jdbc = jdbc; this.json = json; this.resources = resources;
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
