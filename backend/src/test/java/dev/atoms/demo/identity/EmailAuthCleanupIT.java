package dev.atoms.demo.identity;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class EmailAuthCleanupIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailAuthCleanup cleanup;
    @Autowired GuestIdentityService guests;
    @Test void deletesOnlyExpiredAuthenticationMetadataNotAccountsOrProjects() {
        UUID owner = UUID.fromString(guests.create().userId()), old = UUID.randomUUID(), fresh = UUID.randomUUID();
        for (UUID id : new UUID[]{old, fresh}) jdbc.update("""
                INSERT INTO email_auth_challenges(id,owner_id,session_hash,email,code_hash,status,created_at,expires_at)
                VALUES (?,?,?,'cleanup@example.com',?,'SENT',now(),now() + interval '10 minutes')
                """, id, owner, "a".repeat(64), "b".repeat(64));
        jdbc.update("UPDATE email_auth_challenges SET expires_at = now() - interval '2 days' WHERE id = ?", old);
        String key = SessionRevocations.hash(UUID.randomUUID().toString());
        jdbc.update("INSERT INTO email_auth_budgets(budget_key, window_start, used, expires_at) VALUES (?,0,1,now()-interval '2 days')", key);
        jdbc.update("INSERT INTO revoked_auth_sessions(session_hash, expires_at) VALUES (?,now()-interval '1 second')", key);
        cleanup.clean();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_auth_challenges WHERE id = ?", Integer.class, old)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_auth_challenges WHERE id = ?", Integer.class, fresh)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_auth_budgets WHERE budget_key = ?", Integer.class, key)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM revoked_auth_sessions WHERE session_hash = ?", Integer.class, key)).isZero();
        assertThat(guests.get(owner.toString()).userId()).isEqualTo(owner.toString());
    }
}
