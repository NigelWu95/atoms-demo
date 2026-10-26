package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.identity.SessionAccess;
import dev.atoms.demo.projectexecution.*;
import dev.atoms.demo.source.SourceExecutionBudget;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryAccessTest {
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), run = UUID.randomUUID();
    private final String session = "original-session";
    private final MockEnvironment environment = new MockEnvironment().withProperty("atoms.project-delivery.enabled", "true")
            .withProperty("atoms.project-delivery.origin", "http://127.0.0.1:5174");
    private final SessionAccess sessions = mock(SessionAccess.class);
    private final ProjectExecutionAccess execution = mock(ProjectExecutionAccess.class);
    private final ProjectExecutionAccess.RunningHandle handle = mock(ProjectExecutionAccess.RunningHandle.class);
    private final ProjectExecutionAccess.RunningIdentity identity = new ProjectExecutionAccess.RunningIdentity(run, UUID.randomUUID(),
            new ProjectExecutionAccess.BuildIdentity(project, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), 1, "b".repeat(64), "c".repeat(64)));
    private final DeliveryGrants grants = new DeliveryGrants();
    private final DeliveryAccess access = new DeliveryAccess(new DeliverySettings(environment), grants, sessions, execution);

    @BeforeEach void authorizedRun() {
        when(sessions.streamValid(session, owner)).thenReturn(true);
        when(execution.running(eq(owner), eq(project), eq(run), any())).thenReturn(handle);
        when(execution.identity(eq(owner), eq(project), same(handle), any())).thenReturn(identity);
    }

    @Test void disabledOrExpiredSessionCannotAskExecutionForARunningHandle() {
        when(sessions.streamValid(session, owner)).thenReturn(false);
        code("SESSION_REQUIRED", () -> access.issue(owner, project, run, session, budget()));
        verifyNoInteractions(execution);
        environment.setProperty("atoms.project-delivery.enabled", "false");
        code("DELIVERY_DISABLED", () -> access.issue(owner, project, run, session, budget()));
        verifyNoInteractions(execution);
    }

    @Test void issuedGrantPinsOriginalSessionAndHandleAndLogoutPermanentlyRevokesIt() {
        var preview = access.issue(owner, project, run, session, budget());
        var grant = access.resolve(token(preview)); access.validate(grant, budget());
        assertThat(grant.handle()).isSameAs(handle); assertThat(grant.identity()).isEqualTo(identity);
        when(sessions.streamValid(session, owner)).thenReturn(false);
        code("SESSION_REQUIRED", () -> access.resolve(token(preview)));
        when(sessions.streamValid(session, owner)).thenReturn(true);
        code("DELIVERY_PREVIEW_NOT_FOUND", () -> access.resolve(token(preview)));
        verify(execution, times(1)).running(eq(owner), eq(project), eq(run), any());
    }

    @Test void changedRunAndOriginAreRejectedWithoutReacquiringLatestHandle() {
        var preview = access.issue(owner, project, run, session, budget()); var grant = access.resolve(token(preview));
        when(execution.identity(eq(owner), eq(project), same(handle), any()))
                .thenThrow(new ApiException(409, "EXECUTION_ACCESS_REVOKED", "fixed"));
        code("EXECUTION_ACCESS_REVOKED", () -> access.validate(grant, budget()));
        code("DELIVERY_PREVIEW_NOT_FOUND", () -> access.resolve(token(preview)));
        when(execution.identity(eq(owner), eq(project), same(handle), any())).thenReturn(identity);
        var next = access.issue(owner, project, run, session, budget());
        environment.setProperty("atoms.project-delivery.origin", "https://example.com");
        code("DELIVERY_PREVIEW_CHANGED", () -> access.resolve(token(next)));
        environment.setProperty("atoms.project-delivery.origin", "http://127.0.0.1:5174");
        code("DELIVERY_PREVIEW_NOT_FOUND", () -> access.resolve(token(next)));
        verify(execution, times(2)).running(eq(owner), eq(project), eq(run), any());
    }

    @Test void sessionRevocationAfterWriteReturnsUncertaintyWithoutRetryingTheApplication() {
        var preview = access.issue(owner, project, run, session, budget()); var grant = access.resolve(token(preview));
        var request = new ExecutionHttpRequest("POST", "/api/todos", null, Map.of("Content-Type", "application/json"), new byte[]{'{', '}'});
        when(execution.exchange(eq(owner), eq(project), same(handle), same(request), any())).thenAnswer(call -> {
            when(sessions.streamValid(session, owner)).thenReturn(false);
            return mock(ExecutionHttpResponse.class);
        });
        code("DELIVERY_WRITE_UNCERTAIN", () -> access.exchange(grant, request, budget()));
        verify(execution, times(1)).exchange(eq(owner), eq(project), same(handle), same(request), any());
        code("DELIVERY_PREVIEW_NOT_FOUND", () -> grants.token(token(preview)));
    }

    @Test void explicitRevocationUsesProjectAuthorizationEvenAfterGrantExpiryAndDisableClearsUrls() {
        var preview = access.issue(owner, project, run, session, budget());
        access.revoke(owner, project, preview.previewId(), session, budget());
        access.revoke(owner, project, preview.previewId(), session, budget());
        verify(execution, times(2)).authorizeProject(eq(owner), eq(project), any());
        code("DELIVERY_PREVIEW_NOT_FOUND", () -> access.resolve(token(preview)));
        var next = access.issue(owner, project, run, session, budget());
        environment.setProperty("atoms.project-delivery.enabled", "false");
        code("DELIVERY_DISABLED", () -> access.resolve(token(next)));
        environment.setProperty("atoms.project-delivery.enabled", "true");
        code("DELIVERY_PREVIEW_NOT_FOUND", () -> access.resolve(token(next)));
    }

    private SourceExecutionBudget budget() { return () -> Duration.ofSeconds(30); }
    private String token(DeliveryGrants.Preview preview) { return preview.iframeUrl().substring(DeliveryGrants.PREFIX.length()).replace("/", ""); }
    private void code(String expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(expected)).hasNoCause();
    }
}
