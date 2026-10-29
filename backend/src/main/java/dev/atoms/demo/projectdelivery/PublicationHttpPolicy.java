package dev.atoms.demo.projectdelivery;

import com.sun.net.httpserver.HttpExchange;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.projectexecution.ExecutionHttpRequest;
import java.io.IOException;
import java.net.URI;
import java.util.*;

final class PublicationHttpPolicy {
    private PublicationHttpPolicy() {}
    static ExecutionHttpRequest read(HttpExchange exchange, String origin, PublicationCookies cookies) throws IOException {
        var headers = exchange.getRequestHeaders();
        if (!URI.create(origin).getRawAuthority().equals(single(exchange, "Host"))) throw unsupported();
        if (exchange.getRequestURI().isAbsolute() || exchange.getRequestURI().getRawFragment() != null) throw unsupported();
        String from = single(exchange, "Origin"), site = single(exchange, "Sec-Fetch-Site");
        boolean write = !Set.of("GET", "HEAD").contains(exchange.getRequestMethod());
        if (from != null && !from.equals(origin) || write && Set.of("cross-site", "same-site").contains(Objects.toString(site, "")))
            throw new ApiException(403, "PUBLICATION_ORIGIN_REJECTED", "应用请求来源不受支持。");
        if (single(exchange, "Content-Encoding") != null || single(exchange, "Upgrade") != null) throw unsupported();
        String length = single(exchange, "Content-Length");
        if (length != null) {
            try { if (!length.matches("[0-9]{1,10}") || Long.parseLong(length) > DeliveryHttpPolicy.REQUEST_BYTES) throw DeliveryHttpPolicy.tooLarge(); }
            catch (NumberFormatException failure) { throw DeliveryHttpPolicy.tooLarge(); }
        }
        String isolated = cookies.incoming(headers.getOrDefault("Cookie", List.of()));
        if (write && isolated != null && from == null) throw new ApiException(403, "PUBLICATION_ORIGIN_REJECTED", "使用应用 Cookie 的写请求必须来自应用页面。");
        var forwarded = new HashMap<String, String>();
        for (String name : DeliveryHttpPolicy.HEADERS) {
            String value = single(exchange, name); if (value != null) forwarded.put(name, value);
        }
        byte[] body = exchange.getRequestBody().readNBytes(DeliveryHttpPolicy.REQUEST_BYTES + 1);
        if (body.length > DeliveryHttpPolicy.REQUEST_BYTES) throw DeliveryHttpPolicy.tooLarge();
        return new ExecutionHttpRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(), forwarded, body, isolated);
    }
    static void response(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-store"); headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff"); headers.set("X-Frame-Options", "DENY");
        headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; font-src 'self'; connect-src 'self'; base-uri 'none'; object-src 'none'; "
                + "worker-src 'none'; frame-src 'none'; frame-ancestors 'none'; form-action 'self'");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
    }
    private static String single(HttpExchange exchange, String name) {
        var values = exchange.getRequestHeaders().getOrDefault(name, List.of());
        if (values.size() > 1) throw unsupported();
        return values.isEmpty() ? null : values.get(0);
    }
    private static ApiException unsupported() { return DeliveryHttpPolicy.unsupported(); }
}
