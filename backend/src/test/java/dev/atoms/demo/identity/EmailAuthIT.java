package dev.atoms.demo.identity;

import dev.atoms.demo.ApiBrowser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "EMAIL_AUTH_ENABLED=true", "SMTP_USERNAME=fixture@qq.com", "SMTP_PASSWORD=test-only",
        "EMAIL_CODE_HMAC_KEY=abababababababababababababababababababababababababababababababab",
        "EMAIL_MAX_SENDS_PER_HOUR=1000", "EMAIL_MAX_SENDS_PER_DAY=10000"})
@Import(EmailChallengeIT.MailConfig.class)
class EmailAuthIT {
    @LocalServerPort int port;
    @Autowired EmailChallengeIT.Capture mail;
    @Autowired JdbcTemplate jdbc;
    @Test void registersKeepsProjectsRotatesCredentialsAndLogsBackIntoSameAccount() throws Exception {
        var browser = new ApiBrowser(port);
        var original = browser.initialize();
        String owner = original.body().path("userId").asText(), email = owner + "@example.com";
        String oldCookie = original.raw().headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        String oldCsrf = browser.get("/api/csrf").body().path("token").asText();
        var project = browser.request("POST", "/api/projects", "{\"name\":\"注册前项目\"}", UUID.randomUUID().toString());
        assertThat(project.status()).isEqualTo(201);
        String path = "/api/projects/" + project.body().path("id").asText();
        assertThat(browser.get("/api/auth/methods").body().path("emailCodeEnabled").asBoolean()).isTrue();
        var sent = send(browser, email); assertThat(sent.status()).isEqualTo(201);
        var verified = verify(browser, sent.body().path("challengeId").asText(), mail.codes.get(email));
        assertThat(verified.status()).isEqualTo(200);
        assertThat(verified.body().path("userId").asText()).isEqualTo(owner);
        assertThat(verified.body().path("isGuest").asBoolean()).isFalse();
        assertThat(verified.raw().headers().firstValue("set-cookie").orElseThrow()).doesNotContain(oldCookie);
        assertThat(browser.request("DELETE", "/api/session", null, null, oldCsrf).status()).isEqualTo(403);
        browser.refreshCsrf();
        assertThat(browser.get(path).status()).isEqualTo(200);
        var replay = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:" + port + "/api/me")).header("Cookie", oldCookie).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(browser.request("DELETE", "/api/session", null, null).status()).isEqualTo(204);
        assertThat(browser.get("/api/me").status()).isEqualTo(401);
        var other = new ApiBrowser(port); other.initialize();
        assertThat(other.get(path).status()).isEqualTo(404);
        jdbc.update("UPDATE email_auth_budgets SET expires_at = now() - interval '1 second' WHERE budget_key = ?",
                EmailCode.digest("ab".repeat(32), "budget", "send-cooldown-email", email));
        var login = send(other, email); assertThat(login.status()).isEqualTo(201);
        assertThat(verify(other, login.body().path("challengeId").asText(), mail.codes.get(email)).body().path("userId").asText()).isEqualTo(owner);
        other.refreshCsrf();
        assertThat(other.get(path).status()).isEqualTo(200);
    }
    @Test void missingCsrfBadInputsAndRepeatedSendAreRejected() throws Exception {
        var browser = new ApiBrowser(port); browser.initialize(); String email = UUID.randomUUID() + "@example.com";
        assertThat(browser.request("POST", "/api/auth/email/challenges", "{\"email\":\"" + email + "\"}", null, null).status()).isEqualTo(403);
        assertThat(browser.request("POST", "/api/auth/email/challenges", "{\"email\":\"" + email + "\",\"ownerId\":\"x\"}", null).status()).isEqualTo(422);
        assertThat(send(browser, email).status()).isEqualTo(201);
        var limited = send(browser, email);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.raw().headers().firstValue("Retry-After")).isPresent();
        assertThat(limited.body().toString()).doesNotContain(email);
    }
    private ApiBrowser.Reply send(ApiBrowser browser, String email) throws Exception {
        return browser.request("POST", "/api/auth/email/challenges", "{\"email\":\"" + email + "\"}", null);
    }
    private ApiBrowser.Reply verify(ApiBrowser browser, String id, String code) throws Exception {
        return browser.request("POST", "/api/auth/email/verifications", "{\"challengeId\":\"" + id + "\",\"code\":\"" + code + "\"}", null);
    }
}
