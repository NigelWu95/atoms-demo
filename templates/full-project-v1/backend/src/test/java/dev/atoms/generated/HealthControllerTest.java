package dev.atoms.generated;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// MVC slice: no database migration or listening socket is required for this test.
// https://docs.spring.io/spring-boot/3.5/reference/testing/spring-boot-applications.html#testing.spring-boot-applications.spring-mvc-tests
@WebMvcTest(HealthController.class)
@TestPropertySource(properties="APP_PORT=0")
class HealthControllerTest {
    @Autowired MockMvc mvc;

    @Test void healthDescribesInitializationNotGeneratedBusinessCompletion() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.stage").value("INITIALIZED"));
    }

    @Test void missingBusinessApiIsNotReportedAsSuccessful() throws Exception {
        mvc.perform(get("/api/not-generated")).andExpect(status().isNotFound());
    }
}
