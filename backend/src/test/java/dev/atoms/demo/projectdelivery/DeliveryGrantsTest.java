package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.projectexecution.ProjectExecutionAccess;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DeliveryGrantsTest {
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
    private final AtomicLong nanos = new AtomicLong();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC);
    private final DeliverySettings.Configuration configuration = new DeliverySettings.Configuration("http://127.0.0.1:5174");
    private final ProjectExecutionAccess.RunningHandle handle = mock(ProjectExecutionAccess.RunningHandle.class);
    private final DeliveryGrants grants = new DeliveryGrants(clock, nanos::get, new SecureRandom());

    @Test void unpredictableUrlResolvesOnlyInItsIssuerAndContainsNoProjectOrSessionInformation() {
        var issued = issue("session-a"); String token = token(issued);
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
        assertThat(grants.token(token)).isSameAs(issued.grant());
        assertThat(grants.owned(owner, project, issued.preview().previewId())).isSameAs(issued.grant());
        assertThat(issued.preview().expiresAt()).isEqualTo(clock.instant().plusSeconds(600));
        assertThat(issued.preview().iframeUrl()).doesNotContain("session-a", owner.toString(), project.toString());
        assertThat(issued.toString().contains(token)).isFalse();
        assertThat(issued.preview().toString().contains(token)).isFalse();
        rejected(() -> new DeliveryGrants(clock, nanos::get, new SecureRandom()).token(token), 404);
        for (String invalid : List.of("", "../x", "a".repeat(42), token + "/", "#".repeat(43)))
            rejected(() -> grants.token(invalid), 404);
        rejected(() -> grants.owned(UUID.randomUUID(), project, issued.preview().previewId()), 404);
        rejected(() -> grants.revoke(owner, UUID.randomUUID(), issued.preview().previewId()), 404);
        assertThat(grants.token(token)).isSameAs(issued.grant());
    }

    @Test void readsDoNotRenewMonotonicExpiryAndRevocationIsIdempotent() {
        var issued = issue("session-a");
        nanos.set(Duration.ofMinutes(9).toNanos()); grants.token(token(issued));
        nanos.set(Duration.ofMinutes(10).toNanos()); rejected(() -> grants.token(token(issued)), 404);
        var next = issue("session-a");
        grants.revoke(owner, project, next.preview().previewId());
        grants.revoke(owner, project, next.preview().previewId());
        rejected(() -> grants.token(token(next)), 404);
    }

    @Test void sessionAndGlobalLimitsRejectWithoutEvictingActiveGrants() {
        var active = new ArrayList<DeliveryGrants.Issued>();
        for (int session = 0; session < 4; session++) {
            for (int i = 0; i < 8; i++) active.add(issue("session-" + session));
            int current = session;
            rejected(() -> issue("session-" + current), 429);
        }
        rejected(() -> issue("new-session"), 429);
        active.forEach(issued -> assertThat(grants.token(token(issued))).isSameAs(issued.grant()));
        grants.revoke(owner, project, active.get(0).preview().previewId());
        assertThat(issue("new-session").preview().previewId()).isNotNull();
    }

    @Test void inFlightLimitsHaveNoQueueAndRevocationCannotFreeRunningWorkPrematurely() {
        var active = new ArrayList<DeliveryGrants.Issued>();
        var leases = new ArrayList<DeliveryGrants.Exchange>();
        for (int i = 0; i < 5; i++) active.add(issue("session"));
        try {
            for (int i = 0; i < 4; i++) {
                var grant = active.get(i).grant();
                for (int j = 0; j < 4; j++) leases.add(grants.exchange(grant));
                rejected(() -> grants.exchange(grant), 429);
            }
            rejected(() -> grants.exchange(active.get(4).grant()), 429);
            grants.revoke(owner, project, active.get(0).preview().previewId());
            rejected(() -> grants.exchange(active.get(4).grant()), 429);
            leases.get(0).close(); leases.get(0).close();
            try (var ignored = grants.exchange(active.get(4).grant())) {
                rejected(() -> grants.exchange(active.get(4).grant()), 429);
            }
        } finally { leases.forEach(DeliveryGrants.Exchange::close); }
        try (var ignored = grants.exchange(active.get(4).grant())) { assertThat(ignored).isNotNull(); }
        grants.clear(); rejected(() -> grants.require(active.get(4).grant()), 404);
    }

    private DeliveryGrants.Issued issue(String session) {
        var build = new ProjectExecutionAccess.BuildIdentity(project, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), 1, "b".repeat(64), "c".repeat(64));
        var identity = new ProjectExecutionAccess.RunningIdentity(UUID.randomUUID(), UUID.randomUUID(), build);
        return grants.issue(owner, project, session, configuration, handle, identity);
    }
    private String token(DeliveryGrants.Issued issued) {
        return issued.preview().iframeUrl().substring(DeliveryGrants.PREFIX.length()).replace("/", "");
    }
    private void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(status)).hasNoCause();
    }
}
