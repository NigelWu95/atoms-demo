package dev.atoms.demo.identity;

import dev.atoms.demo.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
public class SessionController {
    private final GuestIdentityService guests;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrf;
    public SessionController(GuestIdentityService guests, HttpSessionSecurityContextRepository contexts,
                             HttpSessionCsrfTokenRepository csrf) {
        this.guests = guests;
        this.contexts = contexts;
        this.csrf = csrf;
    }
    public record CsrfResponse(String token, String headerName) {}
    @GetMapping("/api/csrf")
    CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getToken(), token.getHeaderName()); }

    @PostMapping("/api/session")
    GuestIdentityService.Identity initialize(@RequestBody Map<String, Object> body, Principal principal,
                                            HttpServletRequest request, HttpServletResponse response) {
        if (!body.isEmpty()) throw new ApiException(422, "INVALID_INPUT", "此接口只接受空对象。");
        if (principal != null) return guests.get(principal.getName());
        var guest = guests.create();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(guest.userId(), null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
        // Spring Security 6 custom authentication must invoke strategies and explicitly save context.
        // https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, request, response);
        new CsrfAuthenticationStrategy(csrf).onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return guest;
    }
    @GetMapping("/api/me")
    GuestIdentityService.Identity me(Principal principal) { return guests.get(principal.getName()); }
}
