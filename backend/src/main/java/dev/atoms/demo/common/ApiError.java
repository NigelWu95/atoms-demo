package dev.atoms.demo.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

public record ApiError(Detail error, String requestId) {
    public record Detail(String code, String message, boolean retryable) {}
    public static ApiError of(HttpServletRequest request, String code, String message, boolean retryable) {
        Object id = request.getAttribute("requestId");
        return new ApiError(new Detail(code, message, retryable),
                id instanceof String value ? value : UUID.randomUUID().toString());
    }
}
