package dev.atoms.demo.projectexecution;

import com.sun.net.httpserver.HttpServer;
import dev.atoms.demo.common.ApiException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExecutionHttpTransportTest {
    @Test void actualJsonConflictAndStaticModuleBytesKeepSemanticsWithoutUpstreamPolicyHeaders() throws Exception {
        try (var upstream = new Upstream(exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Origin")).isNull();
            assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("after=1");
            boolean api = exchange.getRequestURI().getPath().startsWith("/api/");
            byte[] body = (api ? "{\"code\":\"REVISION_CONFLICT\"}" : "export const app = true;").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", api ? "application/json" : "text/javascript; charset=utf-8");
            exchange.getResponseHeaders().set("ETag", "\"v1\"");
            exchange.getResponseHeaders().set("Set-Cookie", "secret=PRIVATE_CANARY");
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src *");
            exchange.sendResponseHeaders(api ? 409 : 200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        })) {
            for (String path : List.of("/api/todos", "/assets/app.js")) {
                var response = send(upstream.port(), get(path), Duration.ofSeconds(5));
                assertThat(response.status()).isEqualTo(path.startsWith("/api/") ? 409 : 200);
                assertThat(response.etag()).isEqualTo("\"v1\"");
                assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains(path.startsWith("/api/") ? "REVISION_CONFLICT" : "export const");
                assertThat(response.toString()).doesNotContain("PRIVATE_CANARY", "REVISION_CONFLICT", "export const");
            }
        }
    }

    @Test void neverFollowsRedirectsOrUsesTheGlobalProxySelector() throws Exception {
        var arrivals = new AtomicInteger();
        try (var upstream = new Upstream(exchange -> {
            arrivals.incrementAndGet(); exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/private");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        })) {
            ProxySelector original = ProxySelector.getDefault();
            try {
                ProxySelector.setDefault(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { throw new AssertionError("Global proxy must not be consulted"); }
                    @Override public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException error) {}
                });
                rejects(() -> send(upstream.port(), get("/"), Duration.ofSeconds(5)), "DELIVERY_UPSTREAM_INVALID");
                assertThat(arrivals).hasValue(1);
            } finally { ProxySelector.setDefault(original); }
        }
    }

    @Test void rejectsMimeEncodingDownloadsAndNonJsonApiBodies() throws Exception {
        for (Map<String, String> headers : List.of(Map.of("Content-Type", "text/html"),
                Map.of("Content-Type", "application/json", "Content-Encoding", "gzip"),
                Map.of("Content-Type", "application/json", "Content-Disposition", "attachment"),
                Map.of("Content-Type", "application/json; charset=utf-16"), Map.of("Content-Type", "unknown/type"))) {
            try (var upstream = new Upstream(exchange -> {
                headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8)); exchange.close();
            })) { rejects(() -> send(upstream.port(), get("/api/todos"), Duration.ofSeconds(5)), "DELIVERY_UPSTREAM_INVALID"); }
        }
        try (var upstream = new Upstream(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 3); exchange.getResponseBody().write("bad".getBytes(StandardCharsets.UTF_8)); exchange.close();
        })) { rejects(() -> send(upstream.port(), get("/api/todos"), Duration.ofSeconds(5)), "DELIVERY_UPSTREAM_INVALID"); }
    }

    @Test void rejectsOversizedDeclaredAndChunkedResponsesDuringReading() throws Exception {
        for (boolean chunked : List.of(false, true)) {
            try (var upstream = new Upstream(exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, chunked ? 0 : (1 << 20) + 1);
                try { exchange.getResponseBody().write(new byte[(1 << 20) + 1]); }
                catch (java.io.IOException cancelledRead) { /* Expected when bounded reader cancels. */ }
                finally { exchange.close(); }
            })) { rejects(() -> send(upstream.port(), get("/api/todos"), Duration.ofSeconds(5)), "DELIVERY_RESPONSE_TOO_LARGE"); }
        }
    }

    @Test void deadlinesIncludeReadingAndWriteFailuresAreNeverRetried() throws Exception {
        for (boolean write : List.of(false, true)) {
            var received = new CountDownLatch(1); var release = new CountDownLatch(1); var arrivals = new AtomicInteger();
            try (var upstream = new Upstream(exchange -> {
                arrivals.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 2); received.countDown();
                try { release.await(5, TimeUnit.SECONDS); exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (java.io.IOException cancelledRead) { /* Deadline cancels the client read. */ }
                finally { exchange.close(); }
            })) {
                long started = System.nanoTime();
                try {
                    rejects(() -> send(upstream.port(), write ? post() : get("/api/todos"), Duration.ofMillis(300)),
                            write ? "DELIVERY_WRITE_UNCERTAIN" : "DELIVERY_TIMEOUT");
                    assertThat(received.await(1, TimeUnit.SECONDS)).isTrue();
                    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
                    assertThat(arrivals).hasValue(1);
                } finally { release.countDown(); }
            }
        }
    }

    @Test void headAndNoContentResponsesDoNotInventBodies() throws Exception {
        try (var upstream = new Upstream(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("ETag", "\"v2\"");
            exchange.sendResponseHeaders(exchange.getRequestMethod().equals("HEAD") ? 200 : 204, -1); exchange.close();
        })) {
            for (String method : List.of("HEAD", "GET")) {
                var request = new ExecutionHttpRequest(method, "/api/todos", null, Map.of(), new byte[0]);
                assertThat(send(upstream.port(), request, Duration.ofSeconds(5)).body()).isEmpty();
            }
        }
    }

    @Test void staticLimitIsIndependentAndAnExpiredBudgetCannotDispatchAWritingRequest() throws Exception {
        byte[] script = new byte[(1 << 20) + 17]; Arrays.fill(script, (byte) ' ');
        var arrivals = new AtomicInteger();
        try (var upstream = new Upstream(exchange -> {
            arrivals.incrementAndGet(); exchange.getResponseHeaders().set("Content-Type", "text/javascript");
            exchange.sendResponseHeaders(200, script.length); exchange.getResponseBody().write(script); exchange.close();
        })) {
            var response = send(upstream.port(), get("/assets/large.js"), Duration.ofSeconds(5));
            assertThat(response.bytes()).isEqualTo(script.length);
            assertThat(response.body()).isEqualTo(script);
            byte[] copy = response.body(); copy[0] = 0; assertThat(response.body()[0]).isEqualTo((byte) ' ');
            rejects(() -> new ExecutionHttpTransport().exchange(upstream.port(), post(), () -> Duration.ZERO), "DELIVERY_TIMEOUT");
            assertThat(arrivals).hasValue(1);
        }
    }

    private ExecutionHttpResponse send(int port, ExecutionHttpRequest request, Duration timeout) {
        return new ExecutionHttpTransport().exchange(port, request, new ExecutionProcess.Deadline(timeout));
    }
    private ExecutionHttpRequest get(String path) { return new ExecutionHttpRequest("GET", path, "after=1", Map.of(), new byte[0]); }
    private ExecutionHttpRequest post() { return new ExecutionHttpRequest("POST", "/api/todos", null, Map.of("Content-Type", "application/json"), "{}".getBytes(StandardCharsets.UTF_8)); }
    private void rejects(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code))
                .hasNoCause().hasNoSuppressedExceptions().hasMessageNotContaining("PRIVATE_CANARY");
    }
    private static final class Upstream implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        Upstream(com.sun.net.httpserver.HttpHandler handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor); server.createContext("/", handler); server.start();
        }
        int port() { return server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
