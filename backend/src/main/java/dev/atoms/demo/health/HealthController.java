package dev.atoms.demo.health;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/health")
    public ResponseEntity<?> health() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(new Health("UP"));
        } catch (DataAccessException exception) {
            String requestId = UUID.randomUUID().toString();
            log.warn("Database health check unavailable requestId={}", requestId);
            return ResponseEntity.status(503).body(new Failure(
                    new Error("SERVICE_UNAVAILABLE", "服务暂不可用，请稍后重试。", true), requestId));
        }
    }

    record Health(String status) {}
    record Error(String code, String message, boolean retryable) {}
    record Failure(Error error, String requestId) {}
}
