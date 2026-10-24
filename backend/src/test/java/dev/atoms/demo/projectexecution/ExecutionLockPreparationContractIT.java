package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** Opt-in real npm/source publication contract; preparation task scheduling/settlement is tested separately. */
@Tag("project-execution-contract")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionLockPreparationContractIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired DataSource database;

    @Test void realNpmCreatesOnlyALockPublishesANewVersionAndThenMakesNoChanges() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo("atoms_demo_test.atoms_verify_20261021_ex10");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE status IN ('PREPARING','CANDIDATE_READY','NEEDS_RECONCILIATION')", Integer.class)).isZero();
        var locations = new ExecutionToolchain.Locations(required("execution.contract-node"), required("execution.contract-npm"),
                required("execution.contract-java"), required("execution.contract-maven"));
        Path parent = required("execution.contract-directory").toRealPath();
        Path root = Files.createTempDirectory(parent, "lock-preparation-", PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------"))).toRealPath();
        var json = new ObjectMapper();
        var approval = (ObjectNode) json.readTree(Files.readAllBytes(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json")));
        ((ObjectNode) approval.path("npm")).remove("lockSha256");
        Path policyFile = root.resolve("approval.json");
        Files.write(policyFile, json.writeValueAsBytes(approval));
        Files.setPosixFilePermissions(policyFile, PosixFilePermissions.fromString("rw-------"));
        var files = new ArrayList<SourceTree.File>();
        for (String name : Files.readAllLines(Path.of("../templates/full-project-v1.files")))
            if (!name.equals("frontend/package-lock.json")) files.add(new SourceTree.File(name,
                    Files.readAllBytes(Path.of("../templates/full-project-v1").resolve(name)), name.equals("backend/mvnw") ? 0100755 : 0100644));
        SourceTree original = new ProjectSourcePolicy().validate(files);
        var protectedRoots = List.of(root.resolve("source"), root.resolve("work"));
        UUID owner = UUID.randomUUID(); jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        try (var source = new SourceAgentFixture(jdbc, projects, transactions, root);
             var paths = new ExecutionPaths(root.resolve("execution"), protectedRoots);
             var coordination = new ExecutionCoordinatorLease(database)) {
            assertThat(coordination.healthy()).isTrue();
            source.start(database);
            var base = source.accept(owner, source.create(owner), original);
            var saved = prepare(source, paths, coordination, locations, policyFile, owner, base, true, protectedRoots);
            var tree = source.snapshot(owner, saved.projectId(), saved.versionId());
            assertThat(source.snapshot(owner, base.projectId(), base.versionId()).sha256()).isEqualTo(original.sha256());
            assertThat(source.changedPaths(owner, base.projectId(), base.versionId(), saved.versionId()))
                    .containsOnlyKeys("frontend/package-lock.json");
            var preparationOnly = new ExecutionNpmPolicy(json.writeValueAsBytes(approval));
            assertThatThrownBy(() -> preparationOnly.checkBuild(tree)).hasMessage("EXECUTION_NPM_APPROVAL_REQUIRED");
            // Explicit fixture-maintainer approval outside source; a previous preparation never grants this automatically.
            var prepared = preparationOnly.checkPrepared(original, tree);
            ((ObjectNode) approval.path("npm")).put("lockSha256", prepared.lockSha256());
            Files.write(policyFile, json.writeValueAsBytes(approval));
            assertThat(prepare(source, paths, coordination, locations, policyFile, owner, saved, false, protectedRoots)).isEqualTo(saved);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, saved.projectId())).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_edit_leases WHERE project_id=? AND (status<>'RELEASED' OR execution_pending)", Integer.class, saved.projectId())).isZero();
            System.out.printf("lock_preparation_acceptance project=%s base=%s result=%s lockSha256=%s packages=%d root=%s%n",
                    base.projectId(), base.versionId(), saved.versionId(), prepared.lockSha256(), prepared.packages(), root);
        }
    }

    private SourceAgentFixture.Base prepare(SourceAgentFixture source, ExecutionPaths paths, ExecutionCoordinatorLease coordination,
            ExecutionToolchain.Locations locations, Path policyFile, UUID owner, SourceAgentFixture.Base base, boolean changed,
            List<Path> protectedRoots) throws Exception {
        var budget = new ExecutionProcess.Deadline(Duration.ofMinutes(5));
        var snapshot = source.snapshot(owner, base.projectId(), base.versionId());
        var excluded = new ArrayList<>(protectedRoots); excluded.add(paths.checkedRoot());
        var policy = ExecutionApprovedPolicy.capture(policyFile, excluded, budget);
        policy.npm().checkPreparation(snapshot);
        var tools = ExecutionToolchain.capture(locations, excluded, budget);
        UUID task = UUID.randomUUID(), execution = UUID.randomUUID();
        var edit = source.gateway().claimEdit(owner, base.projectId(), base.versionId(), base.revision(), task);
        boolean pending = false;
        try {
            source.gateway().prepareEdit(edit, budget::remaining);
            Path work = source.gateway().workPath(edit);
            var home = ExecutionToolHome.create(paths, paths.createBuild(task, budget), budget);
            var invocation = tools.lockPreparation(home, work);
            assertThat(source.gateway().readEdit(edit).sha256()).isEqualTo(snapshot.sha256());
            source.gateway().markExecutionStarted(edit, execution); pending = true;
            try {
                var result = new ExecutionProcess().run(invocation, budget, () -> false, task, execution, () -> {
                    if (!coordination.healthy()) throw new java.io.IOException("EXECUTION_STATE_UNAVAILABLE");
                    assertThat(source.gateway().workPath(edit)).isEqualTo(work);
                    assertThat(source.gateway().readEdit(edit).sha256()).isEqualTo(snapshot.sha256());
                    home.verify(budget); tools.verify(budget); policy.verify(budget);
                });
                assertThat(result.stopped().matches(task, execution, work)).isTrue();
                source.gateway().confirmExecutionStopped(edit, result.stopped()); pending = false;
            } catch (OwnedProcess.Failure error) {
                if (error.stopped() != null) { source.gateway().confirmExecutionStopped(edit, error.stopped()); pending = false; }
                throw error;
            }
            home.verify(budget); tools.verify(budget); policy.verify(budget);
            assertThat(work.resolve("node_modules")).doesNotExist();
            assertThat(work.resolve("frontend/node_modules")).doesNotExist();
            var accepted = source.gateway().sealAndAccept(edit, "lock-only-" + task, candidate -> {
                try { assertThat(policy.npm().checkPrepared(snapshot, candidate).changed()).isEqualTo(changed); }
                catch (java.io.IOException error) { throw new AssertionError(error.getMessage()); }
            });
            if (!changed) {
                assertThat(accepted).isInstanceOf(ProjectSourceEditGateway.NoChanges.class);
                assertThat(source.gateway().operationFact(edit)).isNull();
                return base;
            }
            var submitted = (ProjectSourceEditGateway.Submitted) accepted;
            var saved = submitted.completion().get(budget.remaining().toNanos(), TimeUnit.NANOSECONDS);
            assertThat(saved.status()).isEqualTo("COMMITTED");
            return new SourceAgentFixture.Base(base.projectId(), saved.resultVersionId(), base.revision() + 1);
        } finally {
            var operation = source.gateway().operationFact(edit);
            if (!pending && (operation == null || operation.terminal())) source.gateway().releaseEdit(edit);
        }
    }

    private static Path required(String key) {
        String raw = System.getProperty(key);
        assertThat(raw).as("Explicit %s required", key).isNotBlank();
        Path path = Path.of(raw);
        assertThat(path.isAbsolute() && path.normalize().equals(path)).isTrue();
        return path;
    }
}
