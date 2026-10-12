package dev.atoms.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Isolated cookie jar: never prints authentication material. */
public final class ApiBrowser {
    private String base;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private String csrf;
    public ApiBrowser(int port) { base = "http://127.0.0.1:" + port; }
    public void usePort(int port) { base = "http://127.0.0.1:" + port; }
    public record Reply(int status, JsonNode body, HttpResponse<String> raw) {}

    public Reply request(String method, String path, String body, String key) throws Exception {
        return request(method, path, body, key, csrf);
    }
    public Reply request(String method, String path, String body, String key, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        if (token != null && !method.equals("GET")) request.header("X-CSRF-TOKEN", token);
        var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), json.readTree(response.body()), response);
    }
    public Reply get(String path) throws Exception { return request("GET", path, null, null); }
    public HttpResponse<byte[]> download(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/zip").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    public HttpResponse<String> document(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
    public HttpResponse<java.io.InputStream> events(String path, String lastEventId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .header("Accept", "text/event-stream").GET();
        if (lastEventId != null) request.header("Last-Event-ID", lastEventId);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    }
    public void refreshCsrf() throws Exception { csrf = get("/api/csrf").body().path("token").asText(); }
    public Reply initialize() throws Exception {
        refreshCsrf();
        var identity = request("POST", "/api/session", "{}", null);
        refreshCsrf();
        return identity;
    }
}
