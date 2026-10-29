package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Package-only transport. The execution owner, never an HTTP caller, supplies the already bound loopback port. */
final class ExecutionHttpTransport {
    private static final Set<String> MIME = Set.of("text/html", "text/css", "text/javascript", "application/javascript",
            "application/json", "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml", "image/x-icon", "font/woff", "font/woff2");
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {}
            }).build(); // No authenticator or cookie handler; do not consult global credentials.

    ExecutionHttpResponse exchange(int port, ExecutionHttpRequest input, OwnedProcess.Budget budget) {
        boolean dispatched = false;
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            if (port < 1024 || port > 65535 || input == null) throw invalid();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + input.path()
                    + (input.query() == null ? "" : "?" + input.query()))).timeout(remaining(budget));
            input.headers().forEach(request::header);
            if (input.applicationCookies() != null) request.header("Cookie", input.applicationCookies());
            byte[] body = input.body();
            request.method(input.method(), body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            remaining(budget);
            dispatched = true;
            future = CLIENT.sendAsync(request.build(), info -> {
                metadata(info.statusCode(), info.headers(), input);
                return new Bounded(input.responseLimit());
            });
            var response = future.get(remaining(budget).toNanos(), TimeUnit.NANOSECONDS);
            var metadata = metadata(response.statusCode(), response.headers(), input);
            boolean empty = input.method().equals("HEAD") || Set.of(204, 205, 304).contains(response.statusCode());
            if (empty && response.body().length != 0) throw invalid();
            if (!empty && input.api()) {
                String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
                if (JSON.readTree(json) == null) throw invalid();
            }
            remaining(budget);
            return new ExecutionHttpResponse(response.statusCode(), metadata.type(), metadata.etag(), response.body(), metadata.cookies());
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (input != null && input.write() && dispatched) throw uncertain();
            Throwable cause = failure;
            for (int i = 0; i < 5 && (cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null; i++)
                cause = cause.getCause();
            if (cause instanceof ApiException api && Set.of("DELIVERY_UPSTREAM_INVALID", "DELIVERY_RESPONSE_TOO_LARGE",
                    "DELIVERY_TIMEOUT", "DELIVERY_CANCELLED").contains(api.code())) throw error(api.status(), api.code());
            if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) throw error(504, "DELIVERY_TIMEOUT");
            if (cause instanceof InterruptedException) throw error(503, "DELIVERY_CANCELLED");
            if (cause instanceof CharacterCodingException || cause instanceof com.fasterxml.jackson.core.JsonProcessingException) throw invalid();
            throw error(502, "DELIVERY_UPSTREAM_UNAVAILABLE");
        } finally { if (future != null && !future.isDone()) future.cancel(true); }
    }

    private static Metadata metadata(int status, HttpHeaders headers, ExecutionHttpRequest request) {
        if (!(status >= 200 && status < 300 && status != 206 || status == 304 || request.api() && status >= 400 && status < 500)) throw invalid();
        String encoding = single(headers, "Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity") || !headers.allValues("Content-Disposition").isEmpty()) throw invalid();
        boolean empty = request.method().equals("HEAD") || Set.of(204, 205, 304).contains(status);
        String length = single(headers, "Content-Length");
        if (!empty && length != null) {
            try {
                if (!length.matches("[0-9]{1,19}")) throw invalid();
                if (Long.parseLong(length) > request.responseLimit()) throw tooLarge();
            } catch (NumberFormatException error) { throw invalid(); }
        }
        String type = single(headers, "Content-Type");
        if (type == null && !empty) throw invalid();
        if (type != null) {
            if (type.length() > 128) throw invalid();
            String[] parts = type.toLowerCase(Locale.ROOT).split(";", -1);
            String mime = parts[0].strip();
            if (!MIME.contains(mime) || request.api() && !mime.equals("application/json") || parts.length > 2
                    || parts.length == 2 && !parts[1].strip().equals("charset=utf-8")) throw invalid();
            type = mime + (parts.length == 2 ? "; charset=utf-8" : "");
        }
        String etag = single(headers, "ETag");
        if (etag != null && (etag.length() > 512 || !etag.matches("(?:W/)?\"[\\x21\\x23-\\x7e]*\""))) throw invalid();
        var cookies = headers.allValues("Set-Cookie");
        if (cookies.size() > 32 || cookies.stream().mapToInt(String::length).sum() > 8192) throw invalid();
        return new Metadata(type, etag, cookies);
    }
    private record Metadata(String type, String etag, List<String> cookies) {}
    private static String single(HttpHeaders headers, String name) {
        var values = headers.allValues(name);
        if (values.size() > 1) throw invalid();
        return values.isEmpty() ? null : values.get(0);
    }
    private static Duration remaining(OwnedProcess.Budget budget) {
        if (Thread.currentThread().isInterrupted()) throw error(503, "DELIVERY_CANCELLED");
        try {
            Duration duration = budget.remaining();
            if (duration == null || duration.isNegative() || duration.isZero()) throw new IOException();
            return duration;
        } catch (IOException | RuntimeException error) { throw error(504, "DELIVERY_TIMEOUT"); }
    }
    static ApiException uncertain() { return error(502, "DELIVERY_WRITE_UNCERTAIN"); }
    private static ApiException invalid() { return error(502, "DELIVERY_UPSTREAM_INVALID"); }
    private static ApiException tooLarge() { return error(413, "DELIVERY_RESPONSE_TOO_LARGE"); }
    private static ApiException error(int status, String code) {
        return new ApiException(status, code, code.equals("DELIVERY_WRITE_UNCERTAIN")
                ? "无法确认应用写入结果，请先查询确认，勿自动重试。" : "应用响应暂不可用或不符合预览契约，请检查运行状态。");
    }

    private static final class Bounded implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private Flow.Subscription subscription;
        private int bytes;
        private boolean finished;
        Bounded(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if (finished) return;
            for (var chunk : chunks) {
                if (chunk.remaining() > limit - bytes) { finished = true; subscription.cancel(); delegate.onError(tooLarge()); return; }
                bytes += chunk.remaining();
            }
            delegate.onNext(chunks);
        }
        @Override public void onError(Throwable error) { if (!finished) { finished = true; delegate.onError(error); } }
        @Override public void onComplete() { if (!finished) { finished = true; delegate.onComplete(); } }
    }
}
