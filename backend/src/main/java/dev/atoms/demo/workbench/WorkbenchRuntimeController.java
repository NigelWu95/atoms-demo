package dev.atoms.demo.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.projectexecution.ExecutionQuery;
import dev.atoms.demo.projectexecution.ProjectExecutionWorkbench;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import static dev.atoms.demo.workbench.WorkbenchRequest.*;

@RestController
@RequestMapping("/api/projects/{projectId}/workbench")
final class WorkbenchRuntimeController {
    private final WorkbenchAccess access;
    private final ProjectExecutionWorkbench runtime;
    WorkbenchRuntimeController(WorkbenchAccess access, ProjectExecutionWorkbench runtime) { this.access = access; this.runtime = runtime; }
    @GetMapping("/runtime")
    ProjectExecutionWorkbench.Availability availability(@PathVariable String projectId, Principal principal, HttpServletRequest request) {
        query(request); access.require(owner(principal), id(projectId)); return runtime.availability(owner(principal), id(projectId));
    }
    @GetMapping("/builds/{buildId}/migration")
    ProjectExecutionWorkbench.Migration migration(@PathVariable String projectId, @PathVariable String buildId,
            Principal principal, HttpServletRequest request) {
        query(request); access.require(owner(principal), id(projectId)); return runtime.migration(owner(principal), id(projectId), id(buildId));
    }
    @PostMapping("/builds/{buildId}/migration-approvals")
    ProjectExecutionWorkbench.Approval approve(@PathVariable String projectId, @PathVariable String buildId, @RequestBody JsonNode input,
            Principal principal, HttpServletRequest request) {
        body(input, request, "historySha256", "targetSha256");
        String history = text(input, "historySha256"), target = text(input, "targetSha256");
        access.require(owner(principal), id(projectId)); return runtime.approve(owner(principal), id(projectId), id(buildId), history, target);
    }
    @PostMapping("/runs")
    DeferredResult<ResponseEntity<ExecutionQuery.TaskView>> start(@PathVariable String projectId, @RequestBody JsonNode input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Principal principal, HttpServletRequest request) {
        body(input, request, "buildId", "bindingId", "approvalId", "predecessorId");
        var command = new ProjectExecutionWorkbench.Start(id(input, "buildId"), id(input, "bindingId"),
                optionalId(input, "approvalId"), optionalId(input, "predecessorId"));
        access.require(owner(principal), id(projectId));
        var pending = runtime.start(owner(principal), id(projectId), command, key(key));
        // Admission preflight is performed by the bounded original runtime pool, never a new servlet executor.
        var response = new DeferredResult<ResponseEntity<ExecutionQuery.TaskView>>(30_000L);
        response.onTimeout(() -> response.setErrorResult(new ApiException(504, "RUN_ADMISSION_UNCERTAIN", "启动受理结果尚不确定，请查询运行状态或使用原请求重试。")));
        pending.whenComplete((run, failure) -> {
            if (failure == null) response.setResult(ResponseEntity.accepted().body(run));
            else response.setErrorResult(failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure);
        });
        return response;
    }
    @PostMapping("/runs/{runId}/stop")
    ExecutionQuery.TaskView stop(@PathVariable String projectId, @PathVariable String runId, @RequestBody JsonNode input,
            Principal principal, HttpServletRequest request) {
        body(input, request); access.require(owner(principal), id(projectId)); return runtime.stop(owner(principal), id(projectId), id(runId));
    }
}
