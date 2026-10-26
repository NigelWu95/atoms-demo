package dev.atoms.demo.projectdelivery;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.demo.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Management routes use the platform's existing authenticated, CSRF-protected chain. */
@RestController
@RequestMapping("/api/projects/{projectId}/delivery")
final class DeliveryController {
    private final DeliveryAccess access;
    DeliveryController(DeliveryAccess access) { this.access = access; }

    @PostMapping("/runs/{runId}/previews")
    ResponseEntity<DeliveryGrants.Preview> create(@PathVariable String projectId, @PathVariable String runId,
            @RequestBody(required = false) JsonNode body, Principal principal, HttpServletRequest request) {
        empty(body, request);
        var preview = access.issue(owner(principal), id(projectId), id(runId), session(request), DeliveryAccess.deadline());
        return ResponseEntity.status(201).body(preview);
    }

    @DeleteMapping("/previews/{previewId}")
    ResponseEntity<Void> revoke(@PathVariable String projectId, @PathVariable String previewId,
            @RequestBody(required = false) JsonNode body, Principal principal, HttpServletRequest request) {
        empty(body, request);
        access.revoke(owner(principal), id(projectId), id(previewId), session(request), DeliveryAccess.deadline());
        return ResponseEntity.noContent().build();
    }

    private static void empty(JsonNode body, HttpServletRequest request) {
        if (request.getQueryString() != null || body != null && (!body.isObject() || !body.isEmpty()))
            throw new ApiException(422, "INVALID_INPUT", "此接口只接受空对象，不接受目标地址或其他参数。");
    }
    static UUID id(String value) {
        try {
            UUID result = UUID.fromString(value);
            if (!result.toString().equals(value)) throw new IllegalArgumentException();
            return result;
        } catch (IllegalArgumentException invalid) { throw new ApiException(422, "INVALID_INPUT", "标识格式无效。"); }
    }
    static UUID owner(Principal principal) {
        if (principal == null) throw new ApiException(401, "SESSION_REQUIRED", "请重新登录后再操作。");
        return id(principal.getName());
    }
    static String session(HttpServletRequest request) {
        var session = request.getSession(false);
        return session == null ? null : session.getId();
    }
}
