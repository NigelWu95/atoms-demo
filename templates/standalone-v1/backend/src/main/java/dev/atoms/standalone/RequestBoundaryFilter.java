package dev.atoms.standalone;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Runs before JDBC sessions and Security: rejected input cannot create a session. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
final class RequestBoundaryFilter extends OncePerRequestFilter {
    private static final int MAX_BODY = 320 * 1024;
    private static final Set<String> API_PATHS = Set.of("/api/health", "/api/csrf", "/api/session",
            "/api/app", "/api/app/document", "/api/state");
    private final ApiExceptionHandler errors;
    private final ObjectReader json;

    RequestBoundaryFilter(ApiExceptionHandler errors, ObjectMapper mapper) {
        this.errors = errors;
        var limited = mapper.copy();
        // Per-reader constraints, never a global Jackson default affecting other consumers.
        // https://github.com/FasterXML/jackson-core/blob/2.21/src/main/java/com/fasterxml/jackson/core/StreamReadConstraints.java
        limited.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build());
        json = limited.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!(path.equals("/api") || path.startsWith("/api/"))) { chain.doFilter(request, response); return; }
        // Do not expose new API routes just because a controller was added. Update the contract explicitly.
        if (!API_PATHS.contains(path)) { errors.write(request, response, ApiException.Code.NOT_FOUND); return; }
        // Delay the response until Spring Session's final save has succeeded. A session-commit
        // failure must not leave a false 200 on the wire. Only finite synchronous API responses.
        // https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/web/util/ContentCachingResponseWrapper.html
        var buffered = new ContentCachingResponseWrapper(response);
        try {
            boolean write = !Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod());
            chain.doFilter(write ? body(request) : request, buffered);
        } catch (Exception failure) {
            buffered.resetBuffer();
            errors.write(request, buffered, ApiExceptionHandler.classify(failure));
        }
        RuntimeDocumentPolicy.apply(request, buffered);
        buffered.copyBodyToResponse();
    }

    private HttpServletRequest body(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY) throw new ApiException(ApiException.Code.PAYLOAD_TOO_LARGE);
        try {
            var type = MediaType.parseMediaType(request.getContentType() == null ? "" : request.getContentType());
            if (!"application".equals(type.getType()) || !"json".equals(type.getSubtype())
                    || (type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset()))
                    || request.getHeader("Content-Encoding") != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ignored) { throw new ApiException(ApiException.Code.INVALID_INPUT); }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw new ApiException(ApiException.Code.PAYLOAD_TOO_LARGE);
        try {
            JsonNode value = json.readTree(bytes);
            if (value == null || value.isMissingNode()) throw new ApiException(ApiException.Code.INVALID_INPUT);
        } catch (IOException ignored) { throw new ApiException(ApiException.Code.INVALID_INPUT); }
        return new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] target, int offset, int length) { return input.read(target, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous JSON API only."); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        };
    }
}
