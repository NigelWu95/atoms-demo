package dev.atoms.demo.identity;

import dev.atoms.demo.ApiBrowser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsAndRestoresGuestInJdbcAndSeparatesBrowsers() throws Exception {
        var browser = new ApiBrowser(port);
        var csrf = browser.get("/api/csrf");
        assertThat(csrf.status()).isEqualTo(200);
        assertThat(csrf.body().path("headerName").asText()).isEqualTo("X-CSRF-TOKEN");
        assertThat(csrf.raw().headers().firstValue("set-cookie").orElseThrow())
                .contains("HttpOnly", "SameSite=Lax", "Max-Age=").doesNotContain("Secure");
        var created = browser.initialize();
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.body().path("isGuest").asBoolean()).isTrue();
        assertThat(browser.get("/api/me").body()).isEqualTo(created.body());
        assertThat(browser.initialize().body()).isEqualTo(created.body());
        assertThat(new ApiBrowser(port).initialize().body().path("userId"))
                .isNotEqualTo(created.body().path("userId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?",
                Integer.class, created.body().path("userId").asText())).isEqualTo(1);
    }

    @Test
    void rejectsAnonymousReadsAndMissingOrWrongCsrfWithoutHtmlOrSecrets() throws Exception {
        var browser = new ApiBrowser(port);
        var anonymous = browser.get("/api/me");
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body().path("error").path("code").asText()).isEqualTo("SESSION_REQUIRED");
        browser.refreshCsrf();
        for (String token : new String[]{null, "incorrect"}) {
            var rejected = browser.request("POST", "/api/session", "{}", null, token);
            assertThat(rejected.status()).isEqualTo(403);
            assertThat(rejected.body().path("error").path("code").asText()).isEqualTo("CSRF_REJECTED");
            assertThat(rejected.body().path("requestId").asText()).isNotBlank();
            assertThat(rejected.raw().headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        }
    }
}
