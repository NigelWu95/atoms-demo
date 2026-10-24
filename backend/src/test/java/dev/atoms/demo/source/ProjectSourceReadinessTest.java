package dev.atoms.demo.source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectSourceReadinessTest {
    @TempDir Path temporary;

    @Test void disabledReadinessDoesNotCreateACoordinatorOrStorage() throws Exception {
        var environment = new ProjectSourceEnvironment(new MockEnvironment());
        var gateway = gateway(environment, () -> { throw new AssertionError("Disabled source must not resolve a coordinator"); });
        assertThat(gateway.readyForExecution()).isFalse();
        try (var files = Files.list(temporary)) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void readinessTracksLifecycleWithoutStartingStoppingOrCachingIt() throws Exception {
        var environment = enabled();
        var current = new AtomicReference<SourceCoordinator>();
        var gateway = gateway(environment, current::get);
        assertThat(gateway.readyForExecution()).isFalse();
        var scheduler = mock(SourceCoordinator.class); current.set(scheduler);
        assertThat(gateway.readyForExecution()).isFalse();
        when(scheduler.isRunning()).thenReturn(true);
        assertThat(gateway.readyForExecution()).isTrue();
        when(scheduler.isRunning()).thenReturn(false);
        assertThat(gateway.readyForExecution()).isFalse();
        verify(scheduler, never()).start(); verify(scheduler, never()).stop(); verify(scheduler, never()).close();
        assertThat(new ProjectSourceReadGateway(null, null, environment).readyForExecution()).isFalse();
        try (var files = Files.list(temporary)) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void unavailableConfigurationOrProviderNeverReportsReadiness() {
        assertThat(gateway(enabled(), () -> { throw new IllegalStateException("private-provider-canary"); }).readyForExecution()).isFalse();
        var invalid = new ProjectSourceEnvironment(new MockEnvironment().withProperty("atoms.source.enabled", "private-config-canary"));
        assertThat(gateway(invalid, () -> { throw new AssertionError("Invalid config must not resolve resources"); }).readyForExecution()).isFalse();
    }

    @Test void springUsesTheOptionalCoordinatorWithoutChangingExistingReadConstruction() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment().withProperty("atoms.source.enabled", "true"));
            var scheduler = mock(SourceCoordinator.class);
            context.registerBean(ProjectSourceService.class, () -> mock(ProjectSourceService.class));
            context.registerBean(SourceRepository.class, () -> mock(SourceRepository.class));
            context.registerBean(ProjectSourceEnvironment.class, this::enabled);
            context.registerBean(SourceCoordinator.class, () -> scheduler);
            context.register(ProjectSourceReadGateway.class); context.refresh();
            assertThat(context.getBean(SourceCoordinator.class)).isSameAs(scheduler);
            var gateway = context.getBean(ProjectSourceReadGateway.class);
            assertThat(gateway.readyForExecution()).isFalse();
            when(scheduler.isRunning()).thenReturn(true);
            assertThat(gateway.readyForExecution()).isTrue();
            when(scheduler.isRunning()).thenReturn(false);
        }
    }

    private ProjectSourceEnvironment enabled() {
        return new ProjectSourceEnvironment(new MockEnvironment().withProperty("atoms.source.enabled", "true")
                .withProperty("atoms.source.root", temporary.resolve("source").toString())
                .withProperty("atoms.source.work-root", temporary.resolve("work").toString()));
    }
    private ProjectSourceReadGateway gateway(ProjectSourceEnvironment environment, Supplier<SourceCoordinator> coordinator) {
        return new ProjectSourceReadGateway(null, null, environment, coordinator);
    }
}
