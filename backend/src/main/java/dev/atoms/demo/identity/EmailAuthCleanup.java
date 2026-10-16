package dev.atoms.demo.identity;

import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded retention cleanup; never deletes users, accounts, projects or active credentials. */
@Component
final class EmailAuthCleanup {
    private final JdbcTemplate jdbc;
    EmailAuthCleanup(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    void clean() {
        try {
            int removed = jdbc.update("DELETE FROM email_auth_challenges WHERE id IN (SELECT id FROM email_auth_challenges WHERE expires_at < now() ORDER BY expires_at LIMIT 500)");
            removed += jdbc.update("DELETE FROM email_auth_budgets WHERE (budget_key,window_start) IN (SELECT budget_key,window_start FROM email_auth_budgets WHERE expires_at < now() ORDER BY expires_at LIMIT 500)");
            removed += jdbc.update("DELETE FROM revoked_auth_sessions WHERE session_hash IN (SELECT session_hash FROM revoked_auth_sessions WHERE expires_at < now() ORDER BY expires_at LIMIT 500)");
            if (removed > 0) LoggerFactory.getLogger(getClass()).info("event=email_auth_cleanup result=completed records={}", removed);
        } catch (DataAccessException unavailable) {
            LoggerFactory.getLogger(getClass()).warn("event=email_auth_cleanup result=database_unavailable");
        }
    }
}
