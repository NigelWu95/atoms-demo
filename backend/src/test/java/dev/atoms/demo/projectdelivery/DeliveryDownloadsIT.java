package dev.atoms.demo.projectdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.projectexecution.ProjectExecutionAccess;
import dev.atoms.demo.source.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real servlet output, security chain and JDBC session; source/execution faults are controlled at the module boundary. */
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "atoms.project-delivery.enabled=true", "atoms.project-delivery.origin=http://127.0.0.1:5174"})
class DeliveryDownloadsIT {
    @LocalServerPort int port;
    @MockitoBean ProjectExecutionAccess execution;
    @Autowired DeliveryGrants grants;
    private final UUID project = UUID.randomUUID(), buildId = UUID.randomUUID(), version = UUID.randomUUID(), run = UUID.randomUUID();
    private final ProjectExecutionAccess.BuildHandle build = mock(ProjectExecutionAccess.BuildHandle.class);
    private final ProjectExecutionAccess.RunningHandle running = mock(ProjectExecutionAccess.RunningHandle.class);
    private final SourceTree tree = new ProjectSourcePolicy().validate(List.of(new SourceTree.File("src/retained.txt", "original\n".getBytes(StandardCharsets.UTF_8), 0100644)));
    private final ProjectExecutionAccess.BuildIdentity identity = new ProjectExecutionAccess.BuildIdentity(project, buildId, version, tree.sha256(), 12, "b".repeat(64), "c".repeat(64));
    private final ProjectExecutionAccess.RunningIdentity live = new ProjectExecutionAccess.RunningIdentity(run, UUID.randomUUID(), identity);
    private final ProjectSourceReadGateway.Snapshot snapshot = new ProjectSourceReadGateway.Snapshot(
            new SourceVersion(version, project, 1, "a".repeat(40), tree.sha256(), 1, tree.totalBytes(), null, null, null, null, null, Instant.EPOCH), tree);
    private final String base = "/api/projects/" + project + "/delivery";
    private ApiBrowser browser;
    private UUID owner;

    @BeforeEach void setup() throws Exception {
        grants.clear(); browser = new ApiBrowser(port); owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        doAnswer(call -> {
            if (!owner.equals(call.getArgument(0)) || !project.equals(call.getArgument(1))) throw new ApiException(404, "PROJECT_NOT_FOUND", "项目不可访问。");
            return null;
        }).when(execution).authorizeProject(any(), any(), any());
        when(execution.build(eq(owner), eq(project), eq(buildId), any())).thenReturn(build);
        when(execution.identity(eq(owner), eq(project), same(build), any())).thenReturn(identity);
        when(execution.source(eq(owner), eq(project), same(build), any())).thenReturn(snapshot);
        when(execution.running(eq(owner), eq(project), eq(run), any())).thenReturn(running);
        when(execution.identity(eq(owner), eq(project), same(running), any())).thenReturn(live);
        when(execution.source(eq(owner), eq(project), same(running), any())).thenReturn(snapshot);
    }

    @Test void servesActualAttachmentWithExactBytesNoStoreAndRecordedBuildAndPreviewIdentities() throws Exception {
        var view = browser.get(base + "/builds/" + buildId);
        assertThat(view.status()).isEqualTo(200); assertThat(view.body().path("identity").path("sourceVersionId").asText()).isEqualTo(version.toString());
        var response = browser.download(base + "/builds/" + buildId + "/source.zip");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition").orElse("")).isEqualTo("attachment; filename=\"source-" + buildId + ".zip\"");
        assertThat(response.headers().firstValue("Content-Type").orElse("")).isEqualTo("application/zip");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("Content-Security-Policy").orElse("")).contains("default-src 'none'");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(response.headers().firstValueAsLong("Content-Length").orElse(-1)).isEqualTo(response.body().length);
        var entries = entries(response.body()); assertThat(entries.get("project/src/retained.txt")).isEqualTo(tree.files().get(0).content());
        var preview = browser.request("POST", base + "/runs/" + run + "/previews", "{}", null);
        assertThat(preview.status()).isEqualTo(201);
        var previewZip = browser.download(base + "/previews/" + preview.body().path("previewId").asText() + "/source.zip");
        assertThat(previewZip.statusCode()).isEqualTo(200);
        var manifest = new ObjectMapper().readTree(entries(previewZip.body()).get("delivery/manifest.json"));
        assertThat(manifest.path("preview").path("executionId").asText()).isEqualTo(live.executionId().toString());
        assertThat(manifest.path("build").path("buildId").asText()).isEqualTo(buildId.toString());
    }

    @Test void invalidInputAnonymousAndForeignOwnerNeverReceiveArchive() throws Exception {
        var path = base + "/builds/" + buildId + "/source.zip";
        assertThat(browser.download(path + "?latest=true").statusCode()).isEqualTo(422);
        assertThat(browser.download(path.replace(buildId.toString(), "1-1-1-1-1")).statusCode()).isEqualTo(422);
        assertThat(new ApiBrowser(port).download(path).statusCode()).isEqualTo(401);
        verifyNoInteractions(execution);
        var stranger = new ApiBrowser(port); stranger.initialize();
        var refused = stranger.download(path); assertThat(refused.statusCode()).isEqualTo(404);
        assertThat(new String(refused.body(), StandardCharsets.UTF_8)).contains("PROJECT_NOT_FOUND").doesNotContain("PK", version.toString());
        verify(execution, never()).build(any(), any(), any(), any());
    }

    @Test void stoppingPreviewReturnsFixedErrorWhileHistoricalBuildRemainsDownloadable() throws Exception {
        var preview = browser.request("POST", base + "/runs/" + run + "/previews", null, null);
        var path = base + "/previews/" + preview.body().path("previewId").asText() + "/source.zip";
        when(execution.identity(eq(owner), eq(project), same(running), any())).thenThrow(new ApiException(409, "EXECUTION_ACCESS_REVOKED", "原运行已停止。"));
        var refused = browser.download(path); assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(new ObjectMapper().readTree(refused.body()).path("error").path("code").asText()).isEqualTo("EXECUTION_ACCESS_REVOKED");
        assertThat(browser.download(path).statusCode()).isEqualTo(404);
        assertThat(browser.download(base + "/builds/" + buildId + "/source.zip").statusCode()).isEqualTo(200);
        assertThat(browser.request("DELETE", "/api/session", null, null).status()).isEqualTo(204);
        assertThat(browser.download(base + "/builds/" + buildId + "/source.zip").statusCode()).isEqualTo(401);
    }

    private Map<String, byte[]> entries(byte[] bytes) throws Exception {
        var result = new HashMap<String, byte[]>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) result.put(entry.getName(), input.readAllBytes());
        }
        return result;
    }
}
