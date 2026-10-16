package dev.atoms.demo.identity;

import dev.atoms.demo.common.ApiException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(properties = {"EMAIL_AUTH_ENABLED=true", "SMTP_USERNAME=fixture@qq.com", "SMTP_PASSWORD=test-only",
        "EMAIL_CODE_HMAC_KEY=abababababababababababababababababababababababababababababababab",
        "EMAIL_MAX_SENDS_PER_HOUR=1000", "EMAIL_MAX_SENDS_PER_DAY=10000"})
@Import(EmailChallengeIT.MailConfig.class)
class EmailChallengeIT {
    private static final String KEY = UUID.randomUUID().toString().replace("-", "").repeat(2);
    @org.springframework.test.context.DynamicPropertySource
    static void isolatedBudgets(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("EMAIL_CODE_HMAC_KEY", () -> KEY);
    }
    @Autowired EmailAuthService service;
    @Autowired GuestIdentityService guests;
    @Autowired JdbcTemplate jdbc;
    @Autowired Capture mail;
    @BeforeEach void reset() { mail.fail = false; mail.codes.clear(); }
    static class Capture implements EmailCodeMailer {
        final ConcurrentHashMap<String, String> codes = new ConcurrentHashMap<>();
        volatile boolean fail;
        public void send(String email, String code) {
            if (fail) throw new ApiException(503, "EMAIL_DELIVERY_UNCONFIRMED", "test failure");
            codes.put(email, code);
        }
    }
    @TestConfiguration static class MailConfig { @Bean @Primary Capture capture() { return new Capture(); } }
    UUID guest() { return UUID.fromString(guests.create().userId()); }
    @Test void storesOnlyDigestAndConsumesOnceWhilePreservingOwner() {
        UUID owner = guest(); String session = UUID.randomUUID().toString(), email = owner + "@example.com";
        var sent = service.send(owner, session, session, email);
        String code = mail.codes.get(email);
        assertThat(jdbc.queryForObject("SELECT code_hash FROM email_auth_challenges WHERE id = ?", String.class, sent.challengeId()))
                .hasSize(64).isNotEqualTo(code);
        assertThat(service.verify(owner, session, session, sent.challengeId(), code)).isEqualTo(owner);
        assertThat(guests.get(owner.toString()).isGuest()).isFalse();
        assertThatThrownBy(() -> service.verify(owner, session, session, sent.challengeId(), code)).isInstanceOf(ApiException.class);
    }
    @Test void foreignSessionCannotConsumeAndFiveWrongAttemptsRemainConsumedAfterErrors() {
        UUID owner = guest(); String session = UUID.randomUUID().toString(), email = owner + "@example.com";
        var sent = service.send(owner, session, session, email); String code = mail.codes.get(email);
        assertThatThrownBy(() -> service.verify(owner, "foreign", "foreign", sent.challengeId(), code)).isInstanceOf(ApiException.class);
        String wrong = code.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++) assertThatThrownBy(() -> service.verify(owner, session, session, sent.challengeId(), wrong)).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT attempts FROM email_auth_challenges WHERE id = ?", Integer.class, sent.challengeId())).isEqualTo(5);
        assertThatThrownBy(() -> service.verify(owner, session, session, sent.challengeId(), code)).isInstanceOf(ApiException.class);
        assertThat(guests.get(owner.toString()).isGuest()).isTrue();
    }
    @Test void expiredAndFailedDeliveryNeverCreateAnAccount() {
        UUID owner = guest(); String session = UUID.randomUUID().toString(), email = owner + "@example.com";
        var sent = service.send(owner, session, session, email);
        jdbc.update("UPDATE email_auth_challenges SET expires_at = now() - interval '1 second' WHERE id = ?", sent.challengeId());
        assertThatThrownBy(() -> service.verify(owner, session, session, sent.challengeId(), mail.codes.get(email))).isInstanceOf(ApiException.class);
        UUID failed = guest(); mail.fail = true;
        assertThatThrownBy(() -> service.send(failed, failed.toString(), failed.toString(), failed + "@example.com")).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM email_auth_challenges WHERE owner_id = ?", String.class, failed)).isEqualTo("FAILED");
        assertThat(guests.get(failed.toString()).isGuest()).isTrue();
    }
    @Test void concurrentVerificationOfOneCodeHasOnlyOneWinner() throws Exception {
        UUID owner = guest(); String session = UUID.randomUUID().toString(), email = owner + "@example.com";
        var sent = service.send(owner, session, session, email); String code = mail.codes.get(email);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> verify = () -> { start.await(); try { service.verify(owner, session, session, sent.challengeId(), code); return true; } catch (ApiException expected) { return false; } };
            var first = pool.submit(verify); var second = pool.submit(verify); start.countDown();
            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM email_identities WHERE email = ?", Integer.class, email)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void failedResendPreservesPriorCodeAndSuccessfulResendReplacesOnlyItsOwnSession() {
        UUID owner = guest(); String session = UUID.randomUUID().toString(), email = owner + "@example.com";
        var original = service.send(owner, session, session, email); String originalCode = mail.codes.get(email);
        cooldown(session, email); mail.fail = true;
        assertThatThrownBy(() -> service.send(owner, session, session, email)).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM email_auth_challenges WHERE id = ?", String.class, original.challengeId())).isEqualTo("SENT");
        mail.fail = false; cooldown(session, email);
        UUID other = guest(); String otherSession = UUID.randomUUID().toString();
        var otherCode = service.send(other, otherSession, otherSession, email);
        cooldown(session, email);
        var replacement = service.send(owner, session, session, email);
        assertThat(jdbc.queryForObject("SELECT status FROM email_auth_challenges WHERE id = ?", String.class, otherCode.challengeId())).isEqualTo("SENT");
        assertThatThrownBy(() -> service.verify(owner, session, session, original.challengeId(), originalCode)).isInstanceOf(ApiException.class);
        assertThat(service.verify(owner, session, session, replacement.challengeId(), mail.codes.get(email))).isEqualTo(owner);
    }
    private void cooldown(String session, String email) {
        for (String key : new String[]{EmailCode.digest(KEY, "budget", "send-cooldown-session", session), EmailCode.digest(KEY, "budget", "send-cooldown-email", email)})
            jdbc.update("UPDATE email_auth_budgets SET expires_at = now() - interval '1 second' WHERE budget_key = ?", key);
    }
}
