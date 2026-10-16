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
    Map<String, Boolean> methods(HttpServletRequest request) { return Map.of("emailCodeEnabled", settings.enabled() && secure(request)); }

    @PostMapping(value = "/api/auth/email/challenges", consumes = "application/json")
    ResponseEntity<EmailAuthService.Challenge> send(Principal principal, HttpServletRequest request) {
        requireSecure(request); JsonNode body = body(request, Set.of("email"));
        long started = System.nanoTime();
        try {
            var sent = emails.send(UUID.fromString(principal.getName()), request.getSession().getId(), request.getRemoteAddr(), body.path("email").asText());
            event(request, "email_send", "accepted", started);
            return ResponseEntity.status(201).body(sent);
        } catch (ApiException failure) { event(request, "email_send", failure.code(), started); throw failure; }
    }
    @PostMapping(value = "/api/auth/email/verifications", consumes = "application/json")
    GuestIdentityService.Identity verify(Principal principal, HttpServletRequest request, HttpServletResponse response) {
        requireSecure(request); JsonNode body = body(request, Set.of("challengeId", "code"));
        UUID challenge;
        try {
            String raw = body.path("challengeId").asText(); challenge = UUID.fromString(raw);
            if (!challenge.toString().equals(raw)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new ApiException(422, "INVALID_INPUT", "验证码请求无效，请重新获取。"); }
        UUID owner = UUID.fromString(principal.getName()); long started = System.nanoTime();
        try {
            UUID target = emails.verify(owner, request.getSession().getId(), request.getRemoteAddr(), challenge, body.path("code").asText());
            sessions.authenticate(owner, target, request, response);
            event(request, "email_verify", "authenticated", started);
            return identities.get(target.toString());
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
        try {
            byte[] bytes = request.getInputStream().readNBytes(4097);
            if (bytes.length > 4096) throw new ApiException(413, "REQUEST_TOO_LARGE", "认证请求过大。");
            JsonNode value = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
            if (value == null || !value.isObject() || value.size() != fields.size()) throw invalid();
            for (String field : fields) if (!value.path(field).isTextual()) throw invalid();
            return value;
        } catch (IOException invalid) { throw invalid(); }
    }
    private static ApiException invalid() { return new ApiException(422, "INVALID_INPUT", "认证请求字段无效。"); }
    static boolean secure(HttpServletRequest request) {
        Set<String> loopback = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1");
        return request.isSecure() || (loopback.contains(request.getServerName()) && loopback.contains(request.getRemoteAddr()));
    }
    private static void requireSecure(HttpServletRequest request) {
        if (!secure(request)) throw new ApiException(503, "EMAIL_AUTH_SECURE_TRANSPORT_REQUIRED", "请通过 HTTPS 使用邮箱注册登录。");
    }
    private static void event(HttpServletRequest request, String event, String result, long started) {
        LoggerFactory.getLogger(EmailAuthController.class).info("event={} entryPoint=http requestId={} result={} durationMs={}",
                event, request.getAttribute("requestId"), result, (System.nanoTime() - started) / 1_000_000);
    }
}
