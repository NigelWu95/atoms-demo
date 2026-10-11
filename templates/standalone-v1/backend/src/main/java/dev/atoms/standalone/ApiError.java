package dev.atoms.standalone;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

record ApiError(Detail error, String requestId) {
    record Detail(String code, String message, boolean retryable) {}

    static String requestId(HttpServletRequest request) {
        String key = ApiError.class.getName();
        if (request.getAttribute(key) instanceof String id) return id;
        String id = UUID.randomUUID().toString();
        request.setAttribute(key, id);
        return id;
    }

    static ApiError of(HttpServletRequest request, ApiException.Code code) {
        return new ApiError(new Detail(code.name(), code.message(), code.retryable()), requestId(request));
    }
}
