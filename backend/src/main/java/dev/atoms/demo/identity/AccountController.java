package dev.atoms.demo.identity;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Own-account fields are deliberately absent from the shared session DTO. */
@RestController
final class AccountController {
    private final JdbcTemplate jdbc;
    private final GuestIdentityService identities;
    AccountController(JdbcTemplate jdbc, GuestIdentityService identities) { this.jdbc = jdbc; this.identities = identities; }
    public record Profile(String userId, boolean isGuest, String displayName, String email,
                          boolean emailVerified, Instant createdAt) {}
    @GetMapping("/api/account")
    Object profile(Principal principal) {
        var identity = identities.get(principal.getName());
        if (identity.isGuest()) return identity;
        return jdbc.queryForObject("""
                SELECT a.user_id,a.display_name,a.created_at,e.email FROM user_accounts a
                JOIN email_identities e ON e.user_id=a.user_id WHERE a.user_id=?
                """, (rs, row) -> new Profile(rs.getString("user_id"), false, rs.getString("display_name"),
                rs.getString("email"), true, rs.getTimestamp("created_at").toInstant()), UUID.fromString(principal.getName()));
    }
}
