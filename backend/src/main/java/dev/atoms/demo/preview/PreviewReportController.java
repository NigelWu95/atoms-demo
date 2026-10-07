package dev.atoms.demo.preview;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{id}/versions/{versionId}/preview-reports")
public class PreviewReportController {
    private final PreviewReportService reports;
    public PreviewReportController(PreviewReportService reports) { this.reports = reports; }
    @PostMapping ResponseEntity<JsonNode> save(Principal principal, @PathVariable String id, @PathVariable String versionId,
            @RequestBody JsonNode body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = reports.save(UUID.fromString(principal.getName()), id, versionId, body, key);
        return ResponseEntity.status(result.status()).body(result.body());
    }
    @GetMapping("/latest") JsonNode latest(Principal principal, @PathVariable String id, @PathVariable String versionId) {
        return reports.latest(UUID.fromString(principal.getName()), id, versionId);
    }
}
