package dev.atoms.standalone;

import java.security.Principal;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Single installed application; callers cannot select platform projects, versions or files. */
@RestController
final class AppController {
    private final RuntimeResources resources;
    private final RuntimeDocumentService documents;
    private final GuestIdentityService guests;

    AppController(RuntimeResources resources, RuntimeDocumentService documents, GuestIdentityService guests) {
        this.resources = resources; this.documents = documents; this.guests = guests;
    }
    record Description(String title, String summary, String runtime, int stateSchemaVersion, String loadedRuntimeId) {}

    @GetMapping("/api/app")
    Description application(Principal principal) {
        guests.get(principal.getName());
        var artifact = resources.artifact();
        return new Description(artifact.title(), artifact.summary(), "web-static-v1", 1, artifact.loadedRuntimeId());
    }
    @GetMapping(value = "/api/app/document", produces = "text/html;charset=UTF-8")
    ResponseEntity<String> document(Principal principal, jakarta.servlet.http.HttpServletResponse response) {
        guests.get(principal.getName());
        // ResponseEntity headers append to the filter's default CSP. Replace it instead:
        // multiple CSP policies intersect and would prevent both scripts and embedding.
        response.setHeader("Content-Security-Policy", RuntimeDocumentPolicy.CSP);
        return ResponseEntity.ok().contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .body(documents.render());
    }
}
