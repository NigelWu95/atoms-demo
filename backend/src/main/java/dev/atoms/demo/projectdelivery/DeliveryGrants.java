package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.projectexecution.ProjectExecutionAccess;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** In-memory capability registry. It stores only token hashes and never renews a grant on reads. */
@Component
final class DeliveryGrants {
    static final String PREFIX = "/api/delivery/preview/";
    private final Map<UUID, Grant> grants = new HashMap<>();
    private final Clock clock;
    private final LongSupplier nanos;
    private final SecureRandom random;
    private int exchanges;

    @Autowired DeliveryGrants() { this(Clock.systemUTC(), System::nanoTime, new SecureRandom()); }
    DeliveryGrants(Clock clock, LongSupplier nanos, SecureRandom random) {
        this.clock = clock; this.nanos = nanos; this.random = random;
    }

    synchronized Issued issue(UUID owner, UUID project, String session, DeliverySettings.Configuration configuration,
            ProjectExecutionAccess.RunningHandle handle, ProjectExecutionAccess.RunningIdentity identity) {
        Objects.requireNonNull(owner); Objects.requireNonNull(project); Objects.requireNonNull(configuration);
        Objects.requireNonNull(handle); Objects.requireNonNull(identity);
        if (session == null || session.isBlank() || session.length() > 128 || session.codePoints().anyMatch(Character::isISOControl)
                || !project.equals(identity.build().projectId())) throw new IllegalArgumentException("Invalid preview authority");
        prune();
        if (grants.size() >= DeliverySettings.GRANTS
                || grants.values().stream().filter(grant -> grant.session.equals(session)).count() >= DeliverySettings.SESSION_GRANTS) throw capacity();
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var grant = new Grant(UUID.randomUUID(), hash(token), owner, project, session, configuration, handle, identity,
                nanos.getAsLong(), clock.instant().plus(DeliverySettings.GRANT_TTL));
        grants.put(grant.id, grant);
        return new Issued(new Preview(grant.id, grant.expiresAt, PREFIX + token + "/", identity), grant);
    }

    synchronized Grant token(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw missing();
        prune(); byte[] expected = hash(token);
        for (Grant grant : grants.values()) if (MessageDigest.isEqual(expected, grant.tokenHash)) return grant;
        throw missing();
    }
    synchronized Grant owned(UUID owner, UUID project, UUID preview) {
        prune(); var grant = grants.get(preview);
        if (grant == null || !grant.owner.equals(owner) || !grant.project.equals(project)) throw missing();
        return grant;
    }
    synchronized void revoke(UUID owner, UUID project, UUID preview) {
        prune(); var grant = grants.get(preview);
        if (grant == null) return;
        if (!grant.owner.equals(owner) || !grant.project.equals(project)) throw missing();
        grants.remove(preview);
    }
    synchronized void invalidate(Grant grant) {
        if (grant != null && grants.get(grant.id) == grant) grants.remove(grant.id);
    }
    synchronized void clear() { grants.clear(); } // Outstanding exchanges retain their slots until actual completion.
    synchronized void require(Grant grant) {
        prune(); if (grant == null || grants.get(grant.id) != grant) throw missing();
    }

    synchronized Exchange exchange(Grant grant) {
        require(grant);
        if (exchanges >= DeliverySettings.EXCHANGES || grant.exchanges >= DeliverySettings.GRANT_EXCHANGES) throw capacity();
        exchanges++; grant.exchanges++;
        return new Exchange(grant);
    }
    final class Exchange implements AutoCloseable {
        private final Grant grant;
        private boolean closed;
        private Exchange(Grant grant) { this.grant = grant; }
        @Override public void close() {
            synchronized (DeliveryGrants.this) {
                if (!closed) { closed = true; exchanges--; grant.exchanges--; }
            }
        }
    }

    record Preview(UUID previewId, Instant expiresAt, String iframeUrl, ProjectExecutionAccess.RunningIdentity identity) {
        @Override public String toString() { return "DeliveryPreview[id=" + previewId + ", expiresAt=" + expiresAt + "]"; }
    }
    record Issued(Preview preview, Grant grant) {
        @Override public String toString() { return preview.toString(); }
    }
    static final class Grant {
        private final UUID id, owner, project;
        private final byte[] tokenHash;
        private final String session;
        private final DeliverySettings.Configuration configuration;
        private final ProjectExecutionAccess.RunningHandle handle;
        private final ProjectExecutionAccess.RunningIdentity identity;
        private final long created;
        private final Instant expiresAt;
        private int exchanges;
        private Grant(UUID id, byte[] tokenHash, UUID owner, UUID project, String session, DeliverySettings.Configuration configuration,
                ProjectExecutionAccess.RunningHandle handle, ProjectExecutionAccess.RunningIdentity identity, long created, Instant expiresAt) {
            this.id = id; this.tokenHash = tokenHash.clone(); this.owner = owner; this.project = project; this.session = session;
            this.configuration = configuration; this.handle = handle; this.identity = identity; this.created = created; this.expiresAt = expiresAt;
        }
        UUID id() { return id; }
        UUID owner() { return owner; }
        UUID project() { return project; }
        String session() { return session; }
        DeliverySettings.Configuration configuration() { return configuration; }
        ProjectExecutionAccess.RunningHandle handle() { return handle; }
        ProjectExecutionAccess.RunningIdentity identity() { return identity; }
    }

    private void prune() {
        long now = nanos.getAsLong();
        grants.values().removeIf(grant -> now - grant.created < 0 || now - grant.created >= DeliverySettings.GRANT_TTL.toNanos());
    }
    private static byte[] hash(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Preview digest unavailable"); }
    }
    static ApiException missing() { return new ApiException(404, "DELIVERY_PREVIEW_NOT_FOUND", "预览已失效或不可访问，请重新打开。"); }
    static ApiException capacity() { return new ApiException(429, "DELIVERY_CAPACITY", "预览授权或并发请求已达上限，请关闭已有预览或稍后重试。"); }
}
