package dev.atoms.demo.run;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.demo.common.ApiException;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class RunController {
    private final RunAdmissionService admission;
    private final RunRepository repo;
    public RunController(RunAdmissionService admission, RunRepository repo) { this.admission = admission; this.repo = repo; }
    @GetMapping("/api/generation") RunAdmissionService.Availability availability() { return admission.availability(); }
    @PostMapping("/api/projects/{id}/runs")
    ResponseEntity<JsonNode> submit(Principal principal, @PathVariable String id, @RequestBody JsonNode input,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = admission.submit(UUID.fromString(principal.getName()), id, input, key);
        return ResponseEntity.status(result.status()).body(result.body());
    }
    @GetMapping("/api/runs/{id}") Run get(Principal principal, @PathVariable String id) { return repo.get(UUID.fromString(principal.getName()), id); }
    @GetMapping("/api/projects/{id}/runs")
    RunRepository.Page<Run> runs(Principal principal, @PathVariable String id, @RequestParam(required = false) String cursor,
                                 @RequestParam(defaultValue = "20") int limit) { return repo.runs(UUID.fromString(principal.getName()), id, cursor, limit); }
    @GetMapping("/api/projects/{id}/messages")
    RunRepository.Page<RunRepository.Message> messages(Principal principal, @PathVariable String id, @RequestParam(required = false) String cursor,
                                                       @RequestParam(defaultValue = "20") int limit) { return repo.messages(UUID.fromString(principal.getName()), id, cursor, limit); }
    @PutMapping("/api/runs/{id}/cancellation")
    Run cancel(Principal principal, @PathVariable String id, @RequestBody JsonNode input) {
        if (!input.isObject() || !input.isEmpty()) throw new ApiException(422, "INVALID_INPUT", "取消操作只接受空对象。");
        return admission.cancel(UUID.fromString(principal.getName()), id);
    }
}
