package dev.atoms.demo.identity;

import dev.atoms.demo.common.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;
    public AccountService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    // Caller holds the short authentication transaction lock after verifying the code.
    UUID claim(UUID guest, String email) {
        if (exists(guest)) throw new ApiException(409, "ACCOUNT_ALREADY_SIGNED_IN", "请先退出当前账号。");
        var existing = jdbc.query("SELECT user_id FROM email_identities WHERE email = ?",
                (rs, row) -> rs.getObject(1, UUID.class), email);
        if (!existing.isEmpty()) return existing.get(0);
        jdbc.update("INSERT INTO user_accounts(user_id) VALUES (?)", guest);
        jdbc.update("INSERT INTO email_identities(email, user_id) VALUES (?, ?)", email, guest);
        return guest;
    }
    boolean exists(UUID user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_accounts WHERE user_id = ?)", Boolean.class, user));
    }
    long version(UUID user) {
        return jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?", Long.class, user);
    }
}
