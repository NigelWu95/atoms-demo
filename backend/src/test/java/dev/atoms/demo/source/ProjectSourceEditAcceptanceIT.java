package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceEditAcceptanceIT extends SourceIntegrationSupport {
    @Autowired DataSource database;
    private UUID base;
    @BeforeEach void baseline() {
        var tree = new ProjectSourcePolicy().validate(List.of(file("README.md", "one")));
        base = new SourceWorker(service, repository, environment).execute(
                new SourceInitializer(service).request(owner, project, tree, "base"), tree).resultVersionId();
    }

    @Test void boundStoppedEvidencePublishesExactlyOnceUnderConcurrentRetries() throws Exception {
        try (var coordinator = new SourceCoordinator(service, repository, environment, database)) {
            coordinator.start();
            var gateway = gateway(coordinator);
            var handle = gateway.claimEdit(owner, project, base, 1, UUID.randomUUID());
            gateway.prepareEdit(handle);
            Path work = gateway.workPath(handle);
            Files.writeString(work.resolve("README.md"), "two");
            var execution = UUID.randomUUID(); gateway.markExecutionStarted(handle, execution);
            assertThatThrownBy(() -> gateway.sealAndAccept(handle, "accept")).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> gateway.releaseEdit(handle)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> gateway.confirmExecutionStopped(handle, stopped(UUID.randomUUID(), execution, work)))
                    .isInstanceOf(ApiException.class);
            var proof = stopped(handle.taskId(), execution, work);
            gateway.confirmExecutionStopped(handle, proof); gateway.confirmExecutionStopped(handle, proof);
            var threads = Executors.newFixedThreadPool(2);
            try {
                var a = threads.submit(() -> gateway.sealAndAccept(handle, "accept"));
                var b = threads.submit(() -> gateway.sealAndAccept(handle, "accept"));
                var first = (ProjectSourceEditGateway.Submitted) a.get(30, TimeUnit.SECONDS);
                var second = (ProjectSourceEditGateway.Submitted) b.get(30, TimeUnit.SECONDS);
                assertThat(first.operation().id()).isEqualTo(second.operation().id());
                var saved = first.completion().get(30, TimeUnit.SECONDS);
                assertThat(saved.status()).isEqualTo("COMMITTED");
                assertThat(((ProjectSourceEditGateway.Submitted) gateway.sealAndAccept(handle, "accept")).operation()).isEqualTo(saved);
                assertThatThrownBy(() -> gateway.sealAndAccept(handle, "another-key")).isInstanceOf(ApiException.class);
                assertThat(repository.state(project).revision()).isEqualTo(2);
                gateway.releaseEdit(handle);
                assertThat(work).isDirectory();
                assertThat(new SourceQuery(service, repository, environment).file(owner, project, saved.resultVersionId(), "README.md").content())
                        .isEqualTo("two".getBytes());
            } finally { threads.shutdownNow(); }
        }
    }

    @Test void noChangesDoesNotCreateAnotherSourceVersion() throws Exception {
        var gateway = gateway(null); var handle = gateway.claimEdit(owner, project, base, 1, UUID.randomUUID());
        gateway.prepareEdit(handle); var work = gateway.workPath(handle); var execution = UUID.randomUUID();
        gateway.markExecutionStarted(handle, execution);
        gateway.confirmExecutionStopped(handle, stopped(handle.taskId(), execution, work));
        assertThat(gateway.sealAndAccept(handle, "same")).isInstanceOf(ProjectSourceEditGateway.NoChanges.class);
        assertThat(gateway.sealAndAccept(handle, "same")).isInstanceOf(ProjectSourceEditGateway.NoChanges.class);
        assertThat(repository.state(project).revision()).isEqualTo(1);
        gateway.releaseEdit(handle);
    }

    @Test void expiredDeadlineStillAllowsStopEvidenceAndSafeReleaseButNotAcceptance() throws Exception {
        var gateway = gateway(null); var handle = gateway.claimEdit(owner, project, base, 1, UUID.randomUUID());
        var expired = new AtomicBoolean();
        gateway.prepareEdit(handle, () -> expired.get() ? Duration.ZERO : Duration.ofMinutes(1));
        var work = gateway.workPath(handle); var execution = UUID.randomUUID();
        gateway.markExecutionStarted(handle, execution); var proof = stopped(handle.taskId(), execution, work);
        expired.set(true);
        gateway.confirmExecutionStopped(handle, proof);
        assertThatThrownBy(() -> gateway.sealAndAccept(handle, "expired")).isInstanceOf(ApiException.class);
        gateway.releaseEdit(handle);
        assertThat(repository.state(project).versionId()).isEqualTo(base);
    }

    @Test void rolledBackExecutionIntentDoesNotPoisonTheHandleOrAuthorizeAReceipt() throws Exception {
        var gateway = gateway(null); var handle = gateway.claimEdit(owner, project, base, 1, UUID.randomUUID());
        gateway.prepareEdit(handle); var work = gateway.workPath(handle); var execution = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            gateway.markExecutionStarted(handle, execution); status.setRollbackOnly();
        });
        assertThatThrownBy(() -> gateway.confirmExecutionStopped(handle, stopped(handle.taskId(), execution, work)))
                .isInstanceOf(ApiException.class);
        gateway.markExecutionStarted(handle, execution);
        assertThatThrownBy(() -> gateway.markExecutionStarted(handle, UUID.randomUUID())).isInstanceOf(ApiException.class);
        gateway.confirmExecutionStopped(handle, stopped(handle.taskId(), execution, work));
        gateway.releaseEdit(handle);
    }

    private ProjectSourceEditGateway gateway(SourceCoordinator coordinator) {
        return new ProjectSourceEditGateway(service, repository, environment, transactions,
                (java.util.function.Supplier<SourceCoordinator>) () -> coordinator);
    }
    private OwnedProcess.Stopped stopped(UUID task, UUID execution, Path work) throws Exception {
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                Path.of("target/test-classes").toAbsolutePath().toString(), "dev.atoms.demo.agent.AgentChildFixture", "success")
                .directory(work.toFile());
        command.environment().clear();
        return new OwnedProcess().run(command, "fixture", () -> Duration.ofSeconds(10), () -> false,
                task, execution, stream -> stream.readNBytes(2048)).stopped();
    }
}
