package dev.atoms.demo.workbench;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.projectexecution.ExecutionQuery;
import dev.atoms.demo.source.ProjectSourceReadGateway;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** An additional UI opt-in, never a substitute for the provider's action-specific authorization. */
@Service
final class WorkbenchAccess {
    private final Environment environment;
    private final ProjectService projects;
    private final ExecutionQuery execution;
    private final ProjectSourceReadGateway source;
    WorkbenchAccess(Environment environment, ProjectService projects, ExecutionQuery execution, ProjectSourceReadGateway source) {
        this.environment = environment; this.projects = projects; this.execution = execution; this.source = source;
    }
    record Capabilities(boolean enabled, String reason, boolean archived, boolean sourceReady,
                        boolean agentEnabled, boolean deliveryEnabled) {}

    Capabilities capabilities(UUID owner, UUID project) {
        var current = projects.get(owner, project.toString());
        try {
            require(owner, project);
            return new Capabilities(true, current.archived() ? "PROJECT_ARCHIVED" : null, current.archived(),
                    source.readyForExecution(), flag("agent.enabled"), flag("project-delivery.enabled"));
        } catch (ApiException unavailable) {
            if (unavailable.status() == 404 || unavailable.status() == 401) throw unavailable;
            return new Capabilities(false, unavailable.code(), current.archived(), false, false, false);
        }
    }
    void require(UUID owner, UUID project) {
        projects.get(owner, project.toString());
        if (!flag("project-workbench.enabled")) throw new ApiException(503, "WORKBENCH_DISABLED", "完整工程工作台尚未启用。");
        execution.authorizeProject(owner, project);
    }
    private boolean flag(String name) {
        String value = environment.getProperty("atoms." + name, "false");
        if (!value.equals("true") && !value.equals("false")) throw new ApiException(503, "WORKBENCH_CONFIGURATION_INVALID", "工作台配置无效。");
        return value.equals("true");
    }
}
