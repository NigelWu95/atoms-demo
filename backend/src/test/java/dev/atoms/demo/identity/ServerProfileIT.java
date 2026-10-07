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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerProfileIT {
    @LocalServerPort int port;

    @Test
    void preservesSecureSessionsBehindTheLocalTlsTerminatingProxy() throws Exception {
        var response = get("/api/csrf", true);
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().firstValue("set-cookie").orElseThrow();
        assertThat(cookie.contains("Secure") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax"))
                .as("session cookie has all required flags without logging its value").isTrue();
        assertThat(response.headers().firstValue("strict-transport-security").orElseThrow())
                .contains("max-age=");
    }

    @Test
    void keepsDirectBackendCookiesSecureAndDoesNotServeTheTestLab() throws Exception {
        assertThat(get("/api/csrf", false).headers().firstValue("set-cookie").orElseThrow().contains("Secure"))
                .as("direct backend cookie remains Secure").isTrue();
        assertThat(get("/test-lab", false).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path, boolean forwarded) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (forwarded) request.header("X-Forwarded-Proto", "https").header("X-Forwarded-For", "203.0.113.7");
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
