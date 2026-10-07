package dev.atoms.demo.appdata;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/apps/{slug}/state")
public class PublicStateController {
    private final AppStateService states;
    public PublicStateController(AppStateService states) { this.states = states; }
    @GetMapping StateDocument get(Principal principal, @PathVariable String slug, @RequestParam String versionId) {
        return states.getLive(UUID.fromString(principal.getName()), slug, versionId);
    }
    @PutMapping ResponseEntity<JsonNode> save(Principal principal, @PathVariable String slug, @RequestBody JsonNode input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = states.saveLive(UUID.fromString(principal.getName()), slug, input, key);
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
