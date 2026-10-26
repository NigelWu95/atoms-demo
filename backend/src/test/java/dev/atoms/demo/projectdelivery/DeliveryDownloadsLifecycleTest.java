package dev.atoms.demo.projectdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryDownloadsLifecycleTest {
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), build = UUID.randomUUID();

    @Test void timeoutRetainsCapacityUntilUncooperativeGeneratorActuallyFinishes() throws Exception {
        var sources = mock(DeliverySources.class); var service = new DeliveryDownloads(sources, new ObjectMapper());
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        when(sources.build(any(), any(), any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { interrupted.countDown(); }
            return new DeliverySources.Archive("source.zip", new byte[]{1});
        });
        var one = request(); var response = new MockHttpServletResponse(); var two = request();
        try {
            service.open(owner, project, build, "one", false, one, response);
            service.open(owner, project, build, "two", false, two, new MockHttpServletResponse());
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            capacity(() -> service.inspect(owner, project, build, "third"));
            timeout(one); timeout(two);
            assertThat(interrupted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(response.getStatus()).isEqualTo(504); assertThat(response.getContentAsString()).contains("DELIVERY_TIMEOUT");
            capacity(() -> service.inspect(owner, project, build, "one"));
            release.countDown(); awaitAvailable(service, "one"); awaitAvailable(service, "two");
            assertThat(response.getStatus()).isEqualTo(504);
            verify(sources, times(2)).build(any(), any(), any(), any(), any());
        } finally { release.countDown(); service.close(); }
    }

    @Test void slowUncommittedWriterHoldsSessionSlotAndTimeoutCannotLeaveSuccessfulZipHeaders() throws Exception {
        var sources = mock(DeliverySources.class); var service = new DeliveryDownloads(sources, new ObjectMapper());
        when(sources.build(any(), any(), any(), any(), any())).thenReturn(new DeliverySources.Archive("source.zip", new byte[32]));
        var registered = new CountDownLatch(1);
        var output = new ServletOutputStream() {
            @Override public boolean isReady() { return false; }
            @Override public void setWriteListener(WriteListener listener) { registered.countDown(); }
            @Override public void write(int value) { throw new AssertionError("Slow socket must not be written"); }
        };
        var response = mock(HttpServletResponse.class); when(response.getOutputStream()).thenReturn(output);
        var request = request();
        try {
            service.open(owner, project, build, "one", false, request, response);
            assertThat(registered.await(3, TimeUnit.SECONDS)).isTrue();
            capacity(() -> service.inspect(owner, project, build, "one"));
            assertThatCode(() -> service.inspect(owner, project, build, "two")).doesNotThrowAnyException();
            timeout(request); awaitAvailable(service, "one");
            verify(response).setStatus(504); verify(response).setContentLength(0);
            verify(response).setHeader("Content-Disposition", "inline");
        } finally { service.close(); }
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/source.zip"); request.setAsyncSupported(true); return request;
    }
    private void timeout(MockHttpServletRequest request) throws IOException {
        var async = (MockAsyncContext) request.getAsyncContext();
        for (var listener : async.getListeners()) listener.onTimeout(new AsyncEvent(async));
    }
    private void awaitAvailable(DeliveryDownloads service, String session) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        for (;;) try { service.inspect(owner, project, build, session); return; }
        catch (ApiException failure) {
            if (failure.status() != 429 || System.nanoTime() >= until) throw failure;
            Thread.sleep(10);
        }
    }
    private void capacity(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(429));
    }
}
