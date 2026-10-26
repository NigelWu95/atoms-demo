package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.ProjectSourceReadGateway;
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
        return guarded(() -> {
            requireIds(project, build); var config = authorize(owner); var scope = scope(); var deadline = budget();
            var current = state(owner, project, build, null);
            var handle = open(config, scope, current, deadline);
            checked(owner, project, handle, null, deadline); return handle;
        });
    }
    /** A READY database row is insufficient: the original worker must still own this exact serving process. */
    public RunningHandle running(UUID owner, UUID project, UUID run) {
        return guarded(() -> {
            requireIds(project, run); var config = authorize(owner); var scope = scope(); var deadline = budget();
            var current = state(owner, project, null, run);
            var build = open(config, scope, current, deadline);
            var handle = new RunningHandle(build, current.run(), scope.ownedProcess(run));
            checked(owner, project, build, handle, deadline); return handle;
        });
    }
    public BuildIdentity identity(UUID owner, UUID project, BuildHandle handle) {
        return guarded(() -> { checked(owner, project, handle, null, budget()); return view(handle.state); });
    }
    public RunningIdentity identity(UUID owner, UUID project, RunningHandle handle) {
        return guarded(() -> {
            requireHandle(handle); checked(owner, project, handle.build, handle, budget());
            return new RunningIdentity(handle.run.id(), handle.run.executionId(), view(handle.build.state));
        });
    }
    /** Immutable Git snapshot for packaging; never reads the mutable build cwd. Reauthorize subsequent actions. */
    public ProjectSourceReadGateway.Snapshot source(UUID owner, UUID project, BuildHandle handle) {
        return guarded(() -> checked(owner, project, handle, null, budget()));
    }
    public ProjectSourceReadGateway.Snapshot source(UUID owner, UUID project, RunningHandle handle) {
        return guarded(() -> { requireHandle(handle); return checked(owner, project, handle.build, handle, budget()); });
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
            ExecutionProcess.Deadline deadline) throws IOException {
        var artifact = scope.artifact(current.build(), current.artifact().jarSha256(), current.artifact().manifestSha256(), deadline);
        if (artifact == null || !current.artifact().equals(artifact.summary())) throw revoked();
        return new BuildHandle(this, config, scope, new State(current.build(), current.artifact(), null), artifact);
    }
    private ProjectSourceReadGateway.Snapshot checked(UUID owner, UUID project, BuildHandle handle,
            RunningHandle running, ExecutionProcess.Deadline deadline) throws IOException {
        if (handle == null || handle.issuer != this || !handle.state.build().ownerId().equals(owner)
                || !handle.state.build().projectId().equals(project)) throw error(403, "EXECUTION_ACCESS_FORBIDDEN");
        handle.scope.check(); compare(owner, project, handle, running); serving(handle, running);
        handle.artifact.verify(deadline);
        var build = handle.state.build();
        var snapshot = source.read(owner, project, build.sourceVersionId(), deadline::remaining);
        if (!build.sourceVersionId().equals(snapshot.version().id()) || !project.equals(snapshot.version().projectId())
                || !build.treeSha256().equals(snapshot.version().treeSha256()) || !build.treeSha256().equals(snapshot.tree().sha256())) throw revoked();
        // Source/Git I/O held no DB lock. Recheck both files and authority before returning any data.
        handle.artifact.verify(deadline); handle.scope.check();
        compare(owner, project, handle, running); serving(handle, running); handle.scope.check(); deadline.remaining();
        return snapshot;
    }
    private void compare(UUID owner, UUID project, BuildHandle handle, RunningHandle running) {
        if (!handle.configuration.equals(authorize(owner))) throw revoked();
        var current = state(owner, project, handle.state.build().id(), running == null ? null : running.run.id());
        if (!current.build().equals(handle.state.build()) || !current.artifact().equals(handle.state.artifact())
                || running != null && !current.run().equals(running.run)
                || !handle.configuration.equals(authorize(owner))) throw revoked();
    }
    private void serving(BuildHandle handle, RunningHandle running) throws IOException {
        if (running != null && (running.process == null || handle.scope.ownedProcess(running.run.id()) != running.process
                || !running.process.serves(running.run.id(), running.run.executionId()))) throw revoked();
    }
    private State state(UUID owner, UUID project, UUID buildId, UUID runId) {
        return transaction.execute(tx -> {
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
                     "EXECUTION_BUILD_NOT_FOUND", "EXECUTION_BUILD_OWNER_CHANGED", "RUN_NOT_FOUND" -> error(error.status(), error.code());
                default -> error(503, "EXECUTION_ACCESS_UNAVAILABLE");
            };
        } catch (IOException | RuntimeException error) { throw error(503, "EXECUTION_ACCESS_UNAVAILABLE"); }
    }
}
