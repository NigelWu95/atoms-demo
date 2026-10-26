package dev.atoms.demo.projectdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.*;
import dev.atoms.demo.source.SourceExecutionBudget;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Two bounded downloads, one per original session, including generation and slow/disconnected writers. */
@Component
final class DeliveryDownloads {
    private final DeliverySources sources;
    private final ObjectMapper json;
    private final Map<String, Slot> slots = new HashMap<>();
    private final Set<Transfer> active = ConcurrentHashMap.newKeySet();
    private boolean closed;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, DeliverySettings.DOWNLOADS, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { var thread = new Thread(task, "delivery-download"); thread.setDaemon(true); return thread; });

    DeliveryDownloads(DeliverySources sources, ObjectMapper json) { this.sources = sources; this.json = json; }

    DeliverySources.BuildView inspect(UUID owner, UUID project, UUID build, String session) {
        var budget = DeliveryAccess.deadline(); sources.authorize(owner, project, session, budget);
        try (var slot = acquire(session)) { return sources.inspect(owner, project, build, session, budget); }
    }

    void open(UUID owner, UUID project, UUID selected, String session, boolean preview,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        var budget = DeliveryAccess.deadline(); sources.authorize(owner, project, session, budget);
        var transfer = new Transfer(request, response, acquire(session), budget,
                () -> preview ? sources.preview(owner, project, selected, session, budget) : sources.build(owner, project, selected, session, budget));
        active.add(transfer);
        try { transfer.start(); }
        catch (Exception failure) { transfer.fail(failure); }
    }

    private synchronized Slot acquire(String session) {
        if (closed) throw cancelled();
        if (slots.size() >= DeliverySettings.DOWNLOADS || slots.containsKey(session)) throw capacity();
        var slot = new Slot(session); slots.put(session, slot); return slot;
    }
    private final class Slot implements AutoCloseable {
        private final String session;
        Slot(String session) { this.session = session; }
        @Override public void close() { synchronized (DeliveryDownloads.this) { slots.remove(session, this); } }
    }
    @PreDestroy void close() {
        synchronized (this) { closed = true; }
        active.forEach(transfer -> transfer.fail(cancelled())); workers.shutdownNow();
    }

    private final class Transfer implements WriteListener, AsyncListener {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final Slot slot;
        private final SourceExecutionBudget budget;
        private final Supplier<DeliverySources.Archive> generate;
        private AsyncContext async;
        private ServletOutputStream output;
        private byte[] bytes;
        private int position;
        private Thread worker;
        private boolean ended;

        Transfer(HttpServletRequest request, HttpServletResponse response, Slot slot, SourceExecutionBudget budget,
                Supplier<DeliverySources.Archive> generate) {
            this.request = request; this.response = response; this.slot = slot; this.budget = budget; this.generate = generate;
        }
        synchronized void start() throws IOException {
            long timeout = millis();
            async = request.startAsync(request, response); async.setTimeout(timeout); async.addListener(this);
            try { workers.execute(this::run); }
            catch (RejectedExecutionException unavailable) { throw capacity(); }
        }
        private void run() {
            synchronized (this) { if (ended) return; worker = Thread.currentThread(); }
            try {
                millis(); var archive = generate.get();
                synchronized (this) {
                    if (ended) return;
                    millis(); bytes = archive.bytes();
                    response.setStatus(200); response.setContentType("application/zip");
                    response.setHeader("Content-Disposition", "attachment; filename=\"" + archive.filename() + "\"");
                    response.setContentLength(bytes.length);
                    output = response.getOutputStream(); output.setWriteListener(this);
                }
            } catch (Exception failure) { fail(failure); }
            finally { synchronized (this) { worker = null; if (ended) release(); } }
        }
        @Override public synchronized void onWritePossible() {
            try {
                while (!ended && position < bytes.length && output.isReady()) {
                    millis(); int count = Math.min(8192, bytes.length - position);
                    output.write(bytes, position, count); position += count;
                }
                if (!ended && position == bytes.length) finish();
            } catch (Exception failure) { fail(failure); }
        }
        synchronized void fail(Throwable failure) {
            if (ended) return;
            if (worker != null && worker != Thread.currentThread()) worker.interrupt();
            var error = failure instanceof ApiException api ? api : cancelled();
            try {
                if (!response.isCommitted()) {
                    response.resetBuffer(); response.setStatus(error.status()); response.setContentType("application/json;charset=UTF-8");
                    response.setHeader("Content-Disposition", "inline");
                    if (output == null) {
                        byte[] detail = json.writeValueAsBytes(ApiError.of(request, error.code(), error.getMessage(), false));
                        response.setContentLength(detail.length); response.getOutputStream().write(detail);
                    } else response.setContentLength(0); // Nonblocking output was registered but no ZIP bytes committed.
                }
            } catch (Exception ignored) { /* A disconnected client receives no successful archive or retry. */ }
            finally { finish(); }
        }
        private synchronized void finish() {
            if (ended) return;
            ended = true;
            try { if (async != null) async.complete(); }
            catch (IllegalStateException completed) { /* Container already closed the response. */ }
            finally { if (worker == null) release(); }
        }
        private void release() { slot.close(); active.remove(this); }
        private long millis() throws IOException {
            try {
                var remaining = budget.remaining();
                if (Thread.currentThread().isInterrupted() || remaining == null || remaining.isNegative() || remaining.isZero()) throw timeout();
                return Math.max(1, remaining.toMillis());
            } catch (IOException expired) { throw timeout(); }
        }
        @Override public void onError(Throwable failure) { fail(failure); }
        @Override public void onError(AsyncEvent event) { fail(event.getThrowable()); }
        @Override public void onTimeout(AsyncEvent event) { fail(timeout()); }
        @Override public synchronized void onComplete(AsyncEvent event) { if (!ended) fail(cancelled()); }
        @Override public void onStartAsync(AsyncEvent event) { fail(cancelled()); }
    }
    private static ApiException cancelled() { return new ApiException(503, "DELIVERY_DOWNLOAD_CANCELLED", "工程下载已中断，请重新下载。"); }
    private static ApiException timeout() { return new ApiException(504, "DELIVERY_TIMEOUT", "工程下载已超时，请重新下载。"); }
    private static ApiException capacity() { return new ApiException(429, "DELIVERY_DOWNLOAD_CAPACITY", "已有工程正在下载，请等待完成后再试。"); }
}
