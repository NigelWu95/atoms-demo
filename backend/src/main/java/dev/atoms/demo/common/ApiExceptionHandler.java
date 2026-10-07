package dev.atoms.demo.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> business(ApiException error, HttpServletRequest request) {
        return ResponseEntity.status(error.status()).body(ApiError.of(request, error.code(), error.getMessage(), false));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> invalidRequest(Exception error, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiError.of(request, "INVALID_JSON", "请求格式不正确。", false));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> unavailable(DataAccessException error, HttpServletRequest request) {
        var body = ApiError.of(request, "SERVICE_UNAVAILABLE", "服务暂不可用，请稍后重试。", true);
        log.warn("Database operation unavailable requestId={}", body.requestId());
        return ResponseEntity.status(503).body(body);
    }
}
