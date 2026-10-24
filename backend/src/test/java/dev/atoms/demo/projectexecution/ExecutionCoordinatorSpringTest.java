package dev.atoms.demo.projectexecution;

import dev.atoms.demo.source.ProjectSourceReadGateway;
import dev.atoms.demo.source.ProjectSourceEditGateway;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionCoordinatorSpringTest {
    @Test void disabledApplicationDoesNotRegisterOrResolveAnyExecutionResources() {
        for (String enabled : new String[]{"", "false"}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                var configuration = new MockEnvironment().withProperty("atoms.project-execution.root", "invalid-private-path");
                if (!enabled.isEmpty()) configuration.withProperty("atoms.project-execution.enabled", enabled);
                context.setEnvironment(configuration);
                context.register(ExecutionCoordinator.class);
                context.refresh();
                assertThat(context.getBeansOfType(ExecutionCoordinator.class)).isEmpty();
            }
        }
    }

    @Test void enabledSpringLifecycleWaitsForSourceReadinessBeforeOpeningResources() throws Exception {
        var source = mock(ProjectSourceReadGateway.class);
        var resources = mock(ExecutionEnvironment.class);
        var database = mock(DataSource.class);
        var builds = mock(ExecutionBuildRepository.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            var configuration = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                    .withProperty("atoms.project-execution.maintainer-ids", UUID.randomUUID().toString());
            for (String name : new String[]{"root", "node", "npm", "java", "maven", "policy"})
                configuration.withProperty("atoms.project-execution." + name, "/private/test-only/" + name);
            context.setEnvironment(configuration);
            context.registerBean(ExecutionSettings.class, () -> new ExecutionSettings(configuration));
            context.registerBean(ExecutionBuildRepository.class, () -> builds);
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
            context.registerBean(DataSource.class, () -> database);
            context.registerBean(ProjectSourceReadGateway.class, () -> source);
            context.registerBean(ProjectSourceEditGateway.class, () -> mock(ProjectSourceEditGateway.class));
            context.registerBean(DependencyPreparationService.class, () -> mock(DependencyPreparationService.class));
            context.registerBean(ExecutionEnvironment.class, () -> resources);
            context.register(ExecutionCoordinator.class); context.refresh();
            assertThat(context.getBean(ExecutionCoordinator.class).isRunning()).isFalse();
            verify(source).readyForExecution();
            verifyNoInteractions(resources, database, builds);
        }
        verifyNoInteractions(resources, database, builds);
    }
}
