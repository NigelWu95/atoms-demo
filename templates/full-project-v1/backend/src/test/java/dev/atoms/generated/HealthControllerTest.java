package dev.atoms.generated;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

// MVC slice: no database migration or listening socket is required for this test.
// https://docs.spring.io/spring-boot/3.5/reference/testing/spring-boot-applications.html#testing.spring-boot-applications.spring-mvc-tests
@WebMvcTest(HealthController.class)
@TestPropertySource(properties={"APP_PORT=0",
        "atoms.runtime.run-id=17b5ad93-7c41-4f86-936d-9af4041bd5f5",
        "atoms.runtime.execution-id=d37dfae4-5e3e-4fde-8c22-8dadf56539a4"})
class HealthControllerTest {
    @Autowired MockMvc mvc;

    @Test void healthDescribesInitializationNotGeneratedBusinessCompletion() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.stage").value("INITIALIZED"))
                .andExpect(jsonPath("$.runId").value("17b5ad93-7c41-4f86-936d-9af4041bd5f5"))
                .andExpect(jsonPath("$.executionId").value("d37dfae4-5e3e-4fde-8c22-8dadf56539a4"));
    }

    @Test void missingBusinessApiIsNotReportedAsSuccessful() throws Exception {
        mvc.perform(get("/api/not-generated")).andExpect(status().isNotFound());
    }

    @Test void standaloneStartupDoesNotInventPlatformIdentity() {
        var health = new HealthController("", "").health();
        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.runId()).isNull(); assertThat(health.executionId()).isNull();
    }

    @Test void incompleteOrMalformedManagedIdentityIsRejectedWithoutEchoingConfiguration() {
        String valid = "17b5ad93-7c41-4f86-936d-9af4041bd5f5";
        for (String[] pair : java.util.List.of(new String[]{valid, ""}, new String[]{"", valid},
                new String[]{"PRIVATE_CANARY", valid}, new String[]{valid, "1-1-1-1-1"},
                new String[]{valid.toUpperCase(java.util.Locale.ROOT), valid}, new String[]{null, valid})) {
            assertThatThrownBy(() -> new HealthController(pair[0], pair[1]))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid runtime identity").hasNoCause();
        }
    }
}
