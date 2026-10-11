package dev.atoms.standalone;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersTest {
    private final SecurityHeadersFilter filter = new SecurityHeadersFilter(
            new ApiExceptionHandler(new ObjectMapper()), "https://app.example.test");

    @Test
    void rejectsNullForeignAndMultipleOriginsBeforeTheRequestCanReachSessions() throws Exception {
        for (String origin : new String[] {"null", "https://evil.example", "https://app.example.test.evil", "https://app.example.test https://evil.example"}) {
            var request = new MockHttpServletRequest("POST", "/api/session");
            request.addHeader("Origin", origin);
            var response = new MockHttpServletResponse();
            var entered = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> entered.set(true));
            assertThat(entered).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
            assertThat(response.getHeader("Content-Security-Policy")).contains("default-src 'none'", "frame-ancestors 'none'");
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(response.getHeader("Permissions-Policy")).contains("camera=()");
        }
    }

    @Test
    void acceptsExactOriginAndNonBrowserClientsButNotCrossSiteFetches() throws Exception {
        for (String origin : new String[] {null, "https://app.example.test"}) {
            var request = new MockHttpServletRequest("GET", "/api/csrf");
            request.setSecure(true);
            if (origin != null) request.addHeader("Origin", origin);
            var response = new MockHttpServletResponse();
            var entered = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> entered.set(true));
            assertThat(entered).isTrue();
            assertThat(response.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000");
        }
        var request = new MockHttpServletRequest("GET", "/api/csrf");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("Must refuse before sessions"); });
        assertThat(response.getStatus()).isEqualTo(403);
    }
}
