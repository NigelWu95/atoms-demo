package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.identity.SessionAccess;
import dev.atoms.demo.projectexecution.*;
import dev.atoms.demo.source.SourceExecutionBudget;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Joins a browser's original session to an exact execution capability; never reacquires a latest run. */
@Service
final class DeliveryAccess {
    private final DeliverySettings settings;
    private final DeliveryGrants grants;
    private final SessionAccess sessions;
    private final ProjectExecutionAccess execution;

    DeliveryAccess(DeliverySettings settings, DeliveryGrants grants, SessionAccess sessions, ProjectExecutionAccess execution) {
        this.settings = settings; this.grants = grants; this.sessions = sessions; this.execution = execution;
    }

    DeliveryGrants.Preview issue(UUID owner, UUID project, UUID run, String session, SourceExecutionBudget budget) {
        var configuration = configuration(); session(session, owner); check(budget);
        var handle = execution.running(owner, project, run, budget);
        var identity = execution.identity(owner, project, handle, budget);
        session(session, owner); check(budget);
        if (!configuration.equals(configuration()) || !run.equals(identity.runId())) throw changed();
        var issued = grants.issue(owner, project, session, configuration, handle, identity);
        try { guard(issued.grant()); check(budget); return issued.preview(); }
        catch (RuntimeException failure) { grants.invalidate(issued.grant()); throw failure; }
    }

    record LocalAddress(UUID runId, UUID buildId, UUID sourceVersionId, String url) {}

    LocalAddress address(UUID owner, UUID project, UUID run, String session, SourceExecutionBudget budget) {
        var configuration = management(owner, session, budget);
        var handle = execution.running(owner, project, run, budget);
        var endpoint = execution.endpoint(owner, project, handle, budget);
        session(session, owner); check(budget);
        var identity = endpoint.identity();
        if (!configuration.equals(configuration()) || !run.equals(identity.runId())
                || !project.equals(identity.build().projectId())) throw changed();
        return new LocalAddress(run, identity.build().buildId(), identity.build().sourceVersionId(),
                DeliverySettings.localAddress(configuration, endpoint.port()));
    }

    void revoke(UUID owner, UUID project, UUID preview, String session, SourceExecutionBudget budget) {
        configuration(); session(session, owner); check(budget);
        execution.authorizeProject(owner, project, budget);
        grants.revoke(owner, project, preview);
    }

    DeliveryGrants.Grant resolve(String token) {
        configuration(); var grant = grants.token(token); guard(grant); return grant;
    }
    DeliveryGrants.Exchange acquire(DeliveryGrants.Grant grant) {
        guard(grant); return grants.exchange(grant);
    }

    DeliverySettings.Configuration management(UUID owner, String session, SourceExecutionBudget budget) {
        var configuration = configuration(); session(session, owner); check(budget); return configuration;
    }

    DeliveryGrants.Grant owned(UUID owner, UUID project, UUID preview, String session, SourceExecutionBudget budget) {
        management(owner, session, budget);
        var grant = grants.owned(owner, project, preview);
        if (!grant.session().equals(session)) throw DeliveryGrants.missing();
        guard(grant); return grant;
    }

    /** OPTIONS/download consumers still verify the original process even when they send no application request. */
    void validate(DeliveryGrants.Grant grant, SourceExecutionBudget budget) {
        try {
            guard(grant); check(budget);
            if (!grant.identity().equals(execution.identity(grant.owner(), grant.project(), grant.handle(), budget))) throw changed();
            guard(grant); check(budget);
        } catch (RuntimeException failure) { grants.invalidate(grant); throw failure; }
    }

    ExecutionHttpResponse exchange(DeliveryGrants.Grant grant, ExecutionHttpRequest request, SourceExecutionBudget budget) {
        guard(grant); check(budget);
        final ExecutionHttpResponse response;
        try { response = execution.exchange(grant.owner(), grant.project(), grant.handle(), request, budget); }
        catch (RuntimeException failure) { grants.invalidate(grant); throw failure; }
        try { guard(grant); check(budget); }
        catch (RuntimeException failure) {
            grants.invalidate(grant);
            if (request.write()) throw new ApiException(502, "DELIVERY_WRITE_UNCERTAIN", "无法确认应用写入结果，请先查询确认，勿自动重试。");
            throw failure;
        }
        return response;
    }

    private void guard(DeliveryGrants.Grant grant) {
        try {
            var configuration = configuration(); grants.require(grant);
            if (!configuration.equals(grant.configuration())) throw changed();
            session(grant.session(), grant.owner()); grants.require(grant);
        } catch (RuntimeException failure) { grants.invalidate(grant); throw failure; }
    }
    private DeliverySettings.Configuration configuration() {
        try { return settings.load(); }
        catch (RuntimeException failure) { grants.clear(); throw failure; }
    }
    private void session(String session, UUID owner) {
        if (session == null || session.isBlank() || owner == null) throw sessionRequired();
        final boolean valid;
        try { valid = sessions.streamValid(session, owner); }
        catch (RuntimeException unavailable) { throw new ApiException(503, "DELIVERY_AUTH_UNAVAILABLE", "会话状态暂不可用，请稍后重新打开预览。"); }
        if (!valid) throw sessionRequired();
    }
    private static ApiException sessionRequired() { return new ApiException(401, "SESSION_REQUIRED", "原登录会话已失效，请返回工作台重新打开预览。"); }
    private static ApiException changed() { return new ApiException(409, "DELIVERY_PREVIEW_CHANGED", "预览配置或运行身份已变化，请重新打开预览。"); }

    static SourceExecutionBudget deadline() {
        long started = System.nanoTime();
        return () -> {
            long elapsed = System.nanoTime() - started, limit = DeliverySettings.REQUEST_TIMEOUT.toNanos();
            if (Thread.currentThread().isInterrupted() || elapsed < 0 || elapsed >= limit) throw new IOException("EXECUTION_TIMEOUT");
            return Duration.ofNanos(limit - elapsed);
        };
    }
    private static void check(SourceExecutionBudget budget) {
        try {
            Duration remaining = budget.remaining();
            if (Thread.currentThread().isInterrupted() || remaining == null || remaining.isNegative() || remaining.isZero()) throw new IOException();
        } catch (IOException | RuntimeException expired) { throw new ApiException(504, "DELIVERY_TIMEOUT", "预览请求已超时，请重新打开或查询状态。"); }
    }
}
