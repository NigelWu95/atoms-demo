package dev.atoms.demo.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SessionRevocations {
    private final JdbcTemplate jdbc;
    public SessionRevocations(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    static String hash(String sessionId) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public boolean revoked(String sessionId) {
        return sessionId == null || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM revoked_auth_sessions WHERE session_hash = ? AND expires_at > now())",
                Boolean.class, hash(sessionId)));
    }
    void revoke(String sessionId) {
        jdbc.update("INSERT INTO revoked_auth_sessions(session_hash, expires_at) VALUES (?, now() + interval '14 days') ON CONFLICT DO NOTHING", hash(sessionId));
        jdbc.update("UPDATE email_auth_challenges SET status = 'REPLACED' WHERE session_hash = ? AND status IN ('SENT','PENDING')", hash(sessionId));
    }
}
