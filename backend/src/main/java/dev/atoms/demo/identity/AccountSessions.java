package dev.atoms.demo.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Service;

@Service
final class AccountSessions {
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrf;
    private final SessionRevocations revocations;
    private final AuthTransactions transactions;
    private final String cookieName;
    AccountSessions(HttpSessionSecurityContextRepository contexts, HttpSessionCsrfTokenRepository csrf,
                    SessionRevocations revocations, AuthTransactions transactions, Environment env) {
        this.contexts = contexts; this.csrf = csrf; this.revocations = revocations; this.transactions = transactions;
        cookieName = env.getProperty("server.servlet.session.cookie.name", "ATOMS_SESSION");
    }
    void authenticate(UUID previous, UUID target, HttpServletRequest request, HttpServletResponse response) {
        if (!previous.equals(target)) request.getSession().invalidate();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(target.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        // https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html#store-authentication-manually
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, request, response);
        new CsrfAuthenticationStrategy(csrf).onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context); contexts.saveContext(context, request, response);
    }
    void logout(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session != null) {
            String id = session.getId();
            transactions.run(() -> { revocations.revoke(id); return null; });
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        response.addHeader("Set-Cookie", ResponseCookie.from(cookieName, "").path("/").maxAge(0)
                .httpOnly(true).sameSite("Lax").secure(request.isSecure()).build().toString());
    }
}
