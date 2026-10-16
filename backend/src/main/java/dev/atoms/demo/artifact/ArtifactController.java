package dev.atoms.demo.artifact;

import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{id}/versions")
public class ArtifactController {
    private final ArtifactService artifacts;
    private final VersionReviewService reviews;
    public ArtifactController(ArtifactService artifacts, VersionReviewService reviews) { this.artifacts = artifacts; this.reviews = reviews; }
    @GetMapping("/{versionId}/review")
    VersionReviewService.Review review(Principal principal, @PathVariable String id, @PathVariable String versionId) {
        return reviews.get(UUID.fromString(principal.getName()), id, versionId);
    }
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
