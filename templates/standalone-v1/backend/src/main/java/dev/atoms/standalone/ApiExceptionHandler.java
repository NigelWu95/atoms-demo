package dev.atoms.standalone;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
final class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ObjectMapper json;
    ApiExceptionHandler(ObjectMapper json) { this.json = json; }

    // MVC exceptions and filter/security failures use exactly the same allowlisted contract.
    // https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-rest-exceptions.html
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handle(Exception failure, HttpServletRequest request) {
        var code = classify(failure);
        var body = body(request, code);
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", body.requestId()).header("Cache-Control", "no-store").body(body);
    }

    void write(HttpServletRequest request, HttpServletResponse response, ApiException.Code code) throws IOException {
        var body = body(request, code);
        response.setStatus(code.status());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("X-Request-Id", body.requestId());
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), body);
    }

    static ApiException.Code classify(Throwable failure) {
        for (int depth = 0; failure != null && depth < 10; depth++, failure = failure.getCause()) {
            if (failure instanceof ApiException api) return api.code();
            if (failure instanceof DataAccessException || failure instanceof java.sql.SQLException)
                return ApiException.Code.SERVICE_UNAVAILABLE;
            if (failure instanceof NoResourceFoundException) return ApiException.Code.NOT_FOUND;
            if (failure instanceof HttpRequestMethodNotSupportedException) return ApiException.Code.METHOD_NOT_ALLOWED;
            if (failure instanceof HttpMessageNotReadableException || failure instanceof HttpMediaTypeNotSupportedException
                    || failure instanceof org.springframework.web.HttpMediaTypeNotAcceptableException)
                return ApiException.Code.INVALID_INPUT;
        }
        return ApiException.Code.INTERNAL_ERROR;
    }

    private ApiError body(HttpServletRequest request, ApiException.Code code) {
        var body = ApiError.of(request, code);
        var event = code.status() >= 500 ? log.atWarn() : log.atInfo();
        event.addKeyValue("event", "api_rejected").addKeyValue("entryPoint", "http")
                .addKeyValue("requestId", body.requestId()).addKeyValue("code", code.name())
                .addKeyValue("status", code.status()).log("API request rejected");
        return body;
    }
}
