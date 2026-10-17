package dev.atoms.demo.identity;

import dev.atoms.demo.ApiBrowser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "EMAIL_AUTH_ENABLED=true", "SMTP_USERNAME=fixture@qq.com", "SMTP_PASSWORD=test-only",
        "EMAIL_CODE_HMAC_KEY=cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"})
@Import(EmailChallengeIT.MailConfig.class)
class AccountProfileIT {
    @LocalServerPort int port;
    @Autowired EmailChallengeIT.Capture mail;
    @Test void returnsOnlyOwnVerifiedProfileWithoutExpandingSessionIdentity() throws Exception {
        var browser = new ApiBrowser(port);
        String owner = browser.initialize().body().path("userId").asText(), email = owner + "@example.com";
        var guest = browser.get("/api/account");
        assertThat(guest.status()).isEqualTo(200);
        assertThat(guest.body().path("isGuest").asBoolean()).isTrue();
        assertThat(guest.body().has("email")).isFalse();
        var sent = browser.request("POST", "/api/auth/email/challenges", "{\"email\":\"" + email + "\"}", null);
        assertThat(sent.status()).isEqualTo(201);
        assertThat(browser.request("POST", "/api/auth/email/verifications", "{\"challengeId\":\"" + sent.body().path("challengeId").asText()
                + "\",\"code\":\"" + mail.codes.get(email) + "\"}", null).status()).isEqualTo(200);
        var profile = browser.get("/api/account");
        assertThat(profile.status()).isEqualTo(200);
        assertThat(profile.body().path("email").asText()).isEqualTo(email);
        assertThat(profile.body().path("userId").asText()).isEqualTo(owner);
        assertThat(profile.body().path("emailVerified").asBoolean()).isTrue();
        assertThat(java.time.Instant.parse(profile.body().path("createdAt").asText())).isBeforeOrEqualTo(java.time.Instant.now());
        assertThat(profile.raw().headers().firstValue("cache-control").orElse("")).contains("no-store");
        assertThat(browser.get("/api/me").body().has("email")).isFalse();
        var stranger = new ApiBrowser(port); stranger.initialize();
        assertThat(stranger.get("/api/account?userId=" + owner).body().toString()).doesNotContain(email);
        assertThat(new ApiBrowser(port).get("/api/account").status()).isEqualTo(401);
    }
}
