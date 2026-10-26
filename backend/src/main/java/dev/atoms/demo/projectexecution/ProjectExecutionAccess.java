package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.ProjectSourceReadGateway;
import dev.atoms.demo.source.SourceExecutionBudget;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal delivery boundary. Handles are in-memory references, never HTTP DTOs or persistent access grants. */
@Service
public final class ProjectExecutionAccess {
    private final ExecutionSettings settings;
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final ProjectSourceReadGateway source;
    private final TransactionTemplate transaction;
    private final Supplier<ExecutionCoordinator> coordinator;
    private final ExecutionBuildRepository builds;
    private final ExecutionRunRepository runs;

    @Autowired
    ProjectExecutionAccess(ExecutionSettings settings, JdbcTemplate jdbc, ProjectService projects,
            ProjectSourceReadGateway source, PlatformTransactionManager transactions, ObjectProvider<ExecutionCoordinator> coordinator) {
        this(settings, jdbc, projects, source, transactions, (Supplier<ExecutionCoordinator>) coordinator::getIfAvailable);
    }
    ProjectExecutionAccess(ExecutionSettings settings, JdbcTemplate jdbc, ProjectService projects,
            ProjectSourceReadGateway source, PlatformTransactionManager transactions, Supplier<ExecutionCoordinator> coordinator) {
        this.settings = settings; this.jdbc = jdbc; this.projects = projects; this.source = source; this.coordinator = coordinator;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
        builds = new ExecutionBuildRepository(jdbc); runs = new ExecutionRunRepository(jdbc);
    }

    /** Issue from an authorized successful build, without opening a new execution root or reading latest source. */
    public BuildHandle build(UUID owner, UUID project, UUID build) {
        return build(owner, project, build, budget()::remaining);
    }
    public BuildHandle build(UUID owner, UUID project, UUID build, SourceExecutionBudget remaining) {
        return guarded(() -> {
            var deadline = budget(remaining); deadline.remaining();
            requireIds(project, build); var config = authorize(owner); var scope = scope();
            var current = state(owner, project, build, null, deadline);
            var handle = open(config, scope, current, deadline);
            checked(owner, project, handle, null, deadline); return handle;
        });
    }
    /** A READY database row is insufficient: the original worker must still own this exact serving process. */
    public RunningHandle running(UUID owner, UUID project, UUID run) {
        return running(owner, project, run, budget()::remaining);
    }
    public RunningHandle running(UUID owner, UUID project, UUID run, SourceExecutionBudget remaining) {
        return guarded(() -> {
            var deadline = budget(remaining); deadline.remaining();
            requireIds(project, run); var config = authorize(owner); var scope = scope();
            var current = state(owner, project, null, run, deadline);
            var build = open(config, scope, current, deadline);
            var handle = new RunningHandle(build, current.run(), scope.ownedProcess(run));
            checked(owner, project, build, handle, deadline); return handle;
        });
    }
    public BuildIdentity identity(UUID owner, UUID project, BuildHandle handle) {
        return identity(owner, project, handle, budget()::remaining);
    }
    public BuildIdentity identity(UUID owner, UUID project, BuildHandle handle, SourceExecutionBudget remaining) {
        return guarded(() -> { checked(owner, project, handle, null, budget(remaining)); return view(handle.state); });
    }
    public RunningIdentity identity(UUID owner, UUID project, RunningHandle handle) {
        return identity(owner, project, handle, budget()::remaining);
    }
    public RunningIdentity identity(UUID owner, UUID project, RunningHandle handle, SourceExecutionBudget remaining) {
        return guarded(() -> {
            requireHandle(handle); checked(owner, project, handle.build, handle, budget(remaining));
            return new RunningIdentity(handle.run.id(), handle.run.executionId(), view(handle.build.state));
        });
    }
    /** Immutable Git snapshot for packaging; never reads the mutable build cwd. Reauthorize subsequent actions. */
    public ProjectSourceReadGateway.Snapshot source(UUID owner, UUID project, BuildHandle handle) {
        return source(owner, project, handle, budget()::remaining);
    }
    public ProjectSourceReadGateway.Snapshot source(UUID owner, UUID project, BuildHandle handle, SourceExecutionBudget remaining) {
        return guarded(() -> checked(owner, project, handle, null, budget(remaining)));
    }
    public ProjectSourceReadGateway.Snapshot source(UUID owner, UUID project, RunningHandle handle) {
        return source(owner, project, handle, budget()::remaining);
    }
    public ProjectSourceReadGateway.Snapshot source(UUID owner, UUID project, RunningHandle handle, SourceExecutionBudget remaining) {
        return guarded(() -> { requireHandle(handle); return checked(owner, project, handle.build, handle, budget(remaining)); });
    }

    /** Fixed owned-process exchange. Caller supplies a shared time budget, never a port, target URL or process ID. */
    public ExecutionHttpResponse exchange(UUID owner, UUID project, RunningHandle handle, ExecutionHttpRequest request,
            SourceExecutionBudget remaining) {
        return guarded(() -> {
            requireHandle(handle);
            if (request == null) throw error(422, "EXECUTION_INVALID_INPUT");
            var deadline = budget(remaining);
            checked(owner, project, handle.build, handle, deadline);
            var target = handle.process.confirmedTarget(handle.run.id(), handle.run.executionId());
            if (!target.project().equals(project) || !target.id().equals(handle.run.bindingId())
                    || !target.hash().equals(handle.run.bindingIdentitySha256())) throw revoked();
            handle.build.scope.check(); serving(handle.build, handle); deadline.remaining();
            var response = new ExecutionHttpTransport().exchange(target.port(), request, deadline);
            try { checked(owner, project, handle.build, handle, deadline); }
            catch (IOException | RuntimeException failure) {
                if (request.write()) throw ExecutionHttpTransport.uncertain();
                throw failure;
            }
            return response;
        });
    }

    public record BuildIdentity(UUID projectId, UUID buildId, UUID sourceVersionId, String treeSha256,
            long jarBytes, String jarSha256, String manifestSha256) {}
    public record RunningIdentity(UUID runId, UUID executionId, BuildIdentity build) {}
    public static final class BuildHandle {
        private final ProjectExecutionAccess issuer;
        private final ExecutionSettings.Configuration configuration;
        private final ExecutionCoordinator.AccessScope scope;
        private final State state;
        private final ExecutionArtifact artifact;
        private BuildHandle(ProjectExecutionAccess issuer, ExecutionSettings.Configuration configuration,
                ExecutionCoordinator.AccessScope scope, State state, ExecutionArtifact artifact) {
            this.issuer = issuer; this.configuration = configuration; this.scope = scope; this.state = state; this.artifact = artifact;
        }
    }
    public static final class RunningHandle {
        private final BuildHandle build;
        private final ExecutionRun run;
        private final ExecutionRunProcess process;
        private RunningHandle(BuildHandle build, ExecutionRun run, ExecutionRunProcess process) {
            this.build = build; this.run = run; this.process = process;
        }
    }
    private record State(ExecutionBuild build, ExecutionArtifact.Summary artifact, ExecutionRun run) {}

    private BuildHandle open(ExecutionSettings.Configuration config, ExecutionCoordinator.AccessScope scope, State current,
            OwnedProcess.Budget deadline) throws IOException {
        var artifact = scope.artifact(current.build(), current.artifact().jarSha256(), current.artifact().manifestSha256(), deadline);
        if (artifact == null || !current.artifact().equals(artifact.summary())) throw revoked();
        return new BuildHandle(this, config, scope, new State(current.build(), current.artifact(), null), artifact);
    }
    private ProjectSourceReadGateway.Snapshot checked(UUID owner, UUID project, BuildHandle handle,
            RunningHandle running, OwnedProcess.Budget deadline) throws IOException {
        deadline.remaining();
        if (handle == null || handle.issuer != this || !handle.state.build().ownerId().equals(owner)
                || !handle.state.build().projectId().equals(project)) throw error(403, "EXECUTION_ACCESS_FORBIDDEN");
        handle.scope.check(); compare(owner, project, handle, running, deadline); serving(handle, running);
        handle.artifact.verify(deadline);
        var build = handle.state.build();
        var snapshot = source.read(owner, project, build.sourceVersionId(), deadline::remaining);
        if (!build.sourceVersionId().equals(snapshot.version().id()) || !project.equals(snapshot.version().projectId())
                || !build.treeSha256().equals(snapshot.version().treeSha256()) || !build.treeSha256().equals(snapshot.tree().sha256())) throw revoked();
        // Source/Git I/O held no DB lock. Recheck both files and authority before returning any data.
        handle.artifact.verify(deadline); handle.scope.check();
        compare(owner, project, handle, running, deadline); serving(handle, running); handle.scope.check(); deadline.remaining();
        return snapshot;
    }
    private void compare(UUID owner, UUID project, BuildHandle handle, RunningHandle running, OwnedProcess.Budget deadline) throws IOException {
        if (!handle.configuration.equals(authorize(owner))) throw revoked();
        var current = state(owner, project, handle.state.build().id(), running == null ? null : running.run.id(), deadline);
        if (!current.build().equals(handle.state.build()) || !current.artifact().equals(handle.state.artifact())
                || running != null && !current.run().equals(running.run)
                || !handle.configuration.equals(authorize(owner))) throw revoked();
    }
    private void serving(BuildHandle handle, RunningHandle running) throws IOException {
        if (running != null && (running.process == null || handle.scope.ownedProcess(running.run.id()) != running.process
                || !running.process.serves(running.run.id(), running.run.executionId()))) throw revoked();
    }
    private State state(UUID owner, UUID project, UUID buildId, UUID runId, OwnedProcess.Budget deadline) throws IOException {
        long millis = Math.max(1, Math.min(5000, deadline.remaining().toMillis()));
        var bounded = new TransactionTemplate(Objects.requireNonNull(transaction.getTransactionManager()));
        bounded.setTimeout((int) ((millis + 999) / 1000));
        var result = bounded.execute(tx -> {
            // This transaction's row-lock wait consumes the same request budget as Git and HTTP.
            jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class, millis + "ms");
            if (projects.lock(owner, project.toString()).archived()) throw error(409, "EXECUTION_PROJECT_ARCHIVED");
            authorize(owner); // Configuration may have changed while waiting for the row lock.
            ExecutionRun run = runId == null ? null : runs.get(project, runId);
            if (run != null && (!owner.equals(run.ownerId()) || !"READY".equals(run.status()) || run.stopRequested()
                    || !run.executionPending() || run.executionId() == null || run.readyAt() == null
                    || run.finishedAt() != null || run.stoppedSha256() != null)) throw revoked();
            var build = builds.get(project, buildId == null ? run.buildId() : buildId);
            if (!owner.equals(build.ownerId())) throw error(409, "EXECUTION_BUILD_OWNER_CHANGED");
            if (!"SUCCEEDED".equals(build.status()) || build.finishedAt() == null) throw revoked();
            var artifact = jdbc.queryForObject("SELECT jar_bytes,jar_sha256,manifest_sha256 FROM execution_builds WHERE id=? AND project_id=?",
                    (rs, row) -> new ExecutionArtifact.Summary(rs.getLong(1), rs.getString(2), rs.getString(3)), build.id(), project);
            if (artifact == null || artifact.jarBytes() < 1 || artifact.jarBytes() > ExecutionArtifact.MAX_JAR_BYTES) throw revoked();
            if (run != null && (!build.id().equals(run.buildId()) || !build.sourceVersionId().equals(run.sourceVersionId())
                    || !build.treeSha256().equals(run.treeSha256()) || !artifact.jarSha256().equals(run.jarSha256())
                    || !artifact.manifestSha256().equals(run.manifestSha256()))) throw revoked();
            return new State(build, artifact, run);
        });
        deadline.remaining(); return result;
    }
    private ExecutionSettings.Configuration authorize(UUID owner) {
        var config = settings.load();
        if (owner == null || !config.maintainerIds().contains(owner)) throw error(403, "EXECUTION_FORBIDDEN");
        return config;
    }
    private ExecutionCoordinator.AccessScope scope() throws IOException {
        var active = coordinator.get(); if (active == null) throw error(503, "EXECUTION_ACCESS_UNAVAILABLE");
        return active.accessScope();
    }
    private static BuildIdentity view(State state) {
        var build = state.build(); var artifact = state.artifact();
        return new BuildIdentity(build.projectId(), build.id(), build.sourceVersionId(), build.treeSha256(),
                artifact.jarBytes(), artifact.jarSha256(), artifact.manifestSha256());
    }
    private static void requireIds(UUID project, UUID id) {
        if (project == null || id == null) throw error(422, "EXECUTION_INVALID_INPUT");
    }
    private static void requireHandle(RunningHandle handle) {
        if (handle == null) throw error(403, "EXECUTION_ACCESS_FORBIDDEN");
    }
    private static ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
    private static OwnedProcess.Budget budget(SourceExecutionBudget shared) {
        if (shared == null) throw error(422, "EXECUTION_INVALID_INPUT");
        var local = budget();
        return () -> {
            if (Thread.currentThread().isInterrupted()) throw new IOException("EXECUTION_CANCELLED");
            try {
                Duration own = local.remaining(), caller = shared.remaining();
                if (caller == null || caller.isNegative() || caller.isZero()) throw new IOException();
                return own.compareTo(caller) < 0 ? own : caller;
            } catch (IOException | RuntimeException failure) { throw new IOException("EXECUTION_TIMEOUT"); }
        };
    }
    private static ApiException revoked() { return error(409, "EXECUTION_ACCESS_REVOKED"); }
    private static ApiException error(int status, String code) { return new ApiException(status, code, "交付访问与当前项目、构建或运行状态不符，请刷新状态。"); }
    @FunctionalInterface private interface Read<T> { T get() throws IOException; }
    private static <T> T guarded(Read<T> read) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw error(409, "EXECUTION_ACCESS_TRANSACTION_ACTIVE");
        try { return read.get(); }
        catch (ApiException error) {
            // Stable, whitelisted codes only; lower-level messages/causes may contain private paths or configuration.
            throw switch (error.code()) {
                case "EXECUTION_DISABLED", "EXECUTION_FORBIDDEN", "EXECUTION_INVALID_INPUT", "EXECUTION_ACCESS_FORBIDDEN",
                     "EXECUTION_ACCESS_REVOKED", "PROJECT_NOT_FOUND", "EXECUTION_PROJECT_ARCHIVED",
                     "EXECUTION_BUILD_NOT_FOUND", "EXECUTION_BUILD_OWNER_CHANGED", "RUN_NOT_FOUND",
                     "DELIVERY_UPSTREAM_INVALID", "DELIVERY_UPSTREAM_UNAVAILABLE", "DELIVERY_RESPONSE_TOO_LARGE",
                     "DELIVERY_TIMEOUT", "DELIVERY_CANCELLED" -> error(error.status(), error.code());
                case "SOURCE_OPERATION_TIMEOUT" -> error(504, "DELIVERY_TIMEOUT");
                case "DELIVERY_WRITE_UNCERTAIN" -> ExecutionHttpTransport.uncertain();
                default -> error(503, "EXECUTION_ACCESS_UNAVAILABLE");
            };
        } catch (org.springframework.dao.QueryTimeoutException error) { throw error(504, "DELIVERY_TIMEOUT"); }
        catch (IOException failure) {
            throw switch (Objects.toString(failure.getMessage(), "")) {
                case "EXECUTION_TIMEOUT" -> error(504, "DELIVERY_TIMEOUT");
                case "EXECUTION_CANCELLED" -> error(503, "DELIVERY_CANCELLED");
                default -> error(503, "EXECUTION_ACCESS_UNAVAILABLE");
            };
        } catch (RuntimeException error) { throw error(503, "EXECUTION_ACCESS_UNAVAILABLE"); }
    }
}
