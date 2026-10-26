package dev.atoms.demo.projectdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.identity.SessionAccess;
import dev.atoms.demo.projectexecution.ProjectExecutionAccess;
import dev.atoms.demo.source.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliverySourcesTest {
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), buildId = UUID.randomUUID(), version = UUID.randomUUID();
    private final String session = "original-session";
    private final SourceExecutionBudget budget = () -> Duration.ofSeconds(30);
    private final MockEnvironment environment = new MockEnvironment().withProperty("atoms.project-delivery.enabled", "true")
            .withProperty("atoms.project-delivery.origin", "http://127.0.0.1:5174");
    private final SessionAccess sessions = mock(SessionAccess.class);
    private final ProjectExecutionAccess execution = mock(ProjectExecutionAccess.class);
    private final ProjectExecutionAccess.BuildHandle build = mock(ProjectExecutionAccess.BuildHandle.class);
    private final ProjectExecutionAccess.RunningHandle running = mock(ProjectExecutionAccess.RunningHandle.class);
    private final SourceTree tree = new ProjectSourcePolicy().validate(List.of(
            new SourceTree.File("original.txt", "old exact source\n".getBytes(StandardCharsets.UTF_8), 0100644)));
    private final ProjectSourceReadGateway.Snapshot snapshot = new ProjectSourceReadGateway.Snapshot(
            new SourceVersion(version, project, 1, "a".repeat(40), tree.sha256(), 1, tree.totalBytes(), null, null, null, null, null, Instant.EPOCH), tree);
    private final ProjectExecutionAccess.BuildIdentity identity = new ProjectExecutionAccess.BuildIdentity(project, buildId, version,
            tree.sha256(), 123, "b".repeat(64), "c".repeat(64));
    private final ProjectExecutionAccess.RunningIdentity run = new ProjectExecutionAccess.RunningIdentity(UUID.randomUUID(), UUID.randomUUID(), identity);
    private final DeliveryGrants grants = new DeliveryGrants();
    private final DeliveryAccess access = new DeliveryAccess(new DeliverySettings(environment), grants, sessions, execution);
    private final DeliverySources sources = new DeliverySources(access, execution);

    @BeforeEach void setup() {
        when(sessions.streamValid(session, owner)).thenReturn(true);
        when(execution.build(eq(owner), eq(project), eq(buildId), any())).thenReturn(build);
        when(execution.identity(eq(owner), eq(project), same(build), any())).thenReturn(identity);
        when(execution.source(eq(owner), eq(project), same(build), any())).thenReturn(snapshot);
        when(execution.running(eq(owner), eq(project), eq(run.runId()), any())).thenReturn(running);
        when(execution.identity(eq(owner), eq(project), same(running), any())).thenReturn(run);
        when(execution.source(eq(owner), eq(project), same(running), any())).thenReturn(snapshot);
    }

    @Test void fixedBuildPackagesOnlyItsOriginalSourceWithoutNeedingALiveRun() throws Exception {
        var view = sources.inspect(owner, project, buildId, session, budget);
        assertThat(view.identity()).isEqualTo(identity); assertThat(view.fileCount()).isEqualTo(1);
        var archive = sources.build(owner, project, buildId, session, budget);
        assertThat(archive.filename()).isEqualTo("source-" + buildId + ".zip");
        var entries = entries(archive);
        assertThat(entries.get("project/original.txt")).isEqualTo(tree.files().get(0).content());
        assertThat(entries).hasSize(3);
        assertThat(new ObjectMapper().readTree(entries.get("delivery/manifest.json")).path("preview").isNull()).isTrue();
        verify(execution, never()).running(any(), any(), any(), any());
    }

    @Test void previewArchivePinsOriginalRunAndRejectsDifferentSessionEvenForSameOwner() throws Exception {
        var preview = access.issue(owner, project, run.runId(), session, budget);
        var archive = sources.preview(owner, project, preview.previewId(), session, budget);
        var manifest = new ObjectMapper().readTree(entries(archive).get("delivery/manifest.json"));
        assertThat(manifest.path("preview").path("executionId").asText()).isEqualTo(run.executionId().toString());
        when(sessions.streamValid("another-session", owner)).thenReturn(true);
        code("DELIVERY_PREVIEW_NOT_FOUND", () -> sources.preview(owner, project, preview.previewId(), "another-session", budget));
        code("DELIVERY_PREVIEW_NOT_FOUND", () -> sources.preview(owner, UUID.randomUUID(), preview.previewId(), session, budget));
        verify(execution, times(1)).running(any(), any(), any(), any());
        verify(execution, never()).build(any(), any(), any(), any());
    }

    @Test void stopDuringSourceReadRejectsPreviewInsteadOfFallingBackToBuildOrLatestRun() {
        var preview = access.issue(owner, project, run.runId(), session, budget);
        when(execution.source(eq(owner), eq(project), same(running), any())).thenAnswer(call -> {
            when(execution.identity(eq(owner), eq(project), same(running), any()))
                    .thenThrow(new ApiException(409, "EXECUTION_ACCESS_REVOKED", "stopped"));
            return snapshot;
        });
        code("EXECUTION_ACCESS_REVOKED", () -> sources.preview(owner, project, preview.previewId(), session, budget));
        code("DELIVERY_PREVIEW_NOT_FOUND", () -> sources.preview(owner, project, preview.previewId(), session, budget));
        verify(execution, times(1)).running(any(), any(), any(), any());
        verify(execution, never()).build(any(), any(), any(), any());
    }

    @Test void logoutConfigurationChangeOrIdentityDriftCannotReleasePreparedZip() {
        when(execution.source(eq(owner), eq(project), same(build), any())).thenAnswer(call -> {
            when(sessions.streamValid(session, owner)).thenReturn(false); return snapshot;
        });
        code("SESSION_REQUIRED", () -> sources.build(owner, project, buildId, session, budget));
        when(sessions.streamValid(session, owner)).thenReturn(true);
        when(execution.source(eq(owner), eq(project), same(build), any())).thenAnswer(call -> {
            environment.setProperty("atoms.project-delivery.origin", "https://changed.example"); return snapshot;
        });
        code("DELIVERY_SOURCE_CHANGED", () -> sources.build(owner, project, buildId, session, budget));
        when(execution.source(eq(owner), eq(project), same(build), any())).thenReturn(snapshot);
        when(execution.identity(eq(owner), eq(project), same(build), any())).thenReturn(identity,
                new ProjectExecutionAccess.BuildIdentity(project, UUID.randomUUID(), version, tree.sha256(), 123, "b".repeat(64), "c".repeat(64)));
        code("DELIVERY_SOURCE_CHANGED", () -> sources.build(owner, project, buildId, session, budget));
    }

    @Test void disabledOrExpiredRequestsNeverReadSourceAndBudgetIsSharedAcrossReads() {
        code("DELIVERY_TIMEOUT", () -> sources.build(owner, project, buildId, session, () -> Duration.ZERO));
        verifyNoInteractions(execution);
        var expired = new java.util.concurrent.atomic.AtomicBoolean();
        SourceExecutionBudget remaining = () -> expired.get() ? Duration.ZERO : Duration.ofSeconds(30);
        when(execution.source(eq(owner), eq(project), same(build), same(remaining))).thenAnswer(call -> { expired.set(true); return snapshot; });
        code("DELIVERY_TIMEOUT", () -> sources.build(owner, project, buildId, session, remaining));
        verify(execution).build(owner, project, buildId, remaining);
        environment.setProperty("atoms.project-delivery.enabled", "false");
        code("DELIVERY_DISABLED", () -> sources.inspect(owner, project, buildId, session, budget));
    }

    private Map<String, byte[]> entries(DeliverySources.Archive archive) throws Exception {
        var result = new HashMap<String, byte[]>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(archive.bytes()))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) result.put(entry.getName(), input.readAllBytes());
        }
        return result;
    }
    private void code(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
