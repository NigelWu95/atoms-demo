package dev.atoms.demo.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.common.IdempotencyService;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService projects;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    public ProjectController(ProjectService projects, IdempotencyService idempotency, ObjectMapper json) {
        this.projects = projects; this.idempotency = idempotency; this.json = json;
    }
    @PostMapping
    ResponseEntity<JsonNode> create(Principal principal, @RequestBody JsonNode input,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        String name = name(input);
        UUID owner = UUID.fromString(principal.getName());
        var result = idempotency.execute(owner, "POST", "/api/projects", key, Map.of("name", name),
                () -> new IdempotencyService.Result(201, json.valueToTree(projects.create(owner, name))));
        return ResponseEntity.status(result.status()).body(result.body());
    }
    @GetMapping
    ProjectService.Page list(Principal principal, @RequestParam(required = false) String cursor,
                              @RequestParam(defaultValue = "20") int limit) {
        return projects.list(UUID.fromString(principal.getName()), cursor, limit);
    }
    @GetMapping("/{id}")
    Project get(Principal principal, @PathVariable String id) {
        return projects.get(UUID.fromString(principal.getName()), id);
    }
    @PatchMapping("/{id}")
    Project rename(Principal principal, @PathVariable String id, @RequestBody JsonNode input) {
        return projects.rename(UUID.fromString(principal.getName()), id, name(input));
    }
    private String name(JsonNode input) {
        if (!input.isObject() || input.size() != 1 || !input.path("name").isTextual()) throw invalidName();
        String name = input.path("name").asText().strip();
        if (name.isEmpty() || name.length() > 80 || name.codePoints().anyMatch(Character::isISOControl)) throw invalidName();
        return name;
    }
    private ApiException invalidName() {
        return new ApiException(422, "INVALID_INPUT", "只接受 name 字段：去除首尾空白后 1–80 字符，不能包含控制字符。");
    }
}
