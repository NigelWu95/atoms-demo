package dev.atoms.generated;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP liveness only, not a claim that user business logic or database checks passed. */
@RestController
public class HealthController {
    public record Health(String status, String stage, String runId, String executionId) {}
    private final Health health;

    public HealthController(@Value("${atoms.runtime.run-id:}") String runId,
                            @Value("${atoms.runtime.execution-id:}") String executionId) {
        if (runId != null && executionId != null && runId.isEmpty() && executionId.isEmpty()) {
            health = new Health("UP", "INITIALIZED", null, null);
        } else {
            requireIdentity(runId); requireIdentity(executionId);
            health = new Health("UP", "INITIALIZED", runId, executionId);
        }
    }

    private static void requireIdentity(String value) {
        try {
            if (value == null || value.length() != 36 || !UUID.fromString(value).toString().equals(value))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid runtime identity"); }
    }

    @GetMapping("/api/health")
    public Health health() {
        return health;
    }
}
