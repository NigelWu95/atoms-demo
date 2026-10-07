package dev.atoms.demo.preview;

import java.security.Principal;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class RuntimeDocumentController {
    private final RuntimeDocumentService documents;
    public RuntimeDocumentController(RuntimeDocumentService documents) { this.documents = documents; }
    @GetMapping("/api/projects/{id}/versions/{versionId}/document")
    ResponseEntity<String> document(Principal principal, @PathVariable String id, @PathVariable String versionId,
                                     @RequestParam(defaultValue = "CURRENT") String previewMode, HttpServletResponse response) {
        String html = documents.render(UUID.fromString(principal.getName()), id, versionId, previewMode);
        // CSP sandbox must be an HTTP response header; meta/Report-Only cannot provide this isolation.
        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/sandbox
        // Replace, don't append: two CSP headers intersect and the default policy would block every script/frame.
        response.setHeader("Content-Security-Policy", RuntimeDocumentPolicy.CSP);
        response.setHeader("Referrer-Policy", "no-referrer");
        return ResponseEntity.ok().contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8)).body(html);
    }
}
