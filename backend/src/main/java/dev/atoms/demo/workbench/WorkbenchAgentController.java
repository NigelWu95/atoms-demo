package dev.atoms.demo.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.demo.agent.AgentQuery;
import dev.atoms.demo.agent.ProjectAgentService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static dev.atoms.demo.workbench.WorkbenchRequest.*;

@RestController
@RequestMapping("/api/projects/{projectId}/workbench/agent/tasks")
final class WorkbenchAgentController {
    private final WorkbenchAccess access;
    private final ProjectAgentService agent;
    private final AgentQuery tasks;
    WorkbenchAgentController(WorkbenchAccess access, ProjectAgentService agent, AgentQuery tasks) {
        this.access = access; this.agent = agent; this.tasks = tasks;
    }
    @GetMapping
    List<AgentQuery.WorkbenchTask> list(@PathVariable String projectId, @RequestParam(defaultValue = "50") int limit,
            Principal principal, HttpServletRequest request) {
        query(request, "limit"); access.require(owner(principal), id(projectId)); return tasks.workbenchTasks(owner(principal), id(projectId), limit);
    }
    @GetMapping("/{taskId}")
    AgentQuery.WorkbenchTask task(@PathVariable String projectId, @PathVariable String taskId, Principal principal, HttpServletRequest request) {
        query(request); access.require(owner(principal), id(projectId)); return tasks.workbenchTask(owner(principal), id(projectId), id(taskId));
    }
    @PostMapping
    ResponseEntity<ProjectAgentService.TaskView> submit(@PathVariable String projectId, @RequestBody JsonNode input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Principal principal, HttpServletRequest request) {
        body(input, request, "prompt", "baseSourceVersionId", "expectedSourceRevision");
        var command = new ProjectAgentService.Input(text(input, "prompt"), id(input, "baseSourceVersionId").toString(), revision(input, "expectedSourceRevision"));
        access.require(owner(principal), id(projectId));
        return ResponseEntity.accepted().body(agent.submit(owner(principal), id(projectId), command, key(key)));
    }
    @PostMapping("/{taskId}/cancel")
    ProjectAgentService.TaskView cancel(@PathVariable String projectId, @PathVariable String taskId, @RequestBody JsonNode input,
            Principal principal, HttpServletRequest request) {
        body(input, request); access.require(owner(principal), id(projectId)); return agent.cancel(owner(principal), id(projectId), id(taskId));
    }
    @GetMapping("/{taskId}/events")
    AgentQuery.EventPage events(@PathVariable String projectId, @PathVariable String taskId, @RequestParam(defaultValue = "0") int after,
            @RequestParam(defaultValue = "100") int limit, Principal principal, HttpServletRequest request) {
        query(request, "after", "limit"); access.require(owner(principal), id(projectId)); return tasks.events(owner(principal), id(projectId), id(taskId), after, limit);
    }
}
