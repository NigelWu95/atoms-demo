package dev.atoms.demo.engineering;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.Artifact;
import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.artifact.ArtifactValidator;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.common.ApiExceptionHandler;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EngineeringExportTest {
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final UUID owner = UUID.randomUUID();
    private final ArtifactService.Version version = new ArtifactService.Version(UUID.randomUUID(), UUID.randomUUID(), 1,
            "ab".repeat(32), null, null, Instant.EPOCH, new Artifact(1,
            new Artifact.Manifest("测试", "测试", "web-static-v1", 1, new ObjectMapper().createObjectNode()),
            Map.of("view.html", "<h1>测试</h1>", "styles.css", "", "main.js", "")));

    @Test void capsConcurrentPackagingAndReleasesBothSlotsAfterFailure() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        authorize();
        var service = new EngineeringExportService(artifacts, new ArtifactValidator(), () -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            throw new IllegalStateException("private-path-canary");
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> invoke(service));
            var two = pool.submit(() -> invoke(service));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> invoke(service)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("EXPORT_BUSY");
            release.countDown();
            for (var task : java.util.List.of(one, two)) assertThatThrownBy(() -> task.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApiException.class).cause().hasMessage("工程打包未完成，请稍后重试。");
            assertThatThrownBy(() -> invoke(service)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("EXPORT_FAILED");
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void sanitizesPackagingErrorsButPreservesAuthorizationAndDatabaseErrors() throws Exception {
        authorize();
        var catalog = new java.util.concurrent.atomic.AtomicInteger();
        var service = new EngineeringExportService(artifacts, new ArtifactValidator(), () -> {
            catalog.incrementAndGet();
            throw new IllegalStateException("private-path-canary");
        });
        var mvc = MockMvcBuilders.standaloneSetup(new EngineeringExportController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String path = "/api/projects/" + version.projectId() + "/versions/" + version.id() + "/engineering.zip";
        mvc.perform(get(path).principal(() -> owner.toString()).accept("application/zip"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("EXPORT_FAILED"))
                .andExpect(jsonPath("$.requestId").isString()).andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-path-canary"))));
        when(artifacts.get(owner, version.projectId().toString(), version.id().toString()))
                .thenThrow(new ApiException(404, "PROJECT_NOT_FOUND", "项目不存在"));
        mvc.perform(get(path).principal(() -> owner.toString())).andExpect(status().isNotFound());
        doThrow(new DataAccessResourceFailureException("database-secret-canary")).when(artifacts)
                .get(owner, version.projectId().toString(), version.id().toString());
        mvc.perform(get(path).principal(() -> owner.toString())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
        assertThat(catalog).hasValue(1);
    }

    @Test void translatesRejectedHistoricalSourceAndTemplateIntoBundleError() {
        authorize();
        var service = new EngineeringExportService(artifacts, new ArtifactValidator(), () -> { throw EngineeringBundle.rejected(); });
        assertThatThrownBy(() -> invoke(service)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_ENGINEERING_BUNDLE");
    }
    private void authorize() { when(artifacts.get(owner, version.projectId().toString(), version.id().toString())).thenReturn(version); }
    private EngineeringExportService.Download invoke(EngineeringExportService service) {
        return service.download(owner, version.projectId().toString(), version.id().toString());
    }
}
