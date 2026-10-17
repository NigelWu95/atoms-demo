package dev.atoms.demo.identity;

import dev.atoms.demo.common.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class PasswordCredentials {
    private final JdbcTemplate jdbc;
    private final SessionRevocations revocations;
    PasswordCredentials(JdbcTemplate jdbc, SessionRevocations revocations) { this.jdbc=jdbc; this.revocations=revocations; }
    static final class Snapshot {
        final UUID owner; final String email, hash; final long version; final Instant updatedAt;
        Snapshot(UUID owner, String email, String hash, long version, Instant updatedAt) {
            this.owner=owner; this.email=email; this.hash=hash; this.version=version; this.updatedAt=updatedAt;
        }
    }
    private static final String SELECT = """
            SELECT a.user_id,e.email,a.auth_version,p.password_hash,p.updated_at FROM user_accounts a
            JOIN email_identities e ON e.user_id=a.user_id LEFT JOIN account_passwords p ON p.user_id=a.user_id
            """;
    Snapshot findEmail(String email) { return find("e.email",email); }
    Snapshot findOwner(UUID owner) { return find("a.user_id",owner); }
    private Snapshot find(String column, Object value) {
        var rows=jdbc.query(SELECT+" WHERE "+column+"=?", (rs,row)->new Snapshot(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getString(4),rs.getLong(3),rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant()),value);
        return rows.isEmpty()?null:rows.get(0);
    }
    Snapshot requireOwner(UUID owner,String session,long version) {
        requireSession(session);
        var value=findOwner(owner);
        if(value==null) throw new ApiException(409,"ACCOUNT_REQUIRED","请先使用邮箱注册或登录。");
        if(value.version!=version) throw new ApiException(401,"SESSION_REQUIRED","账号安全设置已更新，请重新登录。");
        return value;
    }
    void requireSession(String session) {
        if(revocations.revoked(session)) throw new ApiException(401,"SESSION_REQUIRED","会话已失效，请重新登录。");
    }
    VerifiedAccount save(Snapshot original,String hash,String session) {
        jdbc.update("""
                INSERT INTO account_passwords(user_id,password_hash) VALUES (?,?)
                ON CONFLICT (user_id) DO UPDATE SET password_hash=EXCLUDED.password_hash,updated_at=now()
                """,original.owner,hash);
        jdbc.update("UPDATE user_accounts SET auth_version=auth_version+1 WHERE user_id=?",original.owner);
        jdbc.update("UPDATE email_auth_challenges SET status='REPLACED' WHERE owner_id=? AND purpose='PASSWORD_SETUP' AND status IN ('SENT','PENDING')",original.owner);
        revocations.revoke(session);
        return new VerifiedAccount(original.owner, original.version+1);
    }
}
