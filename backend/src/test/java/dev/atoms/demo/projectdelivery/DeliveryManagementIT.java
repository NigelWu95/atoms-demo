package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.projectexecution.ProjectExecutionAccess;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP + JDBC sessions + CSRF; only the already separately tested execution boundary is simulated. */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "atoms.project-delivery.enabled=true", "atoms.project-delivery.origin=http://127.0.0.1:5174"})
class DeliveryManagementIT {
    @LocalServerPort int port;
    @Autowired DeliveryAccess access;
    @Autowired DeliveryGrants grants;
    @MockitoBean ProjectExecutionAccess execution;
    private final UUID project = UUID.randomUUID(), run = UUID.randomUUID();
    private ApiBrowser browser;
    private UUID owner;
    private final String path = "/api/projects/" + project + "/delivery/runs/" + run + "/previews";

    @BeforeEach void authorizedExecution() throws Exception {
        grants.clear(); browser = new ApiBrowser(port);
        owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        var handle = mock(ProjectExecutionAccess.RunningHandle.class);
        var identity = new ProjectExecutionAccess.RunningIdentity(run, UUID.randomUUID(),
                new ProjectExecutionAccess.BuildIdentity(project, UUID.randomUUID(), UUID.randomUUID(),
                        "a".repeat(64), 1, "b".repeat(64), "c".repeat(64)));
        when(execution.running(any(), eq(project), eq(run), any())).thenAnswer(call -> {
            if (!owner.equals(call.getArgument(0))) throw new ApiException(404, "PROJECT_NOT_FOUND", "项目不可访问。");
            return handle;
        });
        when(execution.identity(eq(owner), eq(project), same(handle), any())).thenReturn(identity);
    }

    @Test void issuesWithRealSessionAndRevokesIdempotentlyWithoutStoppingApplication() throws Exception {
        var created = browser.request("POST", path, "{}", null);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.raw().headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(created.body().path("identity").path("runId").asText()).isEqualTo(run.toString());
        String url = created.body().path("iframeUrl").asText();
        assertThat(url).matches("/api/delivery/preview/[A-Za-z0-9_-]{43}/");
        var token = url.substring(DeliveryGrants.PREFIX.length(), url.length() - 1);
        assertThat(access.resolve(token).owner()).isEqualTo(owner);
        var revoke = "/api/projects/" + project + "/delivery/previews/" + created.body().path("previewId").asText();
        for (int i = 0; i < 2; i++) assertThat(browser.request("DELETE", revoke, null, null).status()).isEqualTo(204);
        assertThatThrownBy(() -> access.resolve(token)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("DELIVERY_PREVIEW_NOT_FOUND"));
        verify(execution, times(2)).authorizeProject(eq(owner), eq(project), any());
    }

    @Test void missingCsrfAnonymousAndOtherOwnerCannotIssueAndNullOriginCannotBypassManagement() throws Exception {
        assertThat(browser.request("POST", path, "{}", null, null).status()).isEqualTo(403);
        var anonymous = new ApiBrowser(port); anonymous.refreshCsrf();
        assertThat(anonymous.request("POST", path, "{}", null).status()).isEqualTo(401);
        verifyNoInteractions(execution);
        var stranger = new ApiBrowser(port); stranger.initialize();
        assertThat(stranger.request("POST", path, "{}", null).status()).isEqualTo(404);
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Origin", "null").header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(response.statusCode()).isIn(401, 403);
    }

    @Test void rejectsTargetInputAndNoncanonicalIdentifiersBeforeExecution() throws Exception {
        for (String body : new String[]{"{\"url\":\"http://127.0.0.1:80\"}", "[]", "false"})
            assertThat(browser.request("POST", path, body, null).status()).isEqualTo(422);
        assertThat(browser.request("POST", path + "?url=anything", "{}", null).status()).isEqualTo(422);
        assertThat(browser.request("POST", path.replace(project.toString(), "1-1-1-1-1"), "{}", null).status()).isEqualTo(422);
        verifyNoInteractions(execution);
    }

    @Test void actualLogoutInvalidatesPreviouslyIssuedCapability() throws Exception {
        var preview = browser.request("POST", path, null, null);
        assertThat(preview.status()).isEqualTo(201);
        String url = preview.body().path("iframeUrl").asText(), token = url.substring(DeliveryGrants.PREFIX.length(), url.length() - 1);
        assertThat(access.resolve(token)).isNotNull();
        assertThat(browser.request("DELETE", "/api/session", null, null).status()).isEqualTo(204);
        assertThatThrownBy(() -> access.resolve(token)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("SESSION_REQUIRED"));
        browser.initialize();
        assertThatThrownBy(() -> access.resolve(token)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("DELIVERY_PREVIEW_NOT_FOUND"));
    }
}
