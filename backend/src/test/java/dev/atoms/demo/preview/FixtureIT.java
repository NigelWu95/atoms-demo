package dev.atoms.demo.preview;

import dev.atoms.demo.ApiBrowser;
import dev.atoms.fixtures.FixtureConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "fixture"})
@Import(FixtureConfiguration.class)
class FixtureIT {
    @LocalServerPort int port;
    @Test void testLoaderRequiresIdentityCsrfFixedInputAndIdempotency() throws Exception {
        var browser = new ApiBrowser(port);
        assertThat(browser.document("/test-lab").statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/test-fixtures").status()).isEqualTo(401);
        assertThat(browser.request("POST", "/api/test-fixtures", "{\"fixture\":\"counter\"}", "test").status()).isEqualTo(403);
        browser.initialize();
        String key = UUID.randomUUID().toString();
        var created = browser.request("POST", "/api/test-fixtures", "{\"fixture\":\"history\"}", key);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("testOnly").asBoolean()).isTrue();
        assertThat(created.body().path("version").path("versionNo").asInt()).isEqualTo(2);
        assertThat(browser.request("POST", "/api/test-fixtures", "{\"fixture\":\"history\"}", key).body()).isEqualTo(created.body());
        assertThat(browser.request("POST", "/api/test-fixtures", "{\"fixture\":\"counter\",\"files\":{}}", "invalid").status()).isEqualTo(422);
        assertThat(browser.request("POST", "/api/test-fixtures", "{\"fixture\":\"arbitrary\"}", "invalid").status()).isEqualTo(422);
        assertThat(browser.request("POST", "/api/test-fixtures", "{\"fixture\":\"counter\"}", null).status()).isEqualTo(422);
    }
}
