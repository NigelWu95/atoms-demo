package dev.atoms.fixtures;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.identity.EmailCodeMailer;
import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Test classpath only. Never invokes SMTP; fake mail can only be read by its challenge owner. */
@RestController
public final class EmailFixture implements EmailCodeMailer {
    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<String, Message> mailbox = new ConcurrentHashMap<>();
    EmailFixture(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void send(String email, String code) {
        mailbox.entrySet().removeIf(entry -> entry.getValue().expires.isBefore(Instant.now()));
        if (mailbox.size() >= 500) throw new ApiException(503, "FIXTURE_MAIL_FULL", "Test mailbox full");
        mailbox.put(email, new Message(code, Instant.now().plusSeconds(600)));
    }
    @GetMapping("/api/test/email-codes/{id}")
    Map<String, String> read(Principal principal, @PathVariable UUID id) {
        var emails = jdbc.query("SELECT email FROM email_auth_challenges WHERE id = ? AND owner_id = ? AND status = 'SENT' AND expires_at > now()",
                (rs, row) -> rs.getString(1), id, UUID.fromString(principal.getName()));
        Message message = emails.isEmpty() ? null : mailbox.get(emails.get(0));
        if (message == null || message.expires.isBefore(Instant.now())) throw new ApiException(404, "FIXTURE_MAIL_MISSING", "No test mail");
        return Map.of("code", message.code);
    }
    private record Message(String code, Instant expires) {}
}
