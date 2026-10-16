package dev.atoms.demo.project;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{id}/draft")
public class ProjectDraftController {
    private final ProjectDraftService drafts;
    public ProjectDraftController(ProjectDraftService drafts) { this.drafts = drafts; }
    @GetMapping
    ProjectDraftService.Draft get(Principal principal, @PathVariable String id) {
        return drafts.get(UUID.fromString(principal.getName()), id);
    }
    @PutMapping
    ProjectDraftService.Draft save(Principal principal, @PathVariable String id, @RequestBody JsonNode input) {
        return drafts.save(UUID.fromString(principal.getName()), id, input);
    }
}
