package dev.atoms.demo.identity;

import dev.atoms.demo.common.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class PasswordChallenges {
    private final JdbcTemplate jdbc;
    private final AuthTransactions tx;
    private final AuthBudgetStore budgets;
    private final PasswordCredentials credentials;
    private final EmailAuthSettings settings;
    private final EmailCodeMailer mail;
    PasswordChallenges(JdbcTemplate jdbc, AuthTransactions tx, AuthBudgetStore budgets, PasswordCredentials credentials,
                       EmailAuthSettings settings, EmailCodeMailer mail) {
        this.jdbc=jdbc; this.tx=tx; this.budgets=budgets; this.credentials=credentials; this.settings=settings; this.mail=mail;
    }
    EmailAuthService.Challenge send(UUID owner,String session,String ip,long version) {
        String code=EmailCode.generate(); UUID id=UUID.randomUUID(); Instant now=Instant.now(),expires=now.plusSeconds(600);
        String email=tx.run(()->{
            var account=credentials.requireOwner(owner,session,version);
            requireUnset(account);
            String sessionHash=SessionRevocations.hash(session);
            jdbc.update("UPDATE email_auth_challenges SET status='FAILED' WHERE session_hash=? AND purpose='PASSWORD_SETUP' AND status='PENDING' AND created_at < now()-interval '30 seconds'",sessionHash);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM email_auth_challenges WHERE session_hash=? AND status='PENDING')",Boolean.class,sessionHash)))
                throw new ApiException(409,"EMAIL_SEND_PENDING","邮件正在发送，请稍候。");
            budgets.sending(account.email,session,ip,now);
            jdbc.update("""
                    INSERT INTO email_auth_challenges(id,owner_id,session_hash,email,code_hash,status,created_at,expires_at,purpose,credential_version)
                    VALUES (?,?,?,?,?,'PENDING',?,?,'PASSWORD_SETUP',?)
                    """,id,owner,sessionHash,account.email,digest(id,account.email,code),Timestamp.from(now),Timestamp.from(expires),version);
            return account.email;
        });
        try {
            mail.send(email,code);
            tx.run(()->{
                requireUnset(credentials.requireOwner(owner,session,version));
                int count=jdbc.update("UPDATE email_auth_challenges SET status='SENT' WHERE id=? AND status='PENDING' AND created_at>now()-interval '30 seconds' AND expires_at>now()",id);
                if(count!=1) throw new ApiException(503,"EMAIL_DELIVERY_UNCONFIRMED","邮件发送未确认，请重新获取。");
                jdbc.update("UPDATE email_auth_challenges SET status='REPLACED' WHERE owner_id=? AND session_hash=? AND purpose='PASSWORD_SETUP' AND id<>? AND status='SENT'",owner,SessionRevocations.hash(session),id);
                return null;
            });
            return new EmailAuthService.Challenge(id,expires,60);
        } catch(RuntimeException failure) {
            jdbc.update("UPDATE email_auth_challenges SET status='FAILED' WHERE id=? AND status='PENDING'",id);
            throw failure;
        }
    }
    // Caller owns the short auth transaction; invalid attempts must commit before throwing.
    boolean valid(PasswordCredentials.Snapshot account,String session,UUID id,String code,boolean countAttempt) {
        var rows=jdbc.query("""
                SELECT code_hash FROM email_auth_challenges WHERE id=? AND owner_id=? AND session_hash=?
                AND email=? AND purpose='PASSWORD_SETUP' AND credential_version=? AND status='SENT' AND attempts<5 AND expires_at>now()
                """,(rs,row)->rs.getString(1),id,account.owner,SessionRevocations.hash(session),account.email,account.version);
        if(rows.isEmpty()) return false;
        boolean matches=EmailCode.matches(rows.get(0),digest(id,account.email,code));
        if(!matches && countAttempt) jdbc.update("UPDATE email_auth_challenges SET attempts=attempts+1 WHERE id=?",id);
        return matches;
    }
    void consume(UUID id) { jdbc.update("UPDATE email_auth_challenges SET status='CONSUMED' WHERE id=?",id); }
    private String digest(UUID id,String email,String code) { return EmailCode.digest(settings.key(),"PASSWORD_SETUP",id.toString(),email,code); }
    static void requireUnset(PasswordCredentials.Snapshot account) {
        if(account.hash!=null) throw new ApiException(409,"PASSWORD_ALREADY_CONFIGURED","已设置密码，请使用修改密码。");
    }
}
