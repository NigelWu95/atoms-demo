package dev.atoms.demo.projectdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Terminal filter in the dedicated chain: never forwards unknown preview routes to MVC/SPA/error pages. */
final class DeliveryPreviewFilter extends OncePerRequestFilter {
    private final DeliveryAccess access;
    private final DeliveryPreview preview;
    private final ObjectMapper json;
    DeliveryPreviewFilter(DeliveryAccess access, DeliveryPreview preview, ObjectMapper json) {
        this.access = access; this.preview = preview; this.json = json;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException {
        var budget = DeliveryAccess.deadline(); DeliveryHttpPolicy.closed(response);
        DeliveryGrants.Exchange lease = null;
        try {
            var route = DeliveryHttpPolicy.route(request);
            var input = DeliveryHttpPolicy.input(request, route);
            var grant = access.resolve(route.token());
            lease = access.acquire(grant);
            DeliveryHttpPolicy.authorized(request, response, route, grant.configuration().origin());
            preview.open(request, response, grant, input, lease, budget);
            lease = null; // The async exchange owns the slot until all actual work has ended.
        } catch (Exception failure) {
            reject(request, response, json, failure instanceof ApiException api ? api
                    : new ApiException(503, "DELIVERY_UNAVAILABLE", "预览暂不可用，请重新打开。"));
        } finally { if (lease != null) lease.close(); }
    }
    static void reject(HttpServletRequest request, HttpServletResponse response, ObjectMapper json, ApiException failure) throws IOException {
        response.setStatus(failure.status()); response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(), ApiError.of(request, failure.code(), failure.getMessage(), false));
    }
}
