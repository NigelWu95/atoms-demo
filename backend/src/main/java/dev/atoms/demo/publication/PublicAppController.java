package dev.atoms.demo.publication;

import dev.atoms.demo.preview.RuntimeDocumentPolicy;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/apps/{slug}")
public class PublicAppController {
    private final PublicAppService apps;
    public PublicAppController(PublicAppService apps) { this.apps = apps; }
    @GetMapping PublicAppService.Metadata metadata(@PathVariable String slug) { return apps.metadata(slug); }
    @GetMapping("/document") ResponseEntity<String> document(@PathVariable String slug, @RequestParam String versionId, HttpServletResponse response) {
        String html = apps.document(slug, versionId);
        response.setHeader("Content-Security-Policy", RuntimeDocumentPolicy.CSP);
        response.setHeader("Referrer-Policy", "no-referrer");
        return ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8)).body(html);
    }
}
