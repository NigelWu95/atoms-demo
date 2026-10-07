package dev.atoms.demo.run;

import dev.atoms.demo.common.ApiException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Read-only bounded tailers, deliberately separate from the generation scheduler. */
@Service
public class RunEventStreams {
    private final RunRepository runs;
    private final ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(4);
    private final Set<Subscription> active = ConcurrentHashMap.newKeySet();
    public RunEventStreams(RunRepository runs) { this.runs = runs; pool.setRemoveOnCancelPolicy(true); }

    SseEmitter open(Run run, long after) {
        var subscription = new Subscription(run, after);
        synchronized (active) {
            if (active.size() >= 32) throw new ApiException(429, "EVENT_STREAM_LIMIT", "进度连接已满，请稍后刷新任务状态。");
            active.add(subscription);
        }
        subscription.emitter.onCompletion(subscription::release);
        subscription.emitter.onTimeout(subscription::finish);
        subscription.emitter.onError(error -> subscription.release());
        try {
            subscription.future = pool.scheduleWithFixedDelay(subscription::tick, 0, 500, TimeUnit.MILLISECONDS);
            if (subscription.closed.get()) subscription.future.cancel(false);
        } catch (RuntimeException stopped) { subscription.release(); throw stopped; }
        return subscription.emitter;
    }
    @PreDestroy void shutdown() {
        active.forEach(Subscription::finish);
        pool.shutdownNow();
    }
    private final class Subscription {
        final Run identity;
        final SseEmitter emitter = new SseEmitter(60000L);
        final AtomicBoolean closed = new AtomicBoolean();
        volatile ScheduledFuture<?> future;
        long cursor;
        long lastHeartbeat = System.nanoTime();
        boolean connected;
        Subscription(Run run, long cursor) { this.identity = run; this.cursor = cursor; }

        void tick() {
            if (closed.get()) return;
            try {
                if (!connected) {
                    emitter.send(SseEmitter.event().comment("connected").reconnectTime(2000));
                    connected = true;
                }
                Run snapshot = runs.get(identity.ownerId(), identity.id().toString());
                for (var event : runs.events(snapshot, cursor)) {
                    if (closed.get()) return;
                    emitter.send(SseEmitter.event().id(Long.toString(event.seq())).name(event.type()).data(event.data()));
                    cursor = event.seq();
                }
                if (!snapshot.active() && cursor == snapshot.lastEventSeq()) { finish(); return; }
                if (System.nanoTime() - lastHeartbeat >= TimeUnit.SECONDS.toNanos(15)) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeat = System.nanoTime();
                }
            } catch (IOException disconnected) {
                // Spring owns connection cleanup after a write error; do not completeWithError.
                // https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html
                release();
            } catch (ApiException invalid) {
                try { emitter.send(SseEmitter.event().name("stream.reset").data(java.util.Map.of("code", "EVENT_STREAM_RESET"))); }
                catch (IOException disconnected) { release(); return; }
                finish();
            } catch (RuntimeException unavailable) { finish(); }
        }
        void finish() { release(); emitter.complete(); }
        void release() {
            if (closed.compareAndSet(false, true)) {
                active.remove(this);
                if (future != null) future.cancel(false);
            }
        }
    }
}
