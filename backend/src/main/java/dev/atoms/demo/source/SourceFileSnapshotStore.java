package dev.atoms.demo.source;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Internal filesystem protocol. Authorization and issuance of capabilities belong to the caller. */
final class SourceFileSnapshotStore {
    private final ProjectSourceEnvironment environment;
    private final SourceDeadline deadline;
    SourceFileSnapshotStore(ProjectSourceEnvironment environment) { this(environment, null); }
    private SourceFileSnapshotStore(ProjectSourceEnvironment environment, SourceDeadline deadline) {
        this.environment = environment; this.deadline = deadline;
    }

    SourceFileSnapshotStore withDeadline(SourceDeadline deadline) {
        return new SourceFileSnapshotStore(environment, java.util.Objects.requireNonNull(deadline));
    }

    Snapshot create(UUID scopeProject, UUID id, String marker, SourceTree input) throws IOException {
        if (deadline == null) return withDeadline(new SourceDeadline(environment.operationTimeout())).create(scopeProject, id, marker, input);
        deadline.remaining(environment.operationTimeout());
        var tree = new ProjectSourcePolicy().validate(input.files());
        if (!tree.sha256().equals(input.sha256()) || tree.totalBytes() != input.totalBytes())
            throw new IllegalArgumentException("Source tree metadata mismatch");
        environment.storageBudget().check(tree.totalBytes() + (tree.files().size() + 1L) * 4096, deadline);
        var request = request(scopeProject, id, marker, "create");
        request.put("files", tree.files().stream().map(file -> Map.of("path", file.path(), "mode", file.mode(),
                "content", Base64.getEncoder().encodeToString(file.content()))).toList());
        var result = new Snapshot(scopeProject, id, marker, invoke(request));
        environment.storageBudget().check(0, deadline);
        check(result);
        return result;
    }

    void check(Snapshot snapshot) throws IOException {
        if (deadline == null) { withDeadline(new SourceDeadline(environment.operationTimeout())).check(snapshot); return; }
        if (!invoke(request(snapshot, "check")).equals(snapshot.identity())) throw ProjectSourcePaths.unavailable();
    }

    SourceTree read(Snapshot snapshot) throws IOException {
        if (deadline == null) return withDeadline(new SourceDeadline(environment.operationTimeout())).read(snapshot);
        var result = invoke(request(snapshot, "scan"));
        if (!result.path("evidence").equals(snapshot.identity()) || !result.path("files").isArray()
                || result.path("files").size() > 512) throw ProjectSourcePaths.unavailable();
        var files = new ArrayList<SourceTree.File>();
        for (var file : result.path("files")) {
            if (!file.path("path").isTextual() || !file.path("content").isTextual() || !file.path("mode").isInt())
                throw ProjectSourcePaths.unavailable();
            files.add(new SourceTree.File(file.path("path").textValue(), Base64.getDecoder().decode(file.path("content").textValue()),
                    file.path("mode").intValue()));
        }
        var tree = new ProjectSourcePolicy().validate(files);
        deadline.remaining(environment.operationTimeout());
        return tree;
    }

    private JsonNode invoke(Map<String, Object> request) throws IOException {
        var timeout = deadline.remaining(java.time.Duration.ofSeconds(60));
        var result = new SourceFileProcess(environment.python(), timeout, handle -> {}).run(request);
        deadline.remaining(environment.operationTimeout());
        environment.existingSettings().paths().verify();
        return result;
    }

    private Map<String, Object> request(Snapshot snapshot, String operation) throws IOException {
        var request = request(snapshot.scopeProject(), snapshot.id(), snapshot.marker(), operation);
        request.put("evidence", snapshot.identity());
        return request;
    }

    private Map<String, Object> request(UUID scopeProject, UUID id, String marker, String operation) throws IOException {
        var paths = environment.existingSettings().paths();
        Path root = scopeProject == null ? paths.workRoot() : paths.root();
        var request = new HashMap<String, Object>();
        request.put("operation", operation); request.put("root", root.toString());
        request.put("rootIdentity", SourceFileProcess.identity(root));
        request.put("task", id.toString()); request.put("marker", marker);
        if (scopeProject != null) request.put("scopeProject", scopeProject.toString());
        paths.verify();
        return request;
    }

    record Snapshot(UUID scopeProject, UUID id, String marker, JsonNode identity) {
        Snapshot { identity = identity.deepCopy(); }
        @Override public JsonNode identity() { return identity.deepCopy(); }
    }
}
