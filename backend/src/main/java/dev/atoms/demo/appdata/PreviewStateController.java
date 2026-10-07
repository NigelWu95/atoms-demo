package dev.atoms.demo.appdata;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{id}/preview-state")
public class PreviewStateController {
    private final AppStateService states;
    public PreviewStateController(AppStateService states) { this.states = states; }
    @GetMapping
    StateDocument get(Principal principal, @PathVariable String id, @RequestParam String versionId) {
        return states.get(UUID.fromString(principal.getName()), id, versionId);
    }
    @PutMapping
    ResponseEntity<JsonNode> save(Principal principal, @PathVariable String id, @RequestBody JsonNode input,
                                @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = states.save(UUID.fromString(principal.getName()), id, input, key);
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
