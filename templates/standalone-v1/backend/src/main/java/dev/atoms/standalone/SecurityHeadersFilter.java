package dev.atoms.standalone;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Security metadata must also cover requests rejected before Spring Security runs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class SecurityHeadersFilter extends OncePerRequestFilter {
    private final ApiExceptionHandler errors;
    private final String origin;
    @Value("${standalone.execution-id:}")
    private String executionId = "";

    SecurityHeadersFilter(ApiExceptionHandler errors, @Value("${standalone.origin}") String origin) {
        this.errors = errors;
        this.origin = origin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Request-Id", ApiError.requestId(request));
        if (!executionId.isEmpty()) response.setHeader("X-Atoms-Execution-Id", executionId);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        if (request.isSecure()) response.setHeader("Strict-Transport-Security", "max-age=31536000");
        String path = request.getRequestURI().substring(request.getContextPath().length());
        var origins = Collections.list(request.getHeaders("Origin"));
        if ((path.equals("/api") || path.startsWith("/api/"))
                && ((!origins.isEmpty() && (origins.size() != 1 || !origin.equals(origins.get(0))))
                    || "cross-site".equals(request.getHeader("Sec-Fetch-Site")))) {
            errors.write(request, response, ApiException.Code.FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }
}
