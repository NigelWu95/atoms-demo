package dev.atoms.demo.publication;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{id}/publication")
public class PublicationController {
    private final PublicationService publications;
    public PublicationController(PublicationService publications) { this.publications = publications; }
    @GetMapping PublicationService.Publication get(Principal principal, @PathVariable String id) {
        return publications.get(UUID.fromString(principal.getName()), id);
    }
    @PutMapping ResponseEntity<JsonNode> publish(Principal principal, @PathVariable String id, @RequestBody JsonNode input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = publications.publish(UUID.fromString(principal.getName()), id, input, key);
        return ResponseEntity.status(result.status()).body(result.body());
    }
    @DeleteMapping ResponseEntity<Void> unpublish(Principal principal, @PathVariable String id, @RequestParam long expectedPublicationRevision,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        publications.unpublish(UUID.fromString(principal.getName()), id, expectedPublicationRevision, key);
        return ResponseEntity.noContent().build();
    }
}
