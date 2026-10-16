package dev.atoms.demo.identity;

import dev.atoms.demo.common.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GuestIdentityService {
    private final JdbcTemplate jdbc;
    public GuestIdentityService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Identity(String userId, boolean isGuest, String displayName) {}
    public Identity create() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", id);
        return new Identity(id.toString(), true, "访客创作者");
    }
    public Identity get(String id) {
        var identities = jdbc.query("SELECT g.id, a.display_name FROM guest_users g LEFT JOIN user_accounts a ON a.user_id = g.id WHERE g.id = ?",
                (rs, row) -> new Identity(rs.getString("id"), rs.getString("display_name") == null,
                        rs.getString("display_name") == null ? "访客创作者" : rs.getString("display_name")), UUID.fromString(id));
        if (identities.isEmpty()) throw new ApiException(401, "SESSION_REQUIRED", "访客会话已失效，请重新连接。");
        return identities.get(0);
    }
}
