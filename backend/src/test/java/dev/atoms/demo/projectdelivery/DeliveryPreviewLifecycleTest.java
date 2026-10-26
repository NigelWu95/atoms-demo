package dev.atoms.demo.projectdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.projectexecution.ProjectExecutionAccess;
import jakarta.servlet.AsyncEvent;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryPreviewLifecycleTest {
    @Test void timedOutClientDoesNotReleaseAnExchangeWhoseWorkerHasNotActuallyFinished() throws Exception {
        var grants = new DeliveryGrants();
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
        var identity = new ProjectExecutionAccess.RunningIdentity(UUID.randomUUID(), UUID.randomUUID(),
                new ProjectExecutionAccess.BuildIdentity(project, UUID.randomUUID(), UUID.randomUUID(),
                        "a".repeat(64), 1, "b".repeat(64), "c".repeat(64)));
        var grant = grants.issue(owner, project, "test-session", new DeliverySettings.Configuration("http://127.0.0.1:5174"),
                mock(ProjectExecutionAccess.RunningHandle.class), identity).grant();
        var lease = spy(grants.exchange(grant));
        var access = mock(DeliveryAccess.class);
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { interrupted.countDown(); }
            return null;
        }).when(access).validate(same(grant), any());
        var service = new DeliveryPreview(access, new ObjectMapper());
        var request = new MockHttpServletRequest("OPTIONS", "/"); request.setAsyncSupported(true);
        var response = new MockHttpServletResponse();
        var held = new ArrayList<DeliveryGrants.Exchange>();
        try {
            service.open(request, response, grant, new DeliveryHttpPolicy.Input("GET", "/", null, Map.of(), true), lease,
                    () -> Duration.ofSeconds(30));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 3; i++) held.add(grants.exchange(grant));
            var async = (MockAsyncContext) request.getAsyncContext();
            for (var listener : async.getListeners()) listener.onTimeout(new AsyncEvent(async));
            assertThat(interrupted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(response.getStatus()).isEqualTo(504);
            assertThat(response.getContentAsString()).contains("DELIVERY_TIMEOUT");
            verify(lease, never()).close();
            assertThatThrownBy(() -> grants.exchange(grant)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.status()).isEqualTo(429));
            release.countDown();
            verify(lease, timeout(3000).times(1)).close();
            try (var available = grants.exchange(grant)) { assertThat(available).isNotNull(); }
            assertThat(response.getStatus()).isEqualTo(504); // The late worker cannot replace the timeout with success.
        } finally { release.countDown(); held.forEach(DeliveryGrants.Exchange::close); service.close(); }
    }
}
