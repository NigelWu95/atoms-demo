package dev.atoms.demo.projectdelivery;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.source.ProjectSourceRestorationGateway;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Collections;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static dev.atoms.demo.projectdelivery.DeliveryController.*;

/** Existing management authentication and CSRF protect restoration; this never builds or starts an application. */
@RestController
@RequestMapping("/api/projects/{projectId}/delivery")
final class DeliveryRestorationController {
    private static final Set<String> FIELDS = Set.of("targetSourceVersionId", "expectedSourceVersionId", "expectedRevision");
    private final DeliverySources authorization;
    private final ProjectSourceRestorationGateway source;

    DeliveryRestorationController(DeliverySources authorization, ProjectSourceRestorationGateway source) {
        this.authorization = authorization; this.source = source;
    }

    @GetMapping("/source-head")
    ProjectSourceRestorationGateway.Head head(@PathVariable String projectId, Principal principal, HttpServletRequest request) {
        noInput(request); var owner = owner(principal); var project = id(projectId);
        authorization.authorize(owner, project, session(request), DeliveryAccess.deadline());
        return source.head(owner, project);
    }

    @PostMapping("/restorations")
    ResponseEntity<ProjectSourceRestorationGateway.Operation> restore(@PathVariable String projectId, @RequestBody JsonNode body,
            Principal principal, HttpServletRequest request) {
        if (request.getQueryString() != null || body == null || !body.isObject() || body.size() != FIELDS.size()
                || !body.hasNonNull("targetSourceVersionId") || !body.get("targetSourceVersionId").isTextual()
                || !body.hasNonNull("expectedSourceVersionId") || !body.get("expectedSourceVersionId").isTextual()
                || !body.hasNonNull("expectedRevision") || !body.get("expectedRevision").isIntegralNumber()
                || !body.get("expectedRevision").canConvertToLong() || body.get("expectedRevision").longValue() < 1) throw invalid();
        var fields = body.fieldNames(); while (fields.hasNext()) if (!FIELDS.contains(fields.next())) throw invalid();
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1 || keys.get(0).isBlank() || keys.get(0).length() > 128
                || keys.get(0).codePoints().anyMatch(Character::isISOControl)) throw invalid();
        var target = id(body.get("targetSourceVersionId").asText()); var expected = id(body.get("expectedSourceVersionId").asText());
        var owner = owner(principal); var project = id(projectId);
        authorization.authorize(owner, project, session(request), DeliveryAccess.deadline());
        var operation = source.restore(owner, project, target, expected, body.get("expectedRevision").longValue(), keys.get(0));
        return ResponseEntity.accepted().body(operation);
    }

    @GetMapping("/operations/{operationId}")
    ProjectSourceRestorationGateway.Operation operation(@PathVariable String projectId, @PathVariable String operationId,
            Principal principal, HttpServletRequest request) {
        noInput(request); var owner = owner(principal); var project = id(projectId); var operation = id(operationId);
        authorization.authorize(owner, project, session(request), DeliveryAccess.deadline());
        return source.operation(owner, project, operation);
    }

    private static void noInput(HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) throw invalid();
    }
    private static ApiException invalid() { return new ApiException(422, "INVALID_INPUT", "请提供规范的源码版本、修订号和唯一请求标识。"); }
}
