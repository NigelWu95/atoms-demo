package dev.atoms.demo.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiError;
import dev.atoms.demo.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class EmailAuthController {
    private final EmailAuthSettings settings;
    private final EmailAuthService emails;
    private final AccountSessions sessions;
    private final GuestIdentityService identities;
    private final ObjectMapper json;
    public EmailAuthController(EmailAuthSettings settings, EmailAuthService emails, AccountSessions sessions,
                               GuestIdentityService identities, ObjectMapper json) {
        this.settings = settings; this.emails = emails; this.sessions = sessions; this.identities = identities; this.json = json;
    }
    @GetMapping("/api/auth/methods")
    Map<String, Boolean> methods(HttpServletRequest request) { return Map.of("emailCodeEnabled", settings.enabled() && allowsTransport(request, settings),
            "passwordEnabled",settings.enabled() && allowsPasswordTransport(request,settings)); }

    @PostMapping(value = "/api/auth/email/challenges", consumes = "application/json")
    ResponseEntity<EmailAuthService.Challenge> send(Principal principal, HttpServletRequest request) {
        requireTransport(request); JsonNode body = body(request, Set.of("email"));
        long started = System.nanoTime();
        try {
            var sent = emails.send(UUID.fromString(principal.getName()), request.getSession().getId(), request.getRemoteAddr(), body.path("email").asText());
            event(request, "email_send", "accepted", started);
            return ResponseEntity.status(201).body(sent);
        } catch (ApiException failure) { event(request, "email_send", failure.code(), started); throw failure; }
    }
    @PostMapping(value = "/api/auth/email/verifications", consumes = "application/json")
    GuestIdentityService.Identity verify(Principal principal, HttpServletRequest request, HttpServletResponse response) {
        requireTransport(request); JsonNode body = body(request, Set.of("challengeId", "code"));
        UUID challenge;
        try {
            String raw = body.path("challengeId").asText(); challenge = UUID.fromString(raw);
            if (!challenge.toString().equals(raw)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new ApiException(422, "INVALID_INPUT", "验证码请求无效，请重新获取。"); }
        UUID owner = UUID.fromString(principal.getName()); long started = System.nanoTime();
        try {
            var target = emails.verifyAccount(owner, request.getSession().getId(), request.getRemoteAddr(), challenge, body.path("code").asText());
            sessions.authenticate(owner, target, request, response);
            event(request, "email_verify", "authenticated", started);
            return identities.get(target.userId().toString());
        } catch (ApiException failure) { event(request, "email_verify", failure.code(), started); throw failure; }
    }
    @DeleteMapping("/api/session")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(request, response);
        event(request, "session_logout", "completed", System.nanoTime());
        return ResponseEntity.noContent().build();
    }
    @ExceptionHandler(AuthRateLimited.class)
    ResponseEntity<ApiError> limited(AuthRateLimited failure, HttpServletRequest request) {
        return ResponseEntity.status(429).header("Retry-After", Long.toString(failure.retryAfter()))
                .body(ApiError.of(request, failure.code(), failure.getMessage(), false));
    }
    private JsonNode body(HttpServletRequest request, Set<String> fields) {
        return AuthRequest.fields(AuthRequest.read(request,json),fields);
    }
    static boolean allowsTransport(HttpServletRequest request, EmailAuthSettings settings) {
        return allowsTransport(request,settings.allowInsecureHttp());
    }
    static boolean allowsPasswordTransport(HttpServletRequest request,EmailAuthSettings settings) {
        return allowsTransport(request,settings) && allowsTransport(request,settings.allowInsecurePasswordHttp());
    }
    private static boolean allowsTransport(HttpServletRequest request,boolean allowInsecureHttp) {
        Set<String> loopback = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1");
        // Explicit demo opt-in only; it does not encrypt HTTP or change SMTP/cookie settings.
        return allowInsecureHttp || request.isSecure()
                || (loopback.contains(request.getServerName()) && loopback.contains(request.getRemoteAddr()));
    }
    private void requireTransport(HttpServletRequest request) {
        if (!allowsTransport(request, settings)) throw new ApiException(503, "EMAIL_AUTH_SECURE_TRANSPORT_REQUIRED", "请通过 HTTPS 使用邮箱注册登录。");
    }
    static void event(HttpServletRequest request, String event, String result, long started) {
        LoggerFactory.getLogger(EmailAuthController.class).info("event={} entryPoint=http requestId={} result={} durationMs={}",
                event, request.getAttribute("requestId"), result, (System.nanoTime() - started) / 1_000_000);
    }
}
