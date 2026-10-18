package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Plain task files, never a Git worktree. Incomplete directories are retained, not adopted/deleted. */
final class SourceWorkspaceStore {
    private final ProjectSourceService service;
    private final SourceQuery query;
    private final ProjectSourceEnvironment environment;
    private final SourceFileSnapshotStore files;
    private final int capacity;
    private final Map<UUID, Evidence> issued = new HashMap<>();

    SourceWorkspaceStore(ProjectSourceService service, SourceQuery query, ProjectSourceEnvironment environment) {
        this(service, query, environment, 64);
    }
    SourceWorkspaceStore(ProjectSourceService service, SourceQuery query, ProjectSourceEnvironment environment, int capacity) {
        if (capacity < 1 || capacity > 64) throw new IllegalArgumentException("Invalid workspace capacity");
        this.capacity = capacity;
        this.service = service; this.query = query; this.environment = environment;
        this.files = new SourceFileSnapshotStore(environment);
    }

    synchronized SourceWorkspace prepare(UUID owner, UUID project, UUID version, long revision, UUID task) {
        return prepare(owner, project, version, revision, task, null);
    }

    synchronized SourceWorkspace prepare(UUID owner, UUID project, UUID version, long revision, UUID task, SourceEditLeaseRepository.Lease permit) {
        return prepare(owner, project, version, revision, task, permit, new SourceDeadline(environment.operationTimeout()));
    }

    synchronized SourceWorkspace prepare(UUID owner, UUID project, UUID version, long revision, UUID task,
                                         SourceEditLeaseRepository.Lease permit, SourceDeadline deadline) {
        service.writableBase(owner, project, version, revision, permit);
        if (issued.size() >= capacity) throw ProjectSourceService.conflict("SOURCE_CAPACITY_EXCEEDED");
        var snapshot = query.snapshot(owner, project, version, deadline);
        service.writableBase(owner, project, version, revision, permit);
        var handle = new SourceWorkspace(owner, project, java.util.Objects.requireNonNull(task), version, revision, snapshot.tree().sha256());
        try {
            var copy = files.withDeadline(deadline).create(null, task, marker(handle), snapshot.tree());
            var evidence = new Evidence(handle, copy, permit);
            service.writableBase(owner, project, version, revision, permit);
            issued.put(task, evidence);
            return handle;
        } catch (IOException | IllegalArgumentException error) { throw unavailable(); }
    }

    synchronized Path checked(SourceWorkspace workspace) {
        return checked(workspace, new SourceDeadline(environment.operationTimeout()));
    }

    synchronized Path checked(SourceWorkspace workspace, SourceDeadline deadline) {
        var evidence = authorized(workspace);
        service.writableBase(workspace.ownerId(), workspace.projectId(), workspace.versionId(), workspace.revision(), evidence.permit());
        try {
            files.withDeadline(deadline).check(evidence.snapshot());
            return environment.existingSettings().paths().workRoot().resolve(workspace.taskId().toString()).resolve("project");
        } catch (IOException error) { throw unavailable(); }
    }

    synchronized SourceTree read(SourceWorkspace workspace) {
        return read(workspace, new SourceDeadline(environment.operationTimeout()));
    }

    synchronized SourceTree read(SourceWorkspace workspace, SourceDeadline deadline) {
        checked(workspace, deadline);
        try {
            var tree = files.withDeadline(deadline).read(issued.get(workspace.taskId()).snapshot());
            checked(workspace, deadline);
            return tree;
        } catch (IOException | IllegalArgumentException error) { throw unavailable(); }
    }

    synchronized void authorize(SourceWorkspace workspace) { authorized(workspace); }

    synchronized void release(SourceWorkspace workspace) {
        var entry = workspace == null ? null : issued.get(workspace.taskId());
        if (entry != null && entry.workspace() == workspace) issued.remove(workspace.taskId());
        // Release only the in-memory capability. Residual files remain evidence, never automatic cleanup.
    }

    private Evidence authorized(SourceWorkspace workspace) {
        var evidence = workspace == null ? null : issued.get(workspace.taskId());
        if (evidence == null || evidence.workspace() != workspace) throw unavailable();
        service.authorize(workspace.ownerId(), workspace.projectId());
        return evidence;
    }

    private static String marker(SourceWorkspace workspace) {
        return String.join("\n", "atoms-source-workspace-v1", workspace.ownerId().toString(),
                workspace.projectId().toString(), workspace.taskId().toString(), workspace.versionId().toString(),
                Long.toString(workspace.revision()), workspace.treeSha256(), "");
    }

    private static ApiException unavailable() { return new ApiException(409, "SOURCE_WORKSPACE_UNAVAILABLE", "任务工作副本不可用，请重新准备任务。"); }
    private record Evidence(SourceWorkspace workspace, SourceFileSnapshotStore.Snapshot snapshot, SourceEditLeaseRepository.Lease permit) {}
}
