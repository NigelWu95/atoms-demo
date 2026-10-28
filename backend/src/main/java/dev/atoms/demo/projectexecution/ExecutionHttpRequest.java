package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Value-only request: no URL authority, credentials, process identifiers or caller-selected response limits. */
public final class ExecutionHttpRequest {
    static final int API_BYTES = 1 << 20, STATIC_BYTES = 4 << 20;
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> HEADERS = Set.of("accept", "content-type", "if-match", "if-none-match", "idempotency-key");
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String method, path, query;
    private final Map<String, String> headers;
    private final byte[] body;
    private final boolean api, write;

    public ExecutionHttpRequest(String method, String path, String query, Map<String, String> headers, byte[] body) {
        if (body == null) throw unsupported();
        if (body.length > API_BYTES) throw new ApiException(413, "DELIVERY_REQUEST_TOO_LARGE", "应用请求超过大小限制。");
        if (method == null || !METHODS.contains(method)) throw unsupported();
        this.path = path(path); this.query = query(query); this.method = method;
        this.api = this.path.startsWith("/api/"); this.write = !method.equals("GET") && !method.equals("HEAD");
        if (write && !api || !write && body.length != 0) throw unsupported();
        this.headers = headers(headers); this.body = body.clone();
        String type = this.headers.get("content-type");
        if (type != null && !type.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")) throw unsupported();
        // Action endpoints may use POST without a representation (for example /counter/increment).
        // It remains a write, and a declared JSON representation still requires valid JSON bytes.
        boolean bodylessAction = this.body.length == 0 && (method.equals("DELETE") || method.equals("POST") && type == null);
        if (write && !bodylessAction) {
            if (type == null || this.body.length == 0) throw unsupported();
            try { if (JSON.readTree(utf8(this.body)) == null) throw unsupported(); }
            catch (Exception error) { throw unsupported(); }
        }
    }

    public String method() { return method; }
    public String path() { return path; }
    public String query() { return query; }
    public Map<String, String> headers() { return headers; }
    public byte[] body() { return body.clone(); }
    public boolean write() { return write; }
    boolean api() { return api; }
    int responseLimit() { return api ? API_BYTES : STATIC_BYTES; }
    // Deliberately no toString of paths, query parameters, headers or bodies.

    private static String path(String raw) {
        if (raw == null || raw.length() > 2048 || !raw.startsWith("/")) throw unsupported();
        String decoded = decode(raw);
        if (decoded.contains("//") || decoded.codePoints().anyMatch(c -> Character.isISOControl(c) || "\\%?;#:@".indexOf(c) >= 0))
            throw unsupported();
        if (decoded.equals("/") || decoded.equals("/index.html")) {
            if (!decoded.equals(raw)) throw unsupported();
            return raw;
        }
        if (!(raw.startsWith("/assets/") || raw.startsWith("/api/"))) throw unsupported();
        for (String part : decoded.substring(1).split("/", -1))
            if (part.isBlank() || part.equals(".") || part.equals("..")) throw unsupported();
        // Encoded separators are forbidden even if the final decoded path appears to be under an allowed root.
        if (raw.toLowerCase(Locale.ROOT).contains("%2f") || raw.toLowerCase(Locale.ROOT).contains("%5c")) throw unsupported();
        if (raw.startsWith("/api/") && Set.of("actuator", "management").contains(decoded.split("/", 4)[2].toLowerCase(Locale.ROOT)))
            throw unsupported();
        try { if (!new URI(raw).getRawPath().equals(raw)) throw unsupported(); }
        catch (Exception error) { throw unsupported(); }
        return raw;
    }

    private static String query(String raw) {
        if (raw == null) return null;
        if (raw.length() > 2048 || raw.indexOf('#') >= 0 || raw.indexOf('\\') >= 0) throw unsupported();
        String decoded = decode(raw);
        if (decoded.codePoints().anyMatch(Character::isISOControl) || decoded.indexOf('\\') >= 0) throw unsupported();
        try { if (!new URI("http://127.0.0.1/?" + raw).getRawQuery().equals(raw)) throw unsupported(); }
        catch (Exception error) { throw unsupported(); }
        return raw;
    }

    private static String decode(String raw) {
        var bytes = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c <= 0x20 || c >= 0x7f) throw unsupported();
            if (c == '%') {
                if (i + 2 >= raw.length()) throw unsupported();
                int high = Character.digit(raw.charAt(++i), 16), low = Character.digit(raw.charAt(++i), 16);
                if (high < 0 || low < 0) throw unsupported();
                bytes.write(high * 16 + low);
            } else bytes.write(c);
        }
        return utf8(bytes.toByteArray());
    }

    private static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) { throw unsupported(); }
    }

    private static Map<String, String> headers(Map<String, String> input) {
        if (input == null || input.size() > HEADERS.size()) throw unsupported();
        var values = new HashMap<String, String>(); int total = 0;
        for (var entry : input.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) throw unsupported();
            String name = entry.getKey().toLowerCase(Locale.ROOT), value = entry.getValue();
            if (!HEADERS.contains(name) || value.isBlank() || value.length() > 512
                    || value.codePoints().anyMatch(c -> c < 0x20 || c > 0x7e) || values.putIfAbsent(name, value) != null) throw unsupported();
            total += value.length();
        }
        if (total > 2048) throw unsupported();
        return Map.copyOf(values);
    }
    private static ApiException unsupported() {
        return new ApiException(422, "DELIVERY_REQUEST_UNSUPPORTED", "应用请求的路径、方法、请求头或 JSON 内容不受支持。");
    }
}
