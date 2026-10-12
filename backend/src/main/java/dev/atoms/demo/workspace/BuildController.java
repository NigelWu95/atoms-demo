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
    private final BuildQueryService queries;
    private final BuildCancellationService cancellation;
    public BuildController(BuildAdmissionService admission, BuildQueryService queries, BuildCancellationService cancellation) {
        this.admission=admission; this.queries=queries; this.cancellation=cancellation;
    }
    @PostMapping
    ResponseEntity<JsonNode> submit(Principal principal, @PathVariable String projectId, @RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        var result=admission.submit(UUID.fromString(principal.getName()),projectId,body,key);
        return ResponseEntity.status(result.status()).body(result.body());
    }
    @GetMapping
    BuildQueryService.Page list(Principal principal,@PathVariable String projectId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return queries.list(UUID.fromString(principal.getName()),projectId,cursor,limit);
    }
    @GetMapping("/{buildId}")
    EngineeringBuild get(Principal principal,@PathVariable String projectId,@PathVariable String buildId) {
        return queries.get(UUID.fromString(principal.getName()),projectId,buildId);
    }
    @GetMapping("/{buildId}/events")
    BuildQueryService.Events events(Principal principal,@PathVariable String projectId,@PathVariable String buildId,
            @RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="100") int limit) {
        return queries.events(UUID.fromString(principal.getName()),projectId,buildId,after,limit);
    }
    @PostMapping("/{buildId}/cancellations")
    ResponseEntity<JsonNode> cancel(Principal principal,@PathVariable String projectId,@PathVariable String buildId,
            @RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var result=cancellation.cancel(UUID.fromString(principal.getName()),projectId,buildId,body,key);
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
