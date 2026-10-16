package dev.atoms.demo.identity;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class AuthBudgetStore {
    private final JdbcTemplate jdbc;
    private final EmailAuthSettings settings;
    AuthBudgetStore(JdbcTemplate jdbc, EmailAuthSettings settings) { this.jdbc = jdbc; this.settings = settings; }
    // Must be called inside AuthTransactions. A zero-sized window is an elapsed 60-second cooldown.
    void take(String kind, String value, int seconds, int maximum, Instant now) {
        String key = EmailCode.digest(settings.key(), "budget", kind, value);
        long start = seconds == 0 ? 0 : Math.floorDiv(now.getEpochSecond(), seconds) * seconds;
        Instant expires = seconds == 0 ? now.plusSeconds(60) : Instant.ofEpochSecond(start + seconds);
        var rows = jdbc.query("SELECT used, expires_at FROM email_auth_budgets WHERE budget_key = ? AND window_start = ?",
                (rs, row) -> new Budget(rs.getInt(1), rs.getTimestamp(2).toInstant()), key, start);
        if (!rows.isEmpty() && rows.get(0).expires.isAfter(now) && rows.get(0).used >= maximum) {
            throw new AuthRateLimited(Duration.between(now, rows.get(0).expires).toSeconds() + 1);
        }
        jdbc.update("""
                INSERT INTO email_auth_budgets(budget_key, window_start, used, expires_at) VALUES (?, ?, 1, ?)
                ON CONFLICT (budget_key, window_start) DO UPDATE SET
                used = CASE WHEN email_auth_budgets.expires_at <= ? THEN 1 ELSE email_auth_budgets.used + 1 END,
                expires_at = CASE WHEN email_auth_budgets.expires_at <= ? THEN EXCLUDED.expires_at ELSE email_auth_budgets.expires_at END
                """, key, start, Timestamp.from(expires), Timestamp.from(now), Timestamp.from(now));
    }
    void sending(String email, String session, String ip, Instant now) {
        take("send-cooldown-email", email, 0, 1, now);
        take("send-cooldown-session", session, 0, 1, now);
        take("send-email", email, 900, settings.emailSends, now);
        take("send-session", session, 900, settings.sessionSends, now);
        take("send-ip", ip, 900, settings.ipSends, now);
        take("send-global-hour", "all", 3600, settings.hourlySends, now);
        take("send-global-day", "all", 86400, settings.dailySends, now);
    }
    void verifying(String session, String ip, Instant now) {
        take("verify-session", session, 900, 10, now);
        take("verify-ip", ip, 900, 50, now);
    }
    void verifyingEmail(String email, Instant now) { take("verify-email", email, 900, 10, now); }
    private record Budget(int used, Instant expires) {}
}
