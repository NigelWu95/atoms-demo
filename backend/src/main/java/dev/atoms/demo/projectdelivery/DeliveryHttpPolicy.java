package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.projectexecution.ExecutionHttpRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Browser policy for this capability prefix only; never derives authority from Host or forwarded headers. */
final class DeliveryHttpPolicy {
    static final int REQUEST_BYTES = 1 << 20;
    static final Set<String> HEADERS = Set.of("accept", "content-type", "if-match", "if-none-match", "idempotency-key");
    private DeliveryHttpPolicy() {}

    record Route(String token, String path) {
        @Override public String toString() { return "DeliveryRoute"; }
    }
    record Input(String method, String path, String query, Map<String, String> headers, boolean preflight) {
        ExecutionHttpRequest request(byte[] body) { return new ExecutionHttpRequest(method, path, query, headers, body); }
        boolean write() { return !preflight && !method.equals("GET") && !method.equals("HEAD"); }
        @Override public String toString() { return "DeliveryInput[method=" + method + "]"; }
    }

    static Route route(HttpServletRequest request) {
        String raw = request.getRequestURI();
        int start = DeliveryGrants.PREFIX.length(), end = start + 43;
        if (!raw.startsWith(DeliveryGrants.PREFIX) || raw.length() <= end || raw.charAt(end) != '/') throw DeliveryGrants.missing();
        String token = raw.substring(start, end);
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw DeliveryGrants.missing();
        return new Route(token, raw.substring(end));
    }

    static Input input(HttpServletRequest request, Route route) {
        String origin = single(request, "Origin");
        if (origin != null && !origin.equals("null")) throw unsupported();
        boolean preflight = request.getMethod().equals("OPTIONS");
        if (preflight && origin == null) throw unsupported();
        if (request.getContentLengthLong() > REQUEST_BYTES) throw tooLarge();
        if (single(request, "Content-Encoding") != null) throw unsupported();
        String method = preflight ? single(request, "Access-Control-Request-Method") : request.getMethod();
        var headers = new HashMap<String, String>();
        for (String name : HEADERS) {
            String value = single(request, name);
            if (value != null) headers.put(name, value);
        }
        if (preflight) {
            if (request.getContentLengthLong() > 0 || single(request, "Transfer-Encoding") != null) throw unsupported();
            String names = single(request, "Access-Control-Request-Headers");
            if (names != null) {
                if (names.length() > 256) throw unsupported();
                var seen = new HashSet<String>();
                for (String name : names.split(",", -1)) {
                    name = name.strip().toLowerCase(Locale.ROOT);
                    if (!HEADERS.contains(name) || !seen.add(name)) throw unsupported();
                }
            }
            headers.clear();
        }
        // Validate routing/method/header syntax before waiting for a potentially slow body.
        var probe = new HashMap<>(headers);
        boolean write = method != null && !Set.of("GET", "HEAD").contains(method);
        if (preflight && write) probe.put("content-type", "application/json");
        new ExecutionHttpRequest(method, route.path(), request.getQueryString(), probe,
                write && !("DELETE".equals(method) && !probe.containsKey("content-type"))
                        ? "{}".getBytes(StandardCharsets.UTF_8) : new byte[0]);
        return new Input(method, route.path(), request.getQueryString(), Map.copyOf(headers), preflight);
    }

    static void closed(HttpServletResponse response) {
        response.setHeader("Content-Security-Policy", "sandbox allow-scripts; default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
    }

    static void authorized(HttpServletRequest request, HttpServletResponse response, Route route, String origin) {
        String base = origin + DeliveryGrants.PREFIX + route.token() + "/", assets = base + "assets/";
        response.setHeader("X-Frame-Options", null); // CSP frame-ancestors permits only the configured parent.
        response.setHeader("Content-Security-Policy", "sandbox allow-scripts; default-src 'none'; script-src " + assets
                + "; style-src 'unsafe-inline' " + assets + "; font-src " + assets + "; img-src data: " + assets
                + "; connect-src " + base + "api/; base-uri 'none'; object-src 'none'; worker-src 'none'; frame-src 'none'"
                + "; form-action 'none'; frame-ancestors " + origin);
        response.setHeader("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
        if ("null".equals(single(request, "Origin"))) {
            response.setHeader("Access-Control-Allow-Origin", "null");
            response.setHeader("Access-Control-Expose-Headers", "ETag");
        }
    }

    static void preflight(HttpServletResponse response, Input input) {
        response.setStatus(204);
        response.setHeader("Access-Control-Allow-Methods", input.method());
        response.setHeader("Access-Control-Allow-Headers", "Accept, Content-Type, If-Match, If-None-Match, Idempotency-Key");
        response.setHeader("Access-Control-Max-Age", "30");
    }
    private static String single(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() > 1) throw unsupported();
        return values.isEmpty() ? null : values.get(0);
    }
    static ApiException unsupported() { return new ApiException(422, "DELIVERY_REQUEST_UNSUPPORTED", "应用请求的路径、方法、请求头或 JSON 内容不受支持。"); }
    static ApiException tooLarge() { return new ApiException(413, "DELIVERY_REQUEST_TOO_LARGE", "应用请求超过大小限制。"); }
}
