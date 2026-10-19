package dev.atoms.demo.agent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AgentRetentionSchedulingTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void maintenanceRegistrationIsIndependentOfTheLegacyGenerationScheduler(boolean enabled) {
        try (var context = new AnnotationConfigApplicationContext()) {
            var environment = new MockEnvironment().withProperty("atoms.agent.enabled", Boolean.toString(enabled))
                    .withProperty("atoms.generation.worker-enabled", "false");
            context.setEnvironment(environment);
            context.registerBean(AgentSettings.class, () -> new AgentSettings(environment));
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(AgentTaskRepository.class, () -> new AgentTaskRepository(context.getBean(JdbcTemplate.class)));
            context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
            context.register(AgentRetention.class); context.refresh();
            assertThat(context.getBeansOfType(AgentRetention.class)).hasSize(enabled ? 1 : 0);
            var schedulers = context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class);
            if (enabled) {
                assertThat(schedulers).hasSize(1);
                assertThat(schedulers.values().iterator().next().getScheduledTasks()).hasSize(1);
            } else assertThat(schedulers).isEmpty();
        }
    }
}
