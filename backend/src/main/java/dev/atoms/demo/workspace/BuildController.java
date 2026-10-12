package dev.atoms.demo.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/builds")
public class BuildController {
    private final BuildAdmissionService admission;
    public BuildController(BuildAdmissionService admission) { this.admission=admission; }
    @PostMapping
    ResponseEntity<JsonNode> submit(Principal principal, @PathVariable String projectId, @RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        var result=admission.submit(UUID.fromString(principal.getName()),projectId,body,key);
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
