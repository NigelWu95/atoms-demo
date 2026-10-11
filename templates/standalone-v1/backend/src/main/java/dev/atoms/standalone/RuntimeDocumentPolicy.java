package dev.atoms.standalone;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Apply after session commit, before sending the buffered response. Errors never inherit runtime privileges. */
final class RuntimeDocumentPolicy {
    static final String STRICT_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'";
    // Same opaque-origin policy as the platform. Header sandbox also protects direct navigation.
    // https://www.w3.org/TR/CSP3/#directive-sandbox
    static final String CSP = "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline'; "
            + "script-src-attr 'none'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; "
            + "frame-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'";

    static void apply(HttpServletRequest request, HttpServletResponse response) {
        boolean document = "GET".equals(request.getMethod())
                && (request.getContextPath() + "/api/app/document").equals(request.getRequestURI())
                && response.getStatus() == 200 && CSP.equals(response.getHeader("Content-Security-Policy"))
                && response.getContentType() != null && response.getContentType().startsWith("text/html");
        response.setHeader("X-Frame-Options", document ? "SAMEORIGIN" : "DENY");
        response.setHeader("Content-Security-Policy", document ? CSP : STRICT_CSP);
    }
}
