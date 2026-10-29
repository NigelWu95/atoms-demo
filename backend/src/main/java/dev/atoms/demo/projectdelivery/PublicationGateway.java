package dev.atoms.demo.projectdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.projectexecution.ProjectExecutionAccess;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import dev.atoms.demo.source.SourceExecutionBudget;

/** Public listeners hold exact process handles, not browser sessions or arbitrary destinations. */
@Component
final class PublicationGateway {
    record Candidate(UUID owner, UUID project, UUID run, int internalPort) {}
    private final PublicationSettings settings;
    private final DeliverySettings delivery;
    private final ProjectExecutionAccess execution;
    private final Supplier<List<Candidate>> candidates;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<UUID, String> errors = new HashMap<>();
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> daemon(r, "publication-http"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "publication-lifecycle"));
    private boolean closed;

    @Autowired PublicationGateway(PublicationSettings settings, DeliverySettings delivery, ProjectExecutionAccess execution, JdbcTemplate jdbc) {
        this(settings, delivery, execution, () -> jdbc.query("""
                SELECT r.owner_id,r.project_id,r.id,b.application_port
                  FROM execution_runs r JOIN execution_database_bindings b ON b.id=r.binding_id
                  JOIN projects p ON p.id=r.project_id
                 WHERE r.status='READY' AND NOT r.stop_requested AND NOT p.archived
                 ORDER BY r.created_at LIMIT 5
                """, (rs, row) -> new Candidate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getInt(4))));
    }
    PublicationGateway(PublicationSettings settings, DeliverySettings delivery, ProjectExecutionAccess execution,
            Supplier<List<Candidate>> candidates) {
        this.settings = settings; this.delivery = delivery; this.execution = execution; this.candidates = candidates;
    }
    @EventListener(ApplicationReadyEvent.class) void start() {
        timer.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
    }
    synchronized void tick() {
        if (closed) return;
        try {
            var config = settings.load();
            if (config == null || !delivery.enabled()) { clear(); return; }
            var ready = candidates.get();
            if (ready.size() > 4 || ready.stream().map(Candidate::project).distinct().count() != ready.size()) throw PublicationSettings.invalid();
            var wanted = new HashSet<UUID>(); var internalPorts = new HashSet<Integer>();
            ready.forEach(c -> { wanted.add(c.run()); internalPorts.add(c.internalPort()); });
            entries.entrySet().removeIf(item -> {
                var entry = item.getValue();
                if (!wanted.contains(item.getKey()) || !entry.config.equals(config) || !ready.contains(entry.candidate)) {
                    entry.close(); return true;
                }
                return false;
            });
            errors.keySet().retainAll(wanted);
            for (var candidate : ready) {
                var existing = entries.get(candidate.run());
                if (existing != null) {
                    try {
                        execution.endpoint(candidate.owner(), candidate.project(), existing.handle, lifecycleBudget());
                        continue;
                    } catch (RuntimeException lost) { existing.close(); entries.remove(candidate.run()); }
                }
                try {
                    if (internalPorts.contains(config.port(candidate.internalPort()))
                            || config.origin(candidate.internalPort()).equals(delivery.load().origin())) throw PublicationSettings.invalid();
                    var budget = lifecycleBudget();
                    var handle = execution.running(candidate.owner(), candidate.project(), candidate.run(), budget);
                    var endpoint = execution.endpoint(candidate.owner(), candidate.project(), handle, budget);
                    if (endpoint.port() != candidate.internalPort() || !endpoint.identity().runId().equals(candidate.run())) throw unavailable();
                    var server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port(candidate.internalPort())), 16);
                    var entry = new Entry(candidate, config, handle, server);
                    try {
                        server.createContext("/", entry::serve); server.setExecutor(requests); server.start();
                        entries.put(candidate.run(), entry); errors.remove(candidate.run());
                    } catch (RuntimeException failure) { entry.close(); throw failure; }
                } catch (IOException failure) { errors.put(candidate.run(), "PUBLICATION_PORT_UNAVAILABLE"); }
                catch (RuntimeException failure) { errors.put(candidate.run(), "PUBLICATION_NOT_READY"); }
            }
        } catch (RuntimeException unavailable) { clear(); }
    }
    synchronized String address(UUID owner, UUID project, UUID run, int internalPort) {
        if (settings.load() == null) return null;
        var entry = entries.get(run);
        if (entry == null) {
            String code = errors.getOrDefault(run, "PUBLICATION_NOT_READY");
            throw new ApiException(409, code, code.equals("PUBLICATION_PORT_UNAVAILABLE")
                    ? "应用发布端口被占用或无法监听，请检查服务器端口。" : "应用发布入口尚未就绪，请稍后重新获取地址。");
        }
        if (!entry.candidate.equals(new Candidate(owner, project, run, internalPort)) || !entry.current()) throw unavailable();
        return entry.config.origin(internalPort) + "/";
    }
    @PreDestroy synchronized void close() { closed = true; timer.shutdownNow(); clear(); requests.shutdownNow(); }
    private void clear() { entries.values().forEach(Entry::close); entries.clear(); errors.clear(); }
    private static Thread daemon(Runnable work, String name) { var thread = new Thread(work, name); thread.setDaemon(true); return thread; }
    private static SourceExecutionBudget lifecycleBudget() {
        long started = System.nanoTime();
        return () -> {
            long remaining = Duration.ofSeconds(5).toNanos() - (System.nanoTime() - started);
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new IOException("PUBLICATION_TIMEOUT");
            return Duration.ofNanos(remaining);
        };
    }
    private static ApiException unavailable() { return new ApiException(503, "PUBLICATION_UNAVAILABLE", "应用已下线或发布状态已变化。"); }

    private final class Entry {
        final Candidate candidate;
        final PublicationSettings.Configuration config;
        final ProjectExecutionAccess.RunningHandle handle;
        final HttpServer server;
        final PublicationCookies cookies;
        final Semaphore slots = new Semaphore(4);
        volatile boolean active = true;
        Entry(Candidate candidate, PublicationSettings.Configuration config, ProjectExecutionAccess.RunningHandle handle, HttpServer server) {
            this.candidate = candidate; this.config = config; this.handle = handle; this.server = server;
            cookies = new PublicationCookies(candidate.project());
        }
        boolean current() { return active && config.equals(settings.load()) && delivery.enabled(); }
        void close() { active = false; server.stop(0); }
        void serve(HttpExchange exchange) throws IOException {
            boolean acquired = slots.tryAcquire(); boolean sent = false, completedWrite = false;
            try {
                PublicationHttpPolicy.response(exchange);
                if (!acquired) throw new ApiException(429, "PUBLICATION_BUSY", "应用请求较多，请稍后重试。");
                if (!current()) throw unavailable();
                var budget = DeliveryAccess.deadline();
                var input = PublicationHttpPolicy.read(exchange, config.origin(candidate.internalPort()), cookies);
                var output = execution.exchange(candidate.owner(), candidate.project(), handle, input, budget);
                completedWrite = input.write();
                if (!current()) throw unavailable();
                var headers = exchange.getResponseHeaders();
                if (output.contentType() != null) headers.set("Content-Type", output.contentType());
                if (output.etag() != null) headers.set("ETag", output.etag());
                var isolated = cookies.outgoing(output.applicationCookies());
                if (!isolated.isEmpty()) headers.put("Set-Cookie", isolated);
                boolean empty = input.method().equals("HEAD") || output.bytes() == 0 || Set.of(204, 205, 304).contains(output.status());
                exchange.sendResponseHeaders(output.status(), empty ? -1 : output.bytes()); sent = true;
                if (!empty) exchange.getResponseBody().write(output.body());
            } catch (Exception failure) {
                if (!sent) {
                    var error = completedWrite ? new ApiException(502, "DELIVERY_WRITE_UNCERTAIN", "写入结果未知")
                            : failure instanceof ApiException api ? api : unavailable();
                    // No exception messages, request cookies, upstream bodies or credentials in errors/logs.
                    String message = error.code().equals("DELIVERY_WRITE_UNCERTAIN")
                            ? "无法确认应用写入结果，请先查询确认，勿自动重试。" : "应用请求未完成，请检查应用状态后重试。";
                    byte[] body = json.writeValueAsBytes(Map.of("error", Map.of("code", error.code(), "message", message)));
                    exchange.getResponseHeaders().remove("Set-Cookie");
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(error.status(), exchange.getRequestMethod().equals("HEAD") ? -1 : body.length);
                    if (!exchange.getRequestMethod().equals("HEAD")) exchange.getResponseBody().write(body);
                }
            } finally { exchange.close(); if (acquired) slots.release(); }
        }
    }
}
