package dev.atoms.demo.source;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.demo.common.ApiException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Plain task files, never a Git worktree. Incomplete directories are retained, not adopted/deleted. */
final class SourceWorkspaceStore {
    private final ProjectSourceService service;
    private final SourceQuery query;
    private final ProjectSourceEnvironment environment;
    private final Map<UUID, Evidence> issued = new HashMap<>();

    SourceWorkspaceStore(ProjectSourceService service, SourceQuery query, ProjectSourceEnvironment environment) {
        this.service = service; this.query = query; this.environment = environment;
    }

    synchronized SourceWorkspace prepare(UUID owner, UUID project, UUID version, long revision, UUID task) {
        service.writableBase(owner, project, version, revision);
        var snapshot = query.snapshot(owner, project, version);
        service.writableBase(owner, project, version, revision);
        var handle = new SourceWorkspace(owner, project, java.util.Objects.requireNonNull(task), version, revision, snapshot.tree().sha256());
        try {
            var process = new SourceFileProcess(environment.python());
            var request = request(handle, "create");
            request.put("files", snapshot.tree().files().stream().map(file -> Map.of("path", file.path(), "mode", file.mode(),
                    "content", Base64.getEncoder().encodeToString(file.content()))).toList());
            JsonNode identity = process.run(request);
            var evidence = new Evidence(handle, identity.deepCopy());
            verify(evidence);
            service.writableBase(owner, project, version, revision);
            issued.put(task, evidence);
            return handle;
        } catch (IOException | IllegalArgumentException error) { throw unavailable(); }
    }

    synchronized Path checked(SourceWorkspace workspace) {
        var evidence = workspace == null ? null : issued.get(workspace.taskId());
        if (evidence == null || evidence.workspace() != workspace) throw unavailable();
        service.writableBase(workspace.ownerId(), workspace.projectId(), workspace.versionId(), workspace.revision());
        try {
            verify(evidence);
            return environment.existingSettings().paths().workRoot().resolve(workspace.taskId().toString()).resolve("project");
        } catch (IOException error) { throw unavailable(); }
    }

    private void verify(Evidence value) throws IOException {
        var request = request(value.workspace(), "check");
        request.put("evidence", value.identity());
        if (!new SourceFileProcess(environment.python()).run(request).equals(value.identity())) throw ProjectSourcePaths.unavailable();
        environment.existingSettings().paths().verify();
    }

    private Map<String, Object> request(SourceWorkspace workspace, String operation) throws IOException {
        var paths = environment.existingSettings().paths();
        Path root = paths.workRoot();
        var request = new HashMap<String, Object>();
        request.put("operation", operation); request.put("root", root.toString());
        request.put("rootIdentity", SourceFileProcess.identity(root));
        request.put("task", workspace.taskId().toString());
        request.put("marker", String.join("\n", "atoms-source-workspace-v1", workspace.ownerId().toString(),
                workspace.projectId().toString(), workspace.taskId().toString(), workspace.versionId().toString(),
                Long.toString(workspace.revision()), workspace.treeSha256(), ""));
        paths.verify();
        return request;
    }

    private static ApiException unavailable() { return new ApiException(409, "SOURCE_WORKSPACE_UNAVAILABLE", "任务工作副本不可用，请重新准备任务。"); }
    private record Evidence(SourceWorkspace workspace, JsonNode identity) {}
}
