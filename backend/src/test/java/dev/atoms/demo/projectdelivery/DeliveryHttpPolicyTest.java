package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class DeliveryHttpPolicyTest {
    private final String prefix = DeliveryGrants.PREFIX + "a".repeat(43) + "/";

    @Test void opaqueOriginGetsOnlyItsOwnAssetsAndApiAndNoCredentialPermission() {
        var request = request("GET", "assets/app.js"); request.addHeader("Origin", "null");
        request.addHeader("Host", "attacker.example"); request.addHeader("X-Forwarded-Host", "attacker.example");
        var response = new MockHttpServletResponse(); DeliveryHttpPolicy.closed(response);
        DeliveryHttpPolicy.authorized(request, response, DeliveryHttpPolicy.route(request), "https://platform.example");
        assertThat(response.getHeader("Content-Security-Policy")).contains("sandbox allow-scripts;", "frame-ancestors https://platform.example",
                "script-src https://platform.example" + prefix + "assets/", "connect-src https://platform.example" + prefix + "api/")
                .doesNotContain("allow-same-origin", "unsafe-eval", "attacker.example");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("null");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test void forwardsOnlyApprovedHeadersAndDefersActualJsonValidationUntilBodyArrives() {
        var request = request("PATCH", "api/todos/123");
        request.addHeader("Origin", "null"); request.addHeader("Content-Type", "application/json");
        request.addHeader("If-Match", "\"v1\""); request.addHeader("Cookie", "PLATFORM=canary");
        request.addHeader("Authorization", "Bearer canary"); request.addHeader("Referer", "https://platform.example/account");
        request.addHeader("X-CSRF-TOKEN", "canary"); request.addHeader("Forwarded", "for=canary");
        var input = DeliveryHttpPolicy.input(request, DeliveryHttpPolicy.route(request));
        assertThat(input.request("{}".getBytes(StandardCharsets.UTF_8)).headers())
                .isEqualTo(Map.of("content-type", "application/json", "if-match", "\"v1\""));
        assertThatThrownBy(() -> input.request("{invalid".getBytes(StandardCharsets.UTF_8))).isInstanceOf(ApiException.class);
        assertThat(input.toString()).doesNotContain("canary", "123");
    }

    @Test void preflightChecksActualTargetMethodHeadersAndOriginWithoutApplicationDispatch() {
        var request = request("OPTIONS", "api/todos"); request.addHeader("Origin", "null");
        request.addHeader("Access-Control-Request-Method", "POST"); request.addHeader("Access-Control-Request-Headers", "content-type, if-match");
        var input = DeliveryHttpPolicy.input(request, DeliveryHttpPolicy.route(request));
        assertThat(input.preflight()).isTrue();
        var response = new MockHttpServletResponse(); DeliveryHttpPolicy.preflight(response, input);
        assertThat(response.getStatus()).isEqualTo(204); assertThat(response.getHeader("Access-Control-Max-Age")).isEqualTo("30");
        request.removeHeader("Access-Control-Request-Headers"); request.addHeader("Access-Control-Request-Headers", "authorization");
        assertThatThrownBy(() -> DeliveryHttpPolicy.input(request, DeliveryHttpPolicy.route(request))).isInstanceOf(ApiException.class);
        request.removeHeader("Access-Control-Request-Headers"); request.setRequestURI(prefix + "assets/app.js");
        assertThatThrownBy(() -> DeliveryHttpPolicy.input(request, DeliveryHttpPolicy.route(request))).isInstanceOf(ApiException.class);
    }

    @Test void rejectsWrongOriginsDuplicateHeadersAndUnsafeRawRouting() {
        for (String path : new String[]{"../api/me", "api/../me", "api/%2e%2e/me", "api/%252fme", "api//me", "api/actuator", "assets/a%2fb"}) {
            var request = request("GET", path);
            assertThatThrownBy(() -> DeliveryHttpPolicy.input(request, DeliveryHttpPolicy.route(request))).isInstanceOf(ApiException.class);
        }
        var request = request("GET", ""); request.addHeader("Origin", "https://foreign.example");
        assertThatThrownBy(() -> DeliveryHttpPolicy.input(request, DeliveryHttpPolicy.route(request))).isInstanceOf(ApiException.class);
        request.removeHeader("Origin"); request.addHeader("Accept", "text/html"); request.addHeader("Accept", "*/*");
        assertThatThrownBy(() -> DeliveryHttpPolicy.input(request, DeliveryHttpPolicy.route(request))).isInstanceOf(ApiException.class);
        request.setRequestURI("/api/delivery/preview/short/");
        assertThatThrownBy(() -> DeliveryHttpPolicy.route(request)).isInstanceOf(ApiException.class);
    }

    @Test void errorsStaySandboxedAndContentLengthIsCheckedBeforeReading() {
        var response = new MockHttpServletResponse(); DeliveryHttpPolicy.closed(response);
        assertThat(response.getHeader("Content-Security-Policy")).contains("sandbox allow-scripts", "default-src 'none'");
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        var request = request("POST", "api/todos"); request.setContent(new byte[DeliveryHttpPolicy.REQUEST_BYTES + 1]);
        assertThatThrownBy(() -> DeliveryHttpPolicy.input(request, DeliveryHttpPolicy.route(request)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(413));
    }
    @Test void supportsBodylessDeleteWithoutAnInventedContentType() {
        var request = request("DELETE", "api/todos/123");
        var input = DeliveryHttpPolicy.input(request, DeliveryHttpPolicy.route(request));
        assertThat(input.request(new byte[0]).headers()).isEmpty();
        assertThat(input.write()).isTrue();
    }
    private MockHttpServletRequest request(String method, String path) { return new MockHttpServletRequest(method, prefix + path); }
}
