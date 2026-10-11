package dev.atoms.standalone;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorsTest {
    private final ApiExceptionHandler errors = new ApiExceptionHandler(new ObjectMapper());

    @ParameterizedTest
    @EnumSource(ApiException.Code.class)
    void errorCodesHaveStableStatusRetryAndSafeMessages(ApiException.Code code) throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        errors.write(request, response, code);
        var body = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(code.status());
        assertThat(body.path("error").path("code").asText()).isEqualTo(code.name());
        assertThat(body.path("error").path("retryable").asBoolean()).isEqualTo(code.retryable());
        assertThat(body.path("error").path("message").asText()).isNotBlank();
        assertThat(body.path("requestId").asText()).isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(java.util.UUID.fromString(body.path("requestId").asText())).isNotNull();
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void internalExceptionValuesCannotBecomeAnApiMessage() {
        var request = new MockHttpServletRequest();
        var database = errors.handle(new DataAccessResourceFailureException("jdbc:secret SQL password"), request);
        assertThat(database.getStatusCode().value()).isEqualTo(503);
        assertThat(database.getBody().toString()).doesNotContain("jdbc:", "password", "SQL");
        var unexpected = errors.handle(new IllegalStateException("/private/secret"), request);
        assertThat(unexpected.getStatusCode().value()).isEqualTo(500);
        assertThat(unexpected.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(unexpected.getBody().toString()).doesNotContain("/private/");
        assertThat(unexpected.getBody().requestId()).isEqualTo(database.getBody().requestId());
    }

    @Test
    void clientSuppliedCorrelationHeaderIsNotTrusted() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "attacker-provided-secret");
        assertThat(ApiError.of(request, ApiException.Code.INVALID_INPUT).requestId())
                .isNotEqualTo("attacker-provided-secret");
    }
}
