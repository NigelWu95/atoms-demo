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
        "EMAIL_MAX_SENDS_PER_HOUR=1000", "EMAIL_MAX_SENDS_PER_DAY=10000"})
@Import(EmailChallengeIT.MailConfig.class)
class PasswordAuthIT {
    private static final String KEY = UUID.randomUUID().toString().replace("-", "").repeat(2);
    private static final String PASSWORD = "test-only-long-passphrase", NEXT = "another-test-only-passphrase";
    @org.springframework.test.context.DynamicPropertySource
    static void settings(org.springframework.test.context.DynamicPropertyRegistry properties) { properties.add("EMAIL_CODE_HMAC_KEY", () -> KEY); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailChallengeIT.Capture mail;
    @Autowired SessionAccess access;
    @Autowired AuthTransactions tx;
    @Autowired PasswordCredentials credentials;
    @Autowired PasswordChallenges challenges;
    @Autowired PasswordPolicy policy;
    @Autowired AuthBudgetStore budgets;
    @Autowired SessionRevocations revocations;
    record User(ApiBrowser browser, String owner, String email) {}
    User register() throws Exception {
        var browser = new ApiBrowser(port);
        String owner = browser.initialize().body().path("userId").asText(), email = owner + "@example.com";
        var challenge = browser.request("POST", "/api/auth/email/challenges", "{\"email\":\"" + email + "\"}", null);
        assertThat(challenge.status()).isEqualTo(201);
        var result = browser.request("POST", "/api/auth/email/verifications", "{\"challengeId\":\"" + challenge.body().path("challengeId").asText()
                + "\",\"code\":\"" + mail.codes.get(email) + "\"}", null);
        assertThat(result.status()).isEqualTo(200); browser.refreshCsrf();
        jdbc.update("UPDATE email_auth_budgets SET expires_at=now()-interval '1 second' WHERE budget_key=?", EmailCode.digest(KEY,"budget","send-cooldown-email", email));
        return new User(browser, owner, email);
    }
    String challenge(User user) throws Exception {
        var reply = user.browser.request("POST", "/api/account/password/challenges", "{}", null);
        assertThat(reply.status()).isEqualTo(201);
        return reply.body().path("challengeId").asText();
    }
    ApiBrowser.Reply setup(User user, String id, String code) throws Exception {
        return user.browser.request("PUT", "/api/account/password", "{\"mode\":\"setup\",\"challengeId\":\"" + id + "\",\"code\":\"" + code + "\",\"newPassword\":\"" + PASSWORD + "\"}", null);
    }
    ApiBrowser.Reply login(ApiBrowser browser, String email, String password) throws Exception {
        return browser.request("POST", "/api/auth/password/verifications", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", null);
    }
    @Test void setupLoginChangeAndRevocationPreserveAccountOwnership() throws Exception {
        User user = register();
        var state = user.browser.get("/api/account/password");
        assertThat(state.status()).isEqualTo(200); assertThat(state.body().path("configured").asBoolean()).isFalse();
        String id = challenge(user);
        assertThat(setup(user, id, mail.codes.get(user.email)).status()).isEqualTo(200);
        user.browser.refreshCsrf();
        String stored = jdbc.queryForObject("SELECT password_hash FROM account_passwords WHERE user_id=?", String.class, UUID.fromString(user.owner));
        assertThat(stored).startsWith("{bcrypt}$2a$12$").doesNotContain(PASSWORD);
        var other = new ApiBrowser(port); other.initialize();
        assertThat(login(other, user.email, PASSWORD).body().path("userId").asText()).isEqualTo(user.owner); other.refreshCsrf();
        String oldSession = jdbc.queryForObject("SELECT session_id FROM spring_session WHERE principal_name=? ORDER BY creation_time DESC LIMIT 1", String.class, user.owner);
        assertThat(user.browser.request("PUT", "/api/account/password", "{\"mode\":\"change\",\"currentPassword\":\"wrong-long-passphrase\",\"newPassword\":\"" + NEXT + "\"}", null).status()).isEqualTo(422);
        var changed = user.browser.request("PUT", "/api/account/password", "{\"mode\":\"change\",\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEXT + "\"}", null);
        assertThat(changed.status()).isEqualTo(200); user.browser.refreshCsrf();
        assertThat(user.browser.get("/api/account").body().path("email").asText()).isEqualTo(user.email);
        assertThat(access.streamValid(oldSession,UUID.fromString(user.owner))).isFalse();
        assertThat(other.get("/api/me").status()).isEqualTo(401);
        var fresh = new ApiBrowser(port); fresh.initialize();
        assertThat(login(fresh, user.email, PASSWORD).status()).isEqualTo(422);
        assertThat(login(fresh, user.email, NEXT).status()).isEqualTo(200);
        assertThat(fresh.initialize().body().path("userId").asText()).isEqualTo(user.owner);
        assertThat(jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?", Long.class, UUID.fromString(user.owner))).isEqualTo(2);
    }
    @Test void invalidSetupCodesCommitAttemptCounters() throws Exception {
        User user = register(); String id = challenge(user), code = mail.codes.get(user.email);
        String wrong = code.equals("000000") ? "000001" : "000000";
        for (int i=0;i<5;i++) assertThat(setup(user,id,wrong).status()).isEqualTo(422);
        assertThat(setup(user,id,code).status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT attempts FROM email_auth_challenges WHERE id=?", Integer.class, UUID.fromString(id))).isEqualTo(5);
        assertThat(user.browser.get("/api/account/password").body().path("configured").asBoolean()).isFalse();
    }
    @Test void validSetupChallengeIsBoundToItsIssuingSessionEvenForTheSameAccount() throws Exception {
        User user=register(); String id=challenge(user),code=mail.codes.get(user.email);
        User foreign=register();
        assertThat(setup(foreign,id,code).status()).isEqualTo(422);
        jdbc.update("UPDATE email_auth_budgets SET expires_at=now()-interval '1 second' WHERE budget_key=?",EmailCode.digest(KEY,"budget","send-cooldown-email",user.email));
        var sibling=new ApiBrowser(port); sibling.initialize();
        var sent=sibling.request("POST","/api/auth/email/challenges","{\"email\":\""+user.email+"\"}",null);
        assertThat(sent.status()).isEqualTo(201);
        var signed=sibling.request("POST","/api/auth/email/verifications","{\"challengeId\":\""+sent.body().path("challengeId").asText()+"\",\"code\":\""+mail.codes.get(user.email)+"\"}",null);
        assertThat(signed.status()).isEqualTo(200); sibling.refreshCsrf();
        assertThat(signed.body().path("userId").asText()).isEqualTo(user.owner);
        assertThat(setup(new User(sibling,user.owner,user.email),id,code).status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT status FROM email_auth_challenges WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("SENT");
        assertThat(setup(user,id,code).status()).isEqualTo(200);
    }
    @Test void hidesAccountExistenceAndRequiresCsrfAndExactFields() throws Exception {
        User user = register(); var guest = new ApiBrowser(port); guest.initialize();
        var absent = login(guest, UUID.randomUUID()+"@example.com", PASSWORD);
        var noPassword = login(guest, user.email, PASSWORD);
        assertThat(absent.status()).isEqualTo(422); assertThat(noPassword.status()).isEqualTo(422);
        assertThat(absent.body().path("error")).isEqualTo(noPassword.body().path("error"));
        assertThat(guest.get("/api/me").body().path("isGuest").asBoolean()).isTrue();
        assertThat(guest.request("POST","/api/auth/password/verifications","{}",null,null).status()).isEqualTo(403);
        assertThat(guest.request("POST","/api/auth/password/verifications","{\"email\":\"a@example.com\",\"password\":\"x\",\"extra\":\"x\"}",null).status()).isEqualTo(422);
        assertThat(guest.request("POST","/api/auth/password/verifications","{\"email\":\"a@example.com\",\"email\":\"b@example.com\",\"password\":\"x\"}",null).status()).isEqualTo(422);
        assertThat(guest.request("POST","/api/auth/password/verifications","{} {}",null).status()).isEqualTo(422);
        assertThat(guest.request("POST","/api/auth/password/verifications","{\"email\":\""+"a".repeat(4096)+"\"}",null).status()).isEqualTo(413);
        assertThat(guest.request("POST","/api/account/password/challenges","{}",null).status()).isEqualTo(409);
        assertThat(guest.get("/api/auth/methods").body().path("passwordEnabled").asBoolean()).isTrue();
    }
    @Test void concurrentSetupConsumesOneChallengeAndIncrementsVersionOnlyOnce() throws Exception {
        User user=register(); String id=challenge(user), code=mail.codes.get(user.email);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> submit=()->{ start.await(); return setup(user,id,code).status(); };
            var first=pool.submit(submit); var second=pool.submit(submit); start.countDown();
            var outcomes=java.util.List.of(first.get(),second.get());
            assertThat(outcomes.stream().filter(status->status==200).count()).isEqualTo(1);
            assertThat(outcomes).allMatch(status->java.util.Set.of(200,401,409).contains(status));
            assertThat(jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?",Long.class,UUID.fromString(user.owner))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM email_auth_challenges WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("CONSUMED");
        } finally { pool.shutdownNow(); }
    }
    @Test void oldPasswordVerificationCannotAcquireNewVersionAfterConcurrentChange() throws Exception {
        User user=register(); String id=challenge(user);
        assertThat(setup(user,id,mail.codes.get(user.email)).status()).isEqualTo(200); user.browser.refreshCsrf();
        var guest=new ApiBrowser(port); String owner=guest.initialize().body().path("userId").asText();
        String session=jdbc.queryForObject("SELECT session_id FROM spring_session WHERE principal_name=?",String.class,owner);
        var checked=new java.util.concurrent.CountDownLatch(1); var resume=new java.util.concurrent.CountDownLatch(1);
        var controlled=org.mockito.Mockito.spy(policy);
        org.mockito.Mockito.doAnswer(invocation->{
            boolean valid=(boolean)invocation.callRealMethod(); checked.countDown();
            if(!resume.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test synchronization timeout");
            return valid;
        }).when(controlled).matches(org.mockito.ArgumentMatchers.eq(PASSWORD),org.mockito.ArgumentMatchers.anyString());
        var service=new PasswordService(tx,credentials,challenges,controlled,budgets,revocations);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result=pool.submit(()->service.login(UUID.fromString(owner),session,"test-race-ip",user.email,PASSWORD));
            assertThat(checked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(user.browser.request("PUT","/api/account/password","{\"mode\":\"change\",\"currentPassword\":\""+PASSWORD+"\",\"newPassword\":\""+NEXT+"\"}",null).status()).isEqualTo(200);
            resume.countDown();
            assertThatThrownBy(()->result.get(5,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(dev.atoms.demo.common.ApiException.class);
            assertThat(guest.get("/api/me").body().path("isGuest").asBoolean()).isTrue();
        } finally { resume.countDown(); pool.shutdownNow(); }
    }
    @Test void concurrentPasswordChangesCannotOverwriteTheWinner() throws Exception {
        User user=register(); String id=challenge(user);
        assertThat(setup(user,id,mail.codes.get(user.email)).status()).isEqualTo(200); user.browser.refreshCsrf();
        String session=jdbc.queryForObject("SELECT session_id FROM spring_session WHERE principal_name=?",String.class,user.owner);
        var bothChecked=new java.util.concurrent.CyclicBarrier(2);
        var controlled=org.mockito.Mockito.spy(policy);
        org.mockito.Mockito.doAnswer(invocation->{
            boolean valid=(boolean)invocation.callRealMethod();
            bothChecked.await(10,java.util.concurrent.TimeUnit.SECONDS);
            return valid;
        }).when(controlled).matches(org.mockito.ArgumentMatchers.eq(PASSWORD),org.mockito.ArgumentMatchers.anyString());
        var service=new PasswordService(tx,credentials,challenges,controlled,budgets,revocations);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> change=()->{
                try { service.change(UUID.fromString(user.owner),session,"test-change-ip",1,PASSWORD,NEXT); return true; }
                catch(dev.atoms.demo.common.ApiException rejected) { return false; }
            };
            var first=pool.submit(change); var second=pool.submit(change);
            assertThat(java.util.List.of(first.get(),second.get())).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?",Long.class,UUID.fromString(user.owner))).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }
    @Test void expiredOrWrongPurposeSetupChallengeCannotChangePassword() throws Exception {
        User user=register(); String id=challenge(user),code=mail.codes.get(user.email);
        jdbc.update("UPDATE email_auth_challenges SET purpose='LOGIN',credential_version=NULL WHERE id=?",UUID.fromString(id));
        assertThat(setup(user,id,code).status()).isEqualTo(422);
        jdbc.update("UPDATE email_auth_challenges SET purpose='PASSWORD_SETUP',credential_version=0,expires_at=now()-interval '1 second' WHERE id=?",UUID.fromString(id));
        assertThat(setup(user,id,code).status()).isEqualTo(422);
        assertThat(user.browser.get("/api/account/password").body().path("configured").asBoolean()).isFalse();
    }
    @Test void loginBudgetPersistsAcrossFailedResponsesAndNewSessions() throws Exception {
        String email=UUID.randomUUID()+"@example.com";
        for(int i=0;i<10;i++) { var browser=new ApiBrowser(port); browser.initialize(); assertThat(login(browser,email,PASSWORD).status()).isEqualTo(422); }
        var limited=new ApiBrowser(port); limited.initialize(); var reply=login(limited,email,PASSWORD);
        assertThat(reply.status()).isEqualTo(429); assertThat(reply.raw().headers().firstValue("Retry-After")).isPresent();
    }
}
