package dev.atoms.demo.source;

import dev.atoms.demo.execution.OwnedProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceAgentCoexistenceIT extends SourceIntegrationSupport {
    @Autowired DataSource database;

    @ParameterizedTest @EnumSource(value=SourceWorker.Stage.class, names={"BEFORE_GIT","AFTER_GIT","BEFORE_PUBLISH","AFTER_COMMIT"})
    void recoveryAndDuplicateAcceptanceAgreeOnTheOnlyDurablePublication(SourceWorker.Stage crash) throws Exception {
        var tree = new ProjectSourcePolicy().validate(List.of(file("README.md", "base")));
        var base = new SourceWorker(service, repository, environment).execute(
                new SourceInitializer(service).request(owner, project, tree, "base"), tree).resultVersionId();
        ProjectSourceEditGateway gateway;
        ProjectSourceEditGateway.EditHandle edit;
        ProjectSourceEditGateway.Submitted accepted;
        try (var coordinator = new SourceCoordinator(service, repository, environment, database,
                stage -> { if (stage == crash) throw new Crash(); })) {
            coordinator.start();
            gateway = new ProjectSourceEditGateway(service, repository, environment, transactions,
                    (java.util.function.Supplier<SourceCoordinator>) () -> coordinator);
            edit = gateway.claimEdit(owner, project, base, 1, UUID.randomUUID());
            gateway.prepareEdit(edit);
            Path work = gateway.workPath(edit);
            Files.writeString(work.resolve("README.md"), "edited");
            UUID execution = UUID.randomUUID();
            var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                    Path.of("target/test-classes").toAbsolutePath().toString(), "dev.atoms.demo.agent.AgentChildFixture", "success")
                    .directory(work.toFile());
            command.environment().clear();
            gateway.markExecutionStarted(edit, execution);
            var stopped = new OwnedProcess().run(command, "fixture", () -> Duration.ofSeconds(10), () -> false,
                    edit.taskId(), execution, stream -> stream.readNBytes(2048)).stopped();
            gateway.confirmExecutionStopped(edit, stopped);
            accepted = (ProjectSourceEditGateway.Submitted) gateway.sealAndAccept(edit, "edit");
            assertThatThrownBy(() -> accepted.completion().get(20, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
        }
        environment.close();
        environment = new ProjectSourceEnvironment(sourceConfiguration);
        service = new ProjectSourceService(projects, repository, transactions, environment);
        var recovery = new SourceRecovery(service, repository, environment);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var recovered = threads.submit(() -> recovery.reconcile(project, accepted.operation().id()));
            var duplicate = threads.submit(() -> gateway.sealAndAccept(edit, "edit"));
            var fact = recovered.get(20, TimeUnit.SECONDS);
            assertThat(((ProjectSourceEditGateway.Submitted) duplicate.get(20, TimeUnit.SECONDS)).operation().id()).isEqualTo(fact.id());
            assertThat(fact.status()).isEqualTo(crash == SourceWorker.Stage.AFTER_COMMIT ? "COMMITTED" : "FAILED");
            assertThat(recovery.reconcile(project, fact.id())).isEqualTo(fact);
            var current = repository.state(project);
            assertThat(current.versionId()).isEqualTo(crash == SourceWorker.Stage.AFTER_COMMIT ? fact.resultVersionId() : base);
            assertThat(current.revision()).isEqualTo(crash == SourceWorker.Stage.AFTER_COMMIT ? 2 : 1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE edit_lease_id=?", Integer.class, edit.leaseId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, project))
                    .isEqualTo(crash == SourceWorker.Stage.AFTER_COMMIT ? 2 : 1);
            var reopenedGateway = new ProjectSourceEditGateway(service, repository, environment, transactions);
            var settlement = reopenedGateway.reconcileAbandonedEdit(project, edit.leaseId(), edit.taskId());
            assertThat(settlement.uncertain()).isFalse();
            assertThat(settlement.operation()).isEqualTo(fact);
            assertThat(jdbc.queryForObject("SELECT status FROM source_edit_leases WHERE id=?", String.class, edit.leaseId())).isEqualTo("RELEASED");
            var visible = new SourceQuery(service, repository, environment).file(owner, project, current.versionId(), "README.md");
            assertThat(visible.content()).isEqualTo((crash == SourceWorker.Stage.AFTER_COMMIT ? "edited" : "base").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            threads.shutdownNow(); assertThat(threads.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static final class Crash extends Error {}
}
