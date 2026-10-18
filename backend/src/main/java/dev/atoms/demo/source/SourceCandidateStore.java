package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Seals source only. The execution module must confirm its agent has stopped before invoking seal. */
final class SourceCandidateStore {
    private final SourceWorkspaceStore workspaces;
    private final SourceFileSnapshotStore files;
    private final ProjectSourceEnvironment environment;
    private final Map<UUID, Entry> issued = new HashMap<>();
    private final int capacity;

    SourceCandidateStore(SourceWorkspaceStore workspaces, ProjectSourceEnvironment environment) {
        this(workspaces, environment, 64);
    }
    SourceCandidateStore(SourceWorkspaceStore workspaces, ProjectSourceEnvironment environment, int capacity) {
        if (capacity < 1 || capacity > 64) throw new IllegalArgumentException("Invalid candidate capacity");
        this.capacity = capacity;
        this.workspaces = workspaces; this.files = new SourceFileSnapshotStore(environment); this.environment = environment;
    }

    synchronized SourceCandidate seal(SourceWorkspace workspace) {
        return seal(workspace, new SourceDeadline(environment.operationTimeout()));
    }

    synchronized SourceCandidate seal(SourceWorkspace workspace, SourceDeadline deadline) {
        var boundedFiles = files.withDeadline(deadline);
        workspaces.authorize(workspace);
        if (issued.size() >= capacity) throw ProjectSourceService.conflict("SOURCE_CAPACITY_EXCEEDED");
        if (issued.values().stream().anyMatch(value -> value.workspace() == workspace)) throw unavailable();
        var tree = workspaces.read(workspace, deadline);
        UUID id = UUID.randomUUID();
        var candidate = new SourceCandidate(id, workspace.ownerId(), workspace.projectId(), workspace.taskId(),
                workspace.versionId(), workspace.revision(), tree.sha256());
        String marker = String.join("\n", "atoms-source-candidate-v1", id.toString(), workspace.ownerId().toString(),
                workspace.projectId().toString(), workspace.taskId().toString(), workspace.versionId().toString(),
                Long.toString(workspace.revision()), tree.sha256(), "");
        try {
            var snapshot = boundedFiles.create(workspace.projectId(), id, marker, tree);
            if (!boundedFiles.read(snapshot).sha256().equals(tree.sha256())) throw unavailable();
            workspaces.checked(workspace, deadline);
            issued.put(id, new Entry(candidate, workspace, snapshot));
            return candidate;
        } catch (IOException | IllegalArgumentException error) { throw unavailable(); }
    }

    synchronized void authorize(UUID owner, UUID project, SourceCandidate candidate) { checked(owner, project, candidate); }

    synchronized void release(SourceCandidate candidate) {
        var entry = candidate == null ? null : issued.get(candidate.id());
        if (entry != null && entry.candidate() == candidate) {
            issued.remove(candidate.id());
            workspaces.release(entry.workspace());
        }
    }

    synchronized SourceTree read(UUID owner, UUID project, SourceCandidate candidate) {
        return read(owner, project, candidate, new SourceDeadline(environment.operationTimeout()));
    }

    synchronized SourceTree read(UUID owner, UUID project, SourceCandidate candidate, SourceDeadline deadline) {
        var entry = checked(owner, project, candidate);
        try {
            workspaces.checked(entry.workspace(), deadline);
            var tree = files.withDeadline(deadline).read(entry.snapshot());
            if (!tree.sha256().equals(candidate.treeSha256())) throw unavailable();
            workspaces.checked(entry.workspace(), deadline);
            return tree;
        } catch (IOException | IllegalArgumentException error) { throw unavailable(); }
    }

    private Entry checked(UUID owner, UUID project, SourceCandidate candidate) {
        var entry = candidate == null ? null : issued.get(candidate.id());
        if (entry == null || entry.candidate() != candidate || !candidate.ownerId().equals(owner)
                || !candidate.projectId().equals(project)) throw unavailable();
        workspaces.authorize(entry.workspace());
        return entry;
    }

    private static ApiException unavailable() { return new ApiException(409, "SOURCE_CANDIDATE_UNAVAILABLE", "候选源码不可用，请重新准备修改。"); }
    private record Entry(SourceCandidate candidate, SourceWorkspace workspace, SourceFileSnapshotStore.Snapshot snapshot) {}
}
