package dev.atoms.standalone;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class RuntimeDocumentPolicyTest {
    @Test void everyConditionIsRequiredBeforeAllowingEmbeddedHtml() {
        for (String method : new String[]{"GET", "HEAD", "POST"})
            for (String path : new String[]{"/api/app/document", "/api/app", "/api/app/document/"})
                for (int status : new int[]{200, 401, 403, 404, 500, 503})
                    for (String type : new String[]{"text/html;charset=UTF-8", "application/json"})
                        for (String csp : new String[]{RuntimeDocumentPolicy.CSP, "script-src 'unsafe-inline'"}) {
                            var request = new MockHttpServletRequest(method, path);
                            var response = new MockHttpServletResponse();
                            response.setStatus(status); response.setContentType(type);
                            response.setHeader("Content-Security-Policy", csp);
                            response.setHeader("X-Content-Type-Options", "nosniff");
                            RuntimeDocumentPolicy.apply(request, response);
                            boolean allowed = method.equals("GET") && path.equals("/api/app/document") && status == 200
                                    && type.startsWith("text/html") && csp.equals(RuntimeDocumentPolicy.CSP);
                            assertThat(response.getHeader("X-Frame-Options")).isEqualTo(allowed ? "SAMEORIGIN" : "DENY");
                            assertThat(response.getHeader("Content-Security-Policy"))
                                    .isEqualTo(allowed ? RuntimeDocumentPolicy.CSP : RuntimeDocumentPolicy.STRICT_CSP);
                            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
                        }
    }
}
