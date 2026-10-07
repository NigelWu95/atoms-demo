package dev.atoms.demo.artifact;

import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{id}/versions")
public class ArtifactController {
    private final ArtifactService artifacts;
    public ArtifactController(ArtifactService artifacts) { this.artifacts = artifacts; }
    @GetMapping
    ArtifactService.Page list(Principal principal, @PathVariable String id, @RequestParam(required = false) String cursor,
                              @RequestParam(defaultValue = "20") int limit) {
        return artifacts.list(UUID.fromString(principal.getName()), id, cursor, limit);
    }
    @GetMapping("/{versionId}")
    ArtifactService.Version get(Principal principal, @PathVariable String id, @PathVariable String versionId) {
        return artifacts.get(UUID.fromString(principal.getName()), id, versionId);
    }
}
