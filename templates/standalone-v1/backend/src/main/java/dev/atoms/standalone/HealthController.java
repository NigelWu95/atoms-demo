package dev.atoms.standalone;

import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Readiness includes the installation identity, not merely the existence of a database socket. */
@RestController
@DependsOnDatabaseInitialization
final class HealthController {
    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private final DataSource source;
    private final UUID instanceId;

    HealthController(DataSource source, @Value("${standalone.instance-id}") UUID instanceId) {
        this.source = source;
        this.instanceId = instanceId;
    }

    @GetMapping("/api/health")
    ResponseEntity<?> health() {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        boolean healthy = available();
        int status = healthy ? 200 : 503;
        var event = healthy ? log.atInfo() : log.atWarn();
        event.addKeyValue("event", "health_checked").addKeyValue("entryPoint", "health")
                .addKeyValue("requestId", requestId).addKeyValue("status", status)
                .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000).log("Database readiness checked");
        Object body = healthy ? new Health("UP") : new Failure(
                new Error("SERVICE_UNAVAILABLE", "服务暂不可用，请稍后重试。", true), requestId);
        return ResponseEntity.status(status).header("X-Request-Id", requestId).header("Cache-Control", "no-store").body(body);
    }

    private boolean available() {
        try (var connection = source.getConnection(); var query = connection.prepareStatement(
                "SELECT singleton, instance_id, format_version FROM standalone_app.installation LIMIT 2")) {
            query.setQueryTimeout(2);
            try (var row = query.executeQuery()) {
                return row.next() && row.getBoolean(1) && instanceId.equals(row.getObject(2, UUID.class))
                        && row.getInt(3) == 1 && !row.next();
            }
        } catch (SQLException ignored) {
            return false;
        }
    }

    record Health(String status) {}
    record Error(String code, String message, boolean retryable) {}
    record Failure(Error error, String requestId) {}
}
