package dev.atoms.demo.identity;

import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.AtomsDemoApplication;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

class SessionRestartIT {
    @Test
    void retainsIdentityAndProjectAcrossServerShutdownAndFreshApplicationContext() throws Exception {
        ApiBrowser browser;
        String userId;
        String projectId;
        try (var first = start()) {
            browser = new ApiBrowser(first.getWebServer().getPort());
            userId = browser.initialize().body().path("userId").asText();
            var created = browser.request("POST", "/api/projects", "{\"name\":\"重启保留\"}", UUID.randomUUID().toString());
            assertThat(created.status()).isEqualTo(201);
            projectId = created.body().path("id").asText();
        }
        try (var second = start()) {
            browser.usePort(second.getWebServer().getPort());
            var restored = browser.get("/api/me");
            assertThat(restored.status()).isEqualTo(200);
            assertThat(restored.body().path("userId").asText()).isEqualTo(userId);
            var project = browser.get("/api/projects/" + projectId);
            assertThat(project.status()).isEqualTo(200);
            assertThat(project.body().path("name").asText()).isEqualTo("重启保留");
            assertThat(browser.request("PATCH", "/api/projects/" + projectId, "{\"name\":\"重启后可修改\"}", null).status()).isEqualTo(200);
        }
    }
    private ServletWebServerApplicationContext start() {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(AtomsDemoApplication.class)
                .profiles("test").run("--server.port=0", "--spring.main.banner-mode=off");
    }
}
