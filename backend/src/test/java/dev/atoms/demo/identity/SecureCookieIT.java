package dev.atoms.demo.identity;

import dev.atoms.demo.ApiBrowser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.session.cookie.secure=true")
class SecureCookieIT {
    @LocalServerPort int port;
    @Test
    void emitsSecureHttpOnlySameSiteCookieWhenHttpsConfigurationIsEnabled() throws Exception {
        var result = new ApiBrowser(port).get("/api/csrf");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.raw().headers().firstValue("set-cookie").orElseThrow())
                .contains("Secure", "HttpOnly", "SameSite=Lax", "Max-Age=604800");
    }
}
