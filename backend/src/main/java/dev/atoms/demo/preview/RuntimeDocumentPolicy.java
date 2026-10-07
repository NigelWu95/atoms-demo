package dev.atoms.demo.preview;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.springframework.security.web.header.HeaderWriter;

/** Successful runtime HTML is the only frameable API response. All other responses remain DENY. */
public final class RuntimeDocumentPolicy implements HeaderWriter {
    // No nonce: generated scripts could copy one onto an external script element.
    // Inline code is intentionally executable only in this opaque-origin runtime, never in the management SPA.
    public static final String CSP = "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline'; "
            + "script-src-attr 'none'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; "
            + "frame-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'";
    private static final String UUID_PATH = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern DOCUMENT = Pattern.compile("/api/projects/" + UUID_PATH + "/versions/" + UUID_PATH + "/document");
    @Override public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
        boolean document = "GET".equals(request.getMethod()) && DOCUMENT.matcher(request.getServletPath()).matches()
                && response.getStatus() == 200 && CSP.equals(response.getHeader("Content-Security-Policy"))
                && response.getContentType() != null && response.getContentType().startsWith("text/html");
        response.setHeader("X-Frame-Options", document ? "SAMEORIGIN" : "DENY");
    }
}
