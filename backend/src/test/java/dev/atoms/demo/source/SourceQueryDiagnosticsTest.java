package dev.atoms.demo.source;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceQueryDiagnosticsTest {
    @Test void failedReadLogsOnlyCorrelatedFixedCategoriesAndPreservesFailClosedBehavior() throws Exception {
        var owner = UUID.randomUUID(); var project = UUID.randomUUID(); var version = UUID.randomUUID();
        var service = mock(ProjectSourceService.class); var repository = mock(SourceRepository.class);
        var environment = mock(ProjectSourceEnvironment.class);
        when(repository.version(project, version)).thenReturn(new SourceVersion(version, project, 1,
                "a".repeat(40), "b".repeat(64), 1, 1, null, null, null, null, null, Instant.now()));
        when(environment.existingSettings()).thenThrow(new ApiException(503, "PRIVATE_CODE_CANARY",
                "/private/source\nPRIVATE_MESSAGE_CANARY"));
        var logger = (Logger) LoggerFactory.getLogger(SourceQuery.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            assertThatThrownBy(() -> new SourceQuery(service, repository, environment).snapshot(owner, project, version,
                    new SourceDeadline(Duration.ofSeconds(5))))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("SOURCE_STORAGE_UNAVAILABLE"));
            verify(service).storageUnavailable(owner, project);
            assertThat(logs.list).hasSize(1);
            var event = logs.list.get(0);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain("CANARY", "/private", owner.toString());
            var json = new ObjectMapper().readTree(event.getFormattedMessage());
            assertThat(json.size()).isEqualTo(11);
            assertThat(json.path("event").asText()).isEqualTo("source_snapshot_failed");
            assertThat(json.path("entryPoint").asText()).isEqualTo("source_snapshot");
            assertThat(UUID.fromString(json.path("requestId").asText())).isNotNull();
            assertThat(json.path("projectId").asText()).isEqualTo(project.toString());
            assertThat(json.path("sourceVersionId").asText()).isEqualTo(version.toString());
            assertThat(json.path("stage").asText()).isEqualTo("ENVIRONMENT");
            assertThat(json.path("failureKind").asText()).isEqualTo("ENVIRONMENT_UNAVAILABLE");
            assertThat(json.path("gitStopStage").asText()).isEqualTo("NONE");
            assertThat(json.path("gitStopKind").asText()).isEqualTo("NONE");
            assertThat(json.path("gitStopInterrupted").isBoolean()).isTrue();
            assertThat(json.path("gitStopInterrupted").asBoolean()).isFalse();
            assertThat(json.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void failedGitCleanupPropagatesOnlyBoundedDetailsIntoTheSingleReadEvent() throws Exception {
        var process = mock(Process.class);
        when(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        var failure = catchThrowableOfType(() -> SourceGitProcess.stop(process, owned -> {
            throw new UnsupportedOperationException("/private/source\nSECRET_CANARY");
        }), SourceGitProcess.GitFailure.class);
        var owner = UUID.randomUUID(); var project = UUID.randomUUID(); var version = UUID.randomUUID();
        var service = mock(ProjectSourceService.class); var repository = mock(SourceRepository.class);
        var environment = mock(ProjectSourceEnvironment.class);
        when(repository.version(project, version)).thenReturn(new SourceVersion(version, project, 1,
                "a".repeat(40), "b".repeat(64), 1, 1, null, null, null, null, null, Instant.now()));
        when(environment.existingSettings()).thenAnswer(invocation -> { throw failure; });
        var logger = (Logger) LoggerFactory.getLogger(SourceQuery.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            assertThatThrownBy(() -> new SourceQuery(service, repository, environment).snapshot(owner, project, version,
                    new SourceDeadline(Duration.ofSeconds(5))))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("SOURCE_STORAGE_UNAVAILABLE"));
            verify(service).storageUnavailable(owner, project);
            assertThat(logs.list).hasSize(1);
            var event = logs.list.get(0);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain("CANARY", "/private", owner.toString());
            var json = new ObjectMapper().readTree(event.getFormattedMessage());
            assertThat(json.size()).isEqualTo(11);
            assertThat(json.path("failureKind").asText()).isEqualTo("GIT_PROCESS_UNSETTLED");
            assertThat(json.path("gitStopStage").asText()).isEqualTo("ENUMERATE");
            assertThat(json.path("gitStopKind").asText()).isEqualTo("UNSUPPORTED");
            assertThat(json.path("gitStopInterrupted").isBoolean()).isTrue();
            assertThat(json.path("gitStopInterrupted").asBoolean()).isFalse();
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void errorKindsNeverCopyAnUntrustedMessageEvenWhenItLooksLikeAGitCode() {
        for (var item : Map.of(
                new IOException("GIT_TIMEOUT /private/PASSWORD_CANARY"), "IO_FAILURE",
                new SourceGitProcess.GitFailure("GIT_IO_FAILED"), "GIT_IO_FAILED",
                new SourceGitProcess.GitFailure("GIT_UNKNOWN_SECRET"), "GIT_FAILURE",
                new java.nio.file.NoSuchFileException("/private/CANARY"), "FILE_MISSING",
                new java.nio.file.AccessDeniedException("/private/CANARY"), "ACCESS_DENIED",
                new java.nio.channels.ClosedByInterruptException(), "INTERRUPTED_CHANNEL",
                new IllegalArgumentException("SOURCE_SECRET_CANARY"), "INVALID_STORAGE").entrySet())
            assertThat(SourceQuery.failureKind(item.getKey())).isEqualTo(item.getValue());
    }
}
