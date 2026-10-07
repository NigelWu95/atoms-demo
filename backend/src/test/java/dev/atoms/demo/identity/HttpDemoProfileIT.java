package dev.atoms.demo.identity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"test", "server"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.servlet.session.cookie.secure=false", "server.servlet.session.cookie.name=ATOMS_HTTP_SESSION",
    "PUBLIC_APP_ORIGIN=http://198.51.100.1:8443", "ALLOW_INSECURE_HTTP_PUBLIC_ORIGIN=true"
})
class HttpDemoProfileIT {
    @LocalServerPort int port;

    @Test void explicitHttpDemoKeepsHttpOnlySameSiteAndManagementSecurityHeaders() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/csrf")).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().firstValue("set-cookie").orElseThrow();
        assertThat(cookie.startsWith("ATOMS_HTTP_SESSION=") && cookie.contains("HttpOnly")
                && cookie.contains("SameSite=Lax") && !cookie.contains("Secure"))
                .as("HTTP demo cookie flags, without exposing the cookie value").isTrue();
        assertThat(response.headers().firstValue("strict-transport-security")).isEmpty();
        assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
        assertThat(response.headers().firstValue("x-frame-options")).contains("DENY");
    }
}
