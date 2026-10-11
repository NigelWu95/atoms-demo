package dev.atoms.standalone;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class SessionController {
    private static final Logger log = LoggerFactory.getLogger(SessionController.class);
    private final GuestIdentityService guests;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrf;

    SessionController(GuestIdentityService guests, HttpSessionSecurityContextRepository contexts, HttpSessionCsrfTokenRepository csrf) {
        this.guests = guests;
        this.contexts = contexts;
        this.csrf = csrf;
    }

    record CsrfResponse(String token, String headerName) {}

    // Return the masked token in JSON; do not put it in a JS-readable cookie or URL.
    // https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html
    @GetMapping("/api/csrf")
    CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getToken(), token.getHeaderName()); }

    @PostMapping("/api/session")
    GuestIdentityService.Identity initialize(@RequestBody JsonNode body, Principal principal,
                                             HttpServletRequest request, HttpServletResponse response) {
        if (!body.isObject() || !body.isEmpty()) throw new ApiException(ApiException.Code.INVALID_INPUT);
        if (principal != null) return guests.get(principal.getName());
        var guest = guests.create();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(guest.userId(), null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
        // Custom authentication must explicitly rotate credentials and persist the context.
        // https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, request, response);
        new CsrfAuthenticationStrategy(csrf).onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        log.atInfo().addKeyValue("event", "guest_initialized").addKeyValue("entryPoint", "session")
                .addKeyValue("requestId", ApiError.requestId(request)).log("Guest identity prepared; response awaits session persistence");
        return guest;
    }
}
