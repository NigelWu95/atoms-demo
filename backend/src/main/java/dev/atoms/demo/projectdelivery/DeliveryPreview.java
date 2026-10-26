package dev.atoms.demo.projectdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.*;
import dev.atoms.demo.source.SourceExecutionBudget;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.Set;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Nonblocking servlet ingress/egress with a bounded, non-queuing upstream worker pool. */
@Component
final class DeliveryPreview {
    private final DeliveryAccess access;
    private final ObjectMapper json;
    private final Set<Exchange> active = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, DeliverySettings.EXCHANGES, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { var thread = new Thread(task, "delivery-preview"); thread.setDaemon(true); return thread; });

    DeliveryPreview(DeliveryAccess access, ObjectMapper json) { this.access = access; this.json = json; }

    void open(HttpServletRequest request, HttpServletResponse response, DeliveryGrants.Grant grant,
            DeliveryHttpPolicy.Input input, DeliveryGrants.Exchange lease, SourceExecutionBudget budget) throws IOException {
        var exchange = new Exchange(request, response, grant, input, lease, budget);
        active.add(exchange);
        try { exchange.start(); }
        catch (Exception failure) { exchange.fail(failure); }
    }

    @PreDestroy void close() {
        active.forEach(exchange -> exchange.fail(new ApiException(503, "DELIVERY_CANCELLED", "预览服务已关闭，请重新打开。")));
        workers.shutdownNow();
    }

    private final class Exchange implements ReadListener, WriteListener, AsyncListener {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final DeliveryGrants.Grant grant;
        private final DeliveryHttpPolicy.Input input;
        private final DeliveryGrants.Exchange lease;
        private final SourceExecutionBudget budget;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final byte[] buffer = new byte[8192];
        private AsyncContext async;
        private ServletInputStream incoming;
        private ServletOutputStream outgoing;
        private byte[] result;
        private int position;
        private boolean scheduled, ended, dispatched;
        private Thread worker;

        Exchange(HttpServletRequest request, HttpServletResponse response, DeliveryGrants.Grant grant,
                DeliveryHttpPolicy.Input input, DeliveryGrants.Exchange lease, SourceExecutionBudget budget) {
            this.request = request; this.response = response; this.grant = grant;
            this.input = input; this.lease = lease; this.budget = budget;
        }

        synchronized void start() throws IOException {
            long timeout = millis();
            async = request.startAsync(request, response); async.setTimeout(timeout); async.addListener(this);
            if (input.preflight()) { submit(); return; }
            incoming = request.getInputStream(); incoming.setReadListener(this);
        }

        @Override public synchronized void onDataAvailable() {
            try {
                while (!ended && !incoming.isFinished() && incoming.isReady()) {
                    millis(); int count = incoming.read(buffer);
                    if (count < 0) break;
                    if (count == 0) return;
                    if (count > DeliveryHttpPolicy.REQUEST_BYTES - body.size()) throw DeliveryHttpPolicy.tooLarge();
                    body.write(buffer, 0, count);
                }
            } catch (Exception failure) { fail(failure); }
        }
        @Override public synchronized void onAllDataRead() { submit(); }

        private synchronized void submit() {
            if (ended || scheduled) return;
            scheduled = true;
            try { workers.execute(this::run); }
            catch (RejectedExecutionException capacity) { fail(DeliveryGrants.capacity()); }
        }

        private void run() {
            synchronized (this) {
                if (ended) return;
                worker = Thread.currentThread();
            }
            try {
                millis();
                if (input.preflight()) {
                    access.validate(grant, budget);
                    synchronized (this) {
                        if (!ended) { DeliveryHttpPolicy.preflight(response, input); finish(); }
                    }
                    return;
                }
                var upstream = input.request(body.toByteArray());
                synchronized (this) { if (ended) return; dispatched = true; }
                var reply = access.exchange(grant, upstream, budget);
                synchronized (this) {
                    if (ended) return;
                    millis(); response.setStatus(reply.status());
                    if (reply.contentType() != null) response.setContentType(reply.contentType());
                    if (reply.etag() != null) response.setHeader("ETag", reply.etag());
                    result = reply.body();
                    if (input.method().equals("HEAD") || reply.status() == 204 || reply.status() == 205 || reply.status() == 304) {
                        finish(); return;
                    }
                    response.setContentLength(result.length);
                    outgoing = response.getOutputStream(); outgoing.setWriteListener(this);
                }
            } catch (Exception failure) { fail(failure); }
            finally {
                synchronized (this) { worker = null; if (ended) release(); }
            }
        }

        @Override public synchronized void onWritePossible() {
            try {
                while (!ended && position < result.length && outgoing.isReady()) {
                    millis(); int count = Math.min(8192, result.length - position);
                    outgoing.write(result, position, count); position += count;
                }
                if (!ended && position == result.length) finish();
            } catch (Exception failure) { fail(failure); }
        }

        synchronized void fail(Throwable failure) {
            if (ended) return;
            if (worker != null && worker != Thread.currentThread()) worker.interrupt();
            // Once application dispatch may have begun, a timeout cannot claim that a write was rolled back.
            ApiException error = failure instanceof ApiException api ? api
                    : new ApiException(503, "DELIVERY_CANCELLED", "预览连接已中断，请查询应用状态后再操作。");
            if (dispatched && input.write() && (!(failure instanceof ApiException)
                    || error.code().equals("DELIVERY_TIMEOUT") || error.code().equals("DELIVERY_CANCELLED"))) error = uncertain();
            try {
                if (!response.isCommitted() && outgoing == null) {
                    response.resetBuffer(); response.setStatus(error.status()); response.setContentType("application/json;charset=UTF-8");
                    byte[] bytes = json.writeValueAsBytes(ApiError.of(request, error.code(), error.getMessage(), false));
                    response.setContentLength(bytes.length); response.getOutputStream().write(bytes);
                }
            } catch (Exception ignored) { /* Disconnected clients receive no retry or error dispatch. */ }
            finally { finish(); }
        }

        private synchronized void finish() {
            if (ended) return;
            ended = true;
            try { if (async != null) async.complete(); }
            catch (IllegalStateException alreadyCompleted) { /* Container already closed the connection. */ }
            finally { if (worker == null) release(); }
        }
        private void release() { lease.close(); active.remove(this); }
        private long millis() throws IOException {
            try {
                var remaining = budget.remaining();
                if (Thread.currentThread().isInterrupted() || remaining == null || remaining.isNegative() || remaining.isZero()) throw timeout();
                return Math.max(1, remaining.toMillis());
            } catch (IOException expired) { throw timeout(); }
        }
        @Override public void onError(Throwable failure) { fail(failure); }
        @Override public void onError(AsyncEvent event) { fail(event.getThrowable()); }
        @Override public synchronized void onTimeout(AsyncEvent event) { fail(dispatched && input.write() ? uncertain() : timeout()); }
        @Override public synchronized void onComplete(AsyncEvent event) {
            if (!ended) fail(new IOException("DELIVERY_CANCELLED"));
        }
        @Override public void onStartAsync(AsyncEvent event) { fail(new IOException("DELIVERY_REDISPATCH_REJECTED")); }
    }
    private static ApiException timeout() { return new ApiException(504, "DELIVERY_TIMEOUT", "预览请求超时，请重新打开或查询状态。"); }
    private static ApiException uncertain() { return new ApiException(502, "DELIVERY_WRITE_UNCERTAIN", "无法确认应用写入结果，请先查询确认，勿自动重试。"); }
}
