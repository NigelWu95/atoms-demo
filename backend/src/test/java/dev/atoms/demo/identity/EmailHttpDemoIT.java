package dev.atoms.demo.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

@ActiveProfiles("test")
@SpringBootTest(properties = {"EMAIL_AUTH_ENABLED=true", "EMAIL_AUTH_ALLOW_INSECURE_HTTP=true",
        "SMTP_USERNAME=fixture@qq.com", "SMTP_PASSWORD=test-only"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(EmailChallengeIT.MailConfig.class)
class EmailHttpDemoIT {
    private static final String KEY = UUID.randomUUID().toString().replace("-", "").repeat(2);
    @DynamicPropertySource static void isolatedBudgets(DynamicPropertyRegistry properties) {
        properties.add("EMAIL_CODE_HMAC_KEY", () -> KEY);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EmailChallengeIT.Capture mail;

    @Test void publicHttpOptInSupportsLoginAuthenticatedRequestsAndLogoutWithoutDroppingGuards() throws Exception {
        var browser = new Browser();
        browser.refreshCsrf();
        var guest = browser.call("POST", "/api/session", "{}");
        assertThat(guest.getStatus()).isEqualTo(200);
        String owner = body(guest).path("userId").asText();
        browser.refreshCsrf();
        var methods = browser.call("GET", "/api/auth/methods", null);
        assertThat(body(methods).path("emailCodeEnabled").asBoolean()).isTrue();

        String email = UUID.randomUUID() + "@example.com", payload = "{\"email\":\"" + email + "\"}";
        String csrf = browser.csrf; browser.csrf = null;
        assertThat(browser.call("POST", "/api/auth/email/challenges", payload).getStatus()).isEqualTo(403);
        browser.csrf = csrf;
        var sent = browser.call("POST", "/api/auth/email/challenges", payload);
        assertThat(sent.getStatus()).isEqualTo(201);
        assertThat(browser.call("POST", "/api/auth/email/challenges", payload).getStatus()).isEqualTo(429);
        String challenge = body(sent).path("challengeId").asText(), code = mail.codes.get(email);
        String wrong = code.equals("000000") ? "000001" : "000000";
        assertThat(browser.verify(challenge, wrong).getStatus()).isEqualTo(422);
        assertThat(body(browser.call("GET", "/api/me", null)).path("isGuest").asBoolean()).isTrue();
        var guestCookie = browser.cookie;
        var verified = browser.verify(challenge, code);
        assertThat(verified.getStatus()).isEqualTo(200);
        assertThat(body(verified).path("isGuest").asBoolean()).isFalse();
        assertThat(browser.cookie.getValue().equals(guestCookie.getValue())).isFalse();
        assertThat(browser.cookie.isHttpOnly()).isTrue();
        assertThat(browser.cookie.getSecure()).isFalse();
        assertThat(browser.cookie.getSameSite()).isEqualToIgnoringCase("Lax");
        assertThat(verified.getHeader("Cache-Control")).contains("no-store");

        // A successful verification must also survive the next authenticated request.
        var me = browser.call("GET", "/api/me", null);
        assertThat(me.getStatus()).isEqualTo(200);
        assertThat(body(me).path("userId").asText()).isEqualTo(owner);
        assertThat(browser.call("GET", "/api/projects", null).getStatus()).isEqualTo(200);
        assertThat(browser.call("DELETE", "/api/session", null).getStatus()).isEqualTo(403);
        browser.refreshCsrf();
        var loginCookie = browser.cookie;
        assertThat(browser.call("DELETE", "/api/session", null).getStatus()).isEqualTo(204);
        browser.cookie = loginCookie;
        assertThat(browser.call("GET", "/api/me", null).getStatus()).isEqualTo(401);
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsByteArray());
    }
    private class Browser {
        MockCookie cookie;
        String csrf;
        MockHttpServletResponse call(String method, String path, String payload) throws Exception {
            var request = request(HttpMethod.valueOf(method), path).with(value -> {
                value.setServerName("198.51.100.20"); value.setRemoteAddr("198.51.100.10");
                value.setSecure(false); return value;
            }).contentType("application/json");
            if (cookie != null) request.cookie(cookie);
            if (csrf != null && !method.equals("GET")) request.header("X-CSRF-TOKEN", csrf);
            if (payload != null) request.content(payload);
            var response = mvc.perform(request).andReturn().getResponse();
            for (String header : response.getHeaders("Set-Cookie")) {
                var parsed = MockCookie.parse(header);
                if (parsed.getName().equals("ATOMS_SESSION")) cookie = parsed;
            }
            return response;
        }
        void refreshCsrf() throws Exception { csrf = body(call("GET", "/api/csrf", null)).path("token").asText(); }
        MockHttpServletResponse verify(String challenge, String code) throws Exception {
            return call("POST", "/api/auth/email/verifications", "{\"challengeId\":\"" + challenge + "\",\"code\":\"" + code + "\"}");
        }
    }
}
