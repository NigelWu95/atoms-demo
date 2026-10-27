package dev.atoms.demo.workbench;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.project.Project;
import dev.atoms.demo.projectexecution.ExecutionQuery;
import dev.atoms.demo.source.ProjectSourceReadGateway;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkbenchAccessTest {
    @Test void defaultOffNeverTouchesProvidersAndMaintainerDenialIsNotASessionFailure() {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
        var projects = mock(ProjectService.class); var execution = mock(ExecutionQuery.class); var source = mock(ProjectSourceReadGateway.class);
        when(projects.get(owner, project.toString())).thenReturn(mock(Project.class));
        var env = new MockEnvironment(); var access = new WorkbenchAccess(env, projects, execution, source);
        assertThat(access.capabilities(owner, project).reason()).isEqualTo("WORKBENCH_DISABLED");
        verifyNoInteractions(execution, source);
        env.setProperty("atoms.project-workbench.enabled", "true");
        doThrow(new ApiException(403, "EXECUTION_FORBIDDEN", "denied")).when(execution).authorizeProject(owner, project);
        assertThat(access.capabilities(owner, project).enabled()).isFalse();
        assertThat(access.capabilities(owner, project).reason()).isEqualTo("EXECUTION_FORBIDDEN");
        verifyNoInteractions(source);
    }
}
