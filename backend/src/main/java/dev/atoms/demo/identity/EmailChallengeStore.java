package dev.atoms.demo.identity;

import dev.atoms.demo.common.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class EmailChallengeStore {
    private final JdbcTemplate jdbc;
    private final AuthTransactions transactions;
    private final AuthBudgetStore budgets;
    private final AccountService accounts;
    private final SessionRevocations revocations;
    private final EmailAuthSettings settings;
    EmailChallengeStore(JdbcTemplate jdbc, AuthTransactions transactions, AuthBudgetStore budgets,
                        AccountService accounts, SessionRevocations revocations, EmailAuthSettings settings) {
        this.jdbc = jdbc; this.transactions = transactions; this.budgets = budgets;
        this.accounts = accounts; this.revocations = revocations; this.settings = settings;
    }
    EmailAuthService.Challenge reserve(UUID owner, String session, String ip, String email, String code) {
        return transactions.run(() -> {
            requireGuest(owner, session);
            Instant now = Instant.now(), expires = now.plusSeconds(600);
            String sessionHash = SessionRevocations.hash(session);
            // A crashed/unknown send never becomes valid again, even if its late mail arrives.
            jdbc.update("UPDATE email_auth_challenges SET status = 'FAILED' WHERE session_hash = ? AND status = 'PENDING' AND created_at < ?",
                    sessionHash, Timestamp.from(now.minusSeconds(30)));
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM email_auth_challenges WHERE session_hash = ? AND status = 'PENDING')", Boolean.class, sessionHash)))
                throw new ApiException(409, "EMAIL_SEND_PENDING", "邮件正在发送，请稍候。");
            budgets.sending(email, session, ip, now);
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO email_auth_challenges(id, owner_id, session_hash, email, code_hash, status, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
                    """, id, owner, sessionHash, email, EmailCode.digest(settings.key(), id.toString(), email, code), Timestamp.from(now), Timestamp.from(expires));
            return new EmailAuthService.Challenge(id, expires, 60);
        });
    }
    void activate(UUID owner, String session, UUID id) {
        transactions.run(() -> {
            requireGuest(owner, session);
            int changed = jdbc.update("UPDATE email_auth_challenges SET status = 'SENT' WHERE id = ? AND owner_id = ? AND session_hash = ? AND status = 'PENDING' AND expires_at > now() AND created_at > now() - interval '30 seconds'",
                    id, owner, SessionRevocations.hash(session));
            if (changed != 1) throw new ApiException(503, "EMAIL_DELIVERY_UNCONFIRMED", "邮件发送未确认，请重新获取验证码。");
            jdbc.update("UPDATE email_auth_challenges SET status = 'REPLACED' WHERE session_hash = ? AND id <> ? AND status = 'SENT'", SessionRevocations.hash(session), id);
            return null;
        });
    }
    void failed(UUID id) { jdbc.update("UPDATE email_auth_challenges SET status = 'FAILED' WHERE id = ? AND status = 'PENDING'", id); }
    UUID verify(UUID owner, String session, String ip, UUID id, String code) {
        UUID user = transactions.run(() -> {
            requireGuest(owner, session);
            Instant now = Instant.now();
            budgets.verifying(session, ip, now);
            var rows = jdbc.query("SELECT email, code_hash, status, attempts, expires_at FROM email_auth_challenges WHERE id = ? AND owner_id = ? AND session_hash = ?",
                    (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getTimestamp(5).toInstant()), id, owner, SessionRevocations.hash(session));
            if (rows.isEmpty()) return null;
            Stored stored = rows.get(0);
            budgets.verifyingEmail(stored.email, now);
            if (!stored.status.equals("SENT") || stored.attempts >= 5 || !stored.expires.isAfter(now)) return null;
            if (!EmailCode.matches(stored.hash, EmailCode.digest(settings.key(), id.toString(), stored.email, code))) {
                jdbc.update("UPDATE email_auth_challenges SET attempts = attempts + 1 WHERE id = ?", id);
                return null; // Commit failed-attempt counters before turning the result into an HTTP error.
            }
            jdbc.update("UPDATE email_auth_challenges SET status = 'CONSUMED' WHERE id = ?", id);
            UUID target = accounts.claim(owner, stored.email);
            revocations.revoke(session);
            return target;
        });
        if (user == null) throw new ApiException(422, "EMAIL_CODE_INVALID", "验证码无效、已过期或已使用，请重新获取。");
        return user;
    }
    private void requireGuest(UUID owner, String session) {
        if (revocations.revoked(session)) throw new ApiException(401, "SESSION_REQUIRED", "会话已失效，请重新连接。");
        if (accounts.exists(owner)) throw new ApiException(409, "ACCOUNT_ALREADY_SIGNED_IN", "请先退出当前账号。");
    }
    private record Stored(String email, String hash, String status, int attempts, Instant expires) {}
}
