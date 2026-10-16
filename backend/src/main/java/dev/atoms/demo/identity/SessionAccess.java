package dev.atoms.demo.identity;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Service;

/** Checks both credential revocation and the assurance level, never promotes a guest context. */
@Service
public class SessionAccess {
    private final SessionRevocations revocations;
    private final AccountService accounts;
    private final JdbcIndexedSessionRepository sessions;
    public SessionAccess(SessionRevocations revocations, AccountService accounts, JdbcIndexedSessionRepository sessions) {
        this.revocations = revocations; this.accounts = accounts; this.sessions = sessions;
    }
    public boolean valid(String sessionId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || revocations.revoked(sessionId)) return false;
        UUID owner;
        try { owner = UUID.fromString(auth.getName()); } catch (IllegalArgumentException invalid) { return false; }
        String role = accounts.exists(owner) ? "ROLE_USER" : "ROLE_GUEST";
        return auth.getAuthorities().stream().anyMatch(value -> value.getAuthority().equals(role));
    }
    public boolean streamValid(String sessionId, UUID owner) {
        // Read-only: an open SSE connection must not extend session lifetime.
        org.springframework.session.Session session = sessions.findById(sessionId);
        if (session == null || session.isExpired()) return false;
        SecurityContext context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return context != null && context.getAuthentication() != null
                && owner.toString().equals(context.getAuthentication().getName()) && valid(sessionId, context.getAuthentication());
    }
}
