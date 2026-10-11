package dev.atoms.standalone;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
final class GuestIdentityService {
    private final JdbcTemplate jdbc;
    GuestIdentityService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    record Identity(String userId, boolean isGuest) {}

    Identity create() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO standalone_app.guest_users(id) VALUES (?)", id);
        return new Identity(id.toString(), true);
    }

    Identity get(String name) {
        UUID id;
        try { id = UUID.fromString(name); }
        catch (IllegalArgumentException ignored) { throw new ApiException(ApiException.Code.SESSION_REQUIRED); }
        var rows = jdbc.query("SELECT id FROM standalone_app.guest_users WHERE id = ?",
                (row, number) -> new Identity(row.getString("id"), true), id);
        if (rows.isEmpty()) throw new ApiException(ApiException.Code.SESSION_REQUIRED);
        return rows.get(0);
    }
}
