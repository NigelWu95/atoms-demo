package dev.atoms.demo.engineering;

import dev.atoms.demo.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EngineeringExportController {
    private static final Logger log = LoggerFactory.getLogger(EngineeringExportController.class);
    private final EngineeringExportService exports;
    public EngineeringExportController(EngineeringExportService exports) { this.exports = exports; }

    @GetMapping("/api/projects/{projectId}/versions/{versionId}/engineering.zip")
    ResponseEntity<byte[]> download(Principal principal, @PathVariable String projectId,
                                    @PathVariable String versionId, HttpServletRequest request) {
        try {
            var download = exports.download(UUID.fromString(principal.getName()), projectId, versionId);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                    .contentLength(download.bytes().length)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.filename() + "\"")
                    .body(download.bytes());
        } catch (ApiException error) {
            if (error.code().equals("EXPORT_FAILED") || error.code().equals("EXPORT_BUSY"))
                log.warn("Engineering export refused code={} requestId={}", error.code(), request.getAttribute("requestId"));
            throw error;
        }
    }
}
