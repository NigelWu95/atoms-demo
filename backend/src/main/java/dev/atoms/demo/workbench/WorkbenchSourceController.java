package dev.atoms.demo.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.demo.agent.ProjectAgentInitialization;
import dev.atoms.demo.source.ProjectSourceRestorationGateway;
import dev.atoms.demo.source.ProjectSourceWorkbenchGateway;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static dev.atoms.demo.workbench.WorkbenchRequest.*;

@RestController
@RequestMapping("/api/projects/{projectId}/workbench")
final class WorkbenchSourceController {
    private final WorkbenchAccess access;
    private final ProjectSourceWorkbenchGateway source;
    private final ProjectSourceRestorationGateway restoration;
    private final ProjectAgentInitialization initialization;
    WorkbenchSourceController(WorkbenchAccess access, ProjectSourceWorkbenchGateway source,
            ProjectSourceRestorationGateway restoration, ProjectAgentInitialization initialization) {
        this.access = access; this.source = source; this.restoration = restoration; this.initialization = initialization;
    }
    @GetMapping("/capabilities")
    WorkbenchAccess.Capabilities capabilities(@PathVariable String projectId, Principal principal, HttpServletRequest request) {
        query(request); return access.capabilities(owner(principal), id(projectId));
    }
    @GetMapping("/source/head")
    ProjectSourceRestorationGateway.Head head(@PathVariable String projectId, Principal principal, HttpServletRequest request) {
        query(request); access.require(owner(principal), id(projectId)); return restoration.head(owner(principal), id(projectId));
    }
    @GetMapping("/source/versions")
    ProjectSourceWorkbenchGateway.Versions versions(@PathVariable String projectId,
            @RequestParam(required = false) Long before, @RequestParam(defaultValue = "30") int limit,
            Principal principal, HttpServletRequest request) {
        query(request, "before", "limit"); access.require(owner(principal), id(projectId));
        return source.versions(owner(principal), id(projectId), before, limit);
    }
    @GetMapping("/source/versions/{versionId}/tree")
    List<ProjectSourceWorkbenchGateway.Entry> tree(@PathVariable String projectId, @PathVariable String versionId,
            Principal principal, HttpServletRequest request) {
        query(request); access.require(owner(principal), id(projectId)); return source.tree(owner(principal), id(projectId), id(versionId));
    }
    @GetMapping("/source/versions/{versionId}/file")
    ProjectSourceWorkbenchGateway.FileView file(@PathVariable String projectId, @PathVariable String versionId,
            @RequestParam String path, Principal principal, HttpServletRequest request) {
        query(request, "path"); access.require(owner(principal), id(projectId)); return source.file(owner(principal), id(projectId), id(versionId), path);
    }
    @GetMapping("/source/diff")
    ProjectSourceWorkbenchGateway.Diff diff(@PathVariable String projectId, @RequestParam String before, @RequestParam String after,
            Principal principal, HttpServletRequest request) {
        query(request, "before", "after"); access.require(owner(principal), id(projectId)); return source.diff(owner(principal), id(projectId), id(before), id(after));
    }
    @PostMapping("/source/initialize")
    ResponseEntity<ProjectSourceWorkbenchGateway.Operation> initialize(@PathVariable String projectId, @RequestBody JsonNode input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Principal principal, HttpServletRequest request) {
        body(input, request); key(key); access.require(owner(principal), id(projectId));
        var op = initialization.initialize(owner(principal), id(projectId), key).operation();
        return ResponseEntity.accepted().body(source.operation(owner(principal), id(projectId), op.id()));
    }
    @GetMapping("/source/operations/{operationId}")
    ProjectSourceWorkbenchGateway.Operation operation(@PathVariable String projectId, @PathVariable String operationId,
            Principal principal, HttpServletRequest request) {
        query(request); access.require(owner(principal), id(projectId)); return source.operation(owner(principal), id(projectId), id(operationId));
    }
    @GetMapping("/source/operations")
    List<ProjectSourceWorkbenchGateway.Operation> operations(@PathVariable String projectId, Principal principal, HttpServletRequest request) {
        query(request); access.require(owner(principal), id(projectId)); return source.operations(owner(principal), id(projectId));
    }
}
