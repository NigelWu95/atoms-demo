package dev.atoms.demo.source;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceLifecycleIT extends SourceIntegrationSupport {
    @Autowired DataSource dataSource;
    @Autowired Environment configuration;

    @Test void singleWorkerPublishesAndClosePermanentlyStopsAdmission() throws Exception {
        var coordinator = new SourceCoordinator(service, repository, environment, dataSource);
        var gateway = new ProjectSourceEditGateway(service, repository, environment, transactions,
                (java.util.function.Supplier<SourceCoordinator>) () -> coordinator);
        assertThat(coordinator.getPhase()).isLessThan(Integer.MAX_VALUE);
        assertThat(gateway.readyForAgent()).isFalse();
        try {
            coordinator.start();
            assertThat(gateway.readyForAgent()).isTrue();
            var submitted = coordinator.initialize(owner, project, ProjectSourceInitializationIT.tree(), "worker");
            assertThat(submitted.completion().get(30, TimeUnit.SECONDS).status()).isEqualTo("COMMITTED");
            assertThat(coordinator.initialize(owner, project, ProjectSourceInitializationIT.tree(), "worker").operation().id())
                    .isEqualTo(submitted.operation().id());
        } finally { coordinator.close(); }
        assertThatThrownBy(() -> coordinator.initialize(owner, project, ProjectSourceInitializationIT.tree(), "after-close"))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class);
        assertThat(coordinator.isRunning()).isFalse();
        assertThat(gateway.readyForAgent()).isFalse();
    }

    @Test void disabledCoordinatorDoesNotAcquireStorageOrCreateAnyWorker() throws Exception {
        try (var disabled = new ProjectSourceEnvironment(new org.springframework.mock.env.MockEnvironment());
                var coordinator = new SourceCoordinator(service, repository, disabled, dataSource)) {
            coordinator.start();
            assertThat(coordinator.isRunning()).isFalse();
            assertThatThrownBy(() -> coordinator.initialize(owner, project, ProjectSourceInitializationIT.tree(), "disabled"))
                    .isInstanceOf(dev.atoms.demo.common.ApiException.class);
            assertThat(java.nio.file.Files.exists(temporary.resolve("source"))).isFalse();
        }
    }

    @Test void boundedAdmissionAndShutdownDoNotLeaveQueuedJobsOrPublishInterruptedWork() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var blocked = new java.util.concurrent.CountDownLatch(1);
        var coordinator = new SourceCoordinator(service, repository, environment, dataSource, stage -> {
            if (stage == SourceWorker.Stage.BEFORE_GIT) {
                entered.countDown();
                try { blocked.await(); }
                catch (InterruptedException error) { throw new IllegalStateException("test worker interrupted"); }
            }
        });
        try {
            coordinator.start();
            var running = coordinator.initialize(owner, project, ProjectSourceInitializationIT.tree(), "active");
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var queued = new java.util.ArrayList<SourceCoordinator.Submission>();
            for (int i = 0; i < 63; i++) queued.add(coordinator.initialize(owner, project, ProjectSourceInitializationIT.tree(), "queued-" + i));
            assertThatThrownBy(() -> coordinator.initialize(owner, project, ProjectSourceInitializationIT.tree(), "over-capacity"))
                    .isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class, e -> assertThat(e.code()).isEqualTo("SOURCE_CAPACITY_EXCEEDED"));
            coordinator.close();
            assertThat(running.completion().isDone()).isTrue();
            for (var task : queued) assertThat(task.completion().get(5, TimeUnit.SECONDS).status()).isEqualTo("FAILED");
            assertThat(repository.state(project).versionId()).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE project_id=? AND execution_pending", Integer.class, project)).isZero();
        } finally { blocked.countDown(); coordinator.close(); }
    }

    @Test void recoversAfterARealJvmExitsBetweenGitAndDatabasePublication() throws Exception {
        var intent = new SourceInitializer(service).request(owner, project, ProjectSourceInitializationIT.tree(), "real-process");
        var parent = temporary.toRealPath();
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                System.getProperty("java.class.path"), SourceProcessProbe.class.getName(), project.toString(), intent.id().toString(),
                parent.resolve("source").toString(), parent.resolve("work").toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin");
        var child = builder.start();
        try {
            // Test-only child needs only the dedicated test credential, over stdin rather than argv or full environment.
            try (var input = child.getOutputStream()) {
                input.write((configuration.getProperty("spring.datasource.password") + "\n").getBytes(StandardCharsets.UTF_8));
            }
            assertThat(child.waitFor(45, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isEqualTo(71);
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue(); }
        }
        assertThat(repository.operation(project, intent.id()).status()).isEqualTo("PREPARING");
        assertThat(repository.operation(project, intent.id()).executionPending()).isFalse();
        try (var coordinator = new SourceCoordinator(service, repository, environment, dataSource)) {
            coordinator.start();
            assertThat(repository.operation(project, intent.id()).status()).isEqualTo("FAILED");
            assertThat(repository.state(project).versionId()).isNull();
            var retry = coordinator.initialize(owner, project, ProjectSourceInitializationIT.tree(), "after-real-restart");
            assertThat(retry.completion().get(30, TimeUnit.SECONDS).status()).isEqualTo("COMMITTED");
        }
    }
}
