package dev.atoms.generated;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP liveness only, not a claim that user business logic or database checks passed. */
@RestController
public class HealthController {
    public record Health(String status, String stage) {}

    @GetMapping("/api/health")
    public Health health() {
        return new Health("UP", "INITIALIZED");
    }
}
