package dev.atoms.demo.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.demo.projectexecution.*;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static dev.atoms.demo.workbench.WorkbenchRequest.*;

@RestController
@RequestMapping("/api/projects/{projectId}/workbench")
final class WorkbenchBuildController {
    private final WorkbenchAccess access;
    private final ProjectExecutionService builds;
    private final DependencyPreparationService preparations;
    private final ExecutionQuery tasks;
    WorkbenchBuildController(WorkbenchAccess access, ProjectExecutionService builds, DependencyPreparationService preparations, ExecutionQuery tasks) {
        this.access = access; this.builds = builds; this.preparations = preparations; this.tasks = tasks;
    }
    @GetMapping({"/builds", "/preparations", "/runs"})
    ExecutionQuery.Page<ExecutionQuery.TaskView> list(@PathVariable String projectId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "30") int limit, Principal principal, HttpServletRequest request) {
        query(request, "cursor", "limit"); access.require(owner(principal), id(projectId));
        var kind = request.getRequestURI().endsWith("/builds") ? ExecutionQuery.Kind.BUILD
                : request.getRequestURI().endsWith("/preparations") ? ExecutionQuery.Kind.PREPARATION : ExecutionQuery.Kind.RUN;
        return tasks.tasks(owner(principal), id(projectId), kind, cursor, limit);
    }
    @PostMapping("/builds")
    ResponseEntity<ProjectExecutionService.BuildView> build(@PathVariable String projectId, @RequestBody JsonNode input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Principal principal, HttpServletRequest request) {
        body(input, request, "sourceVersionId"); var version = id(input, "sourceVersionId"); access.require(owner(principal), id(projectId));
        return ResponseEntity.accepted().body(builds.submit(owner(principal), id(projectId), version, key(key)));
    }
    @GetMapping("/builds/{buildId}")
    ProjectExecutionService.BuildView build(@PathVariable String projectId, @PathVariable String buildId, Principal principal, HttpServletRequest request) {
        query(request); access.require(owner(principal), id(projectId)); return builds.get(owner(principal), id(projectId), id(buildId));
    }
    @PostMapping("/builds/{buildId}/cancel")
    ProjectExecutionService.BuildView cancelBuild(@PathVariable String projectId, @PathVariable String buildId, @RequestBody JsonNode input,
            Principal principal, HttpServletRequest request) {
        body(input, request); access.require(owner(principal), id(projectId)); return builds.cancel(owner(principal), id(projectId), id(buildId));
    }
    @PostMapping("/preparations")
    ResponseEntity<ExecutionQuery.TaskView> prepare(@PathVariable String projectId, @RequestBody JsonNode input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Principal principal, HttpServletRequest request) {
        body(input, request, "baseSourceVersionId", "expectedSourceRevision");
        var command = new DependencyPreparationService.Input(id(input, "baseSourceVersionId"), revision(input, "expectedSourceRevision"));
        access.require(owner(principal), id(projectId));
        return ResponseEntity.accepted().body(view(preparations.submit(owner(principal), id(projectId), command, key(key))));
    }
    @PostMapping("/preparations/{taskId}/cancel")
    ExecutionQuery.TaskView cancelPreparation(@PathVariable String projectId, @PathVariable String taskId, @RequestBody JsonNode input,
            Principal principal, HttpServletRequest request) {
        body(input, request); access.require(owner(principal), id(projectId)); return view(preparations.cancel(owner(principal), id(projectId), id(taskId)));
    }
    @GetMapping("/execution/{kind}/{taskId}/events")
    ExecutionQuery.Page<ExecutionQuery.EventView> events(@PathVariable String projectId, @PathVariable String kind, @PathVariable String taskId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit, Principal principal, HttpServletRequest request) {
        query(request, "cursor", "limit"); ExecutionQuery.Kind type;
        try { type = ExecutionQuery.Kind.valueOf(kind); } catch (IllegalArgumentException invalid) { throw invalid(); }
        access.require(owner(principal), id(projectId)); return tasks.events(owner(principal), id(projectId), type, id(taskId), cursor, limit);
    }
    private static ExecutionQuery.TaskView view(DependencyPreparationService.TaskView t) {
        return new ExecutionQuery.TaskView(t.id(), t.projectId(), ExecutionQuery.Kind.PREPARATION, t.baseVersionId(), null,
                t.resultVersionId(), t.status(), t.cancelRequested(), false, t.executionPending(), t.errorCode(), t.createdAt(), t.finishedAt());
    }
}
