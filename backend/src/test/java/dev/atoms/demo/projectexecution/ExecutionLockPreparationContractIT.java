package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** Opt-in real npm through durable preparation admission, owned execution and source-result settlement. */
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
        var config = new org.springframework.mock.env.MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString())
                .withProperty("atoms.project-execution.root", paths.checkedRoot().toString())
                .withProperty("atoms.project-execution.node", locations.node().toString())
                .withProperty("atoms.project-execution.npm", locations.npm().toString())
                .withProperty("atoms.project-execution.java", locations.java().toString())
                .withProperty("atoms.project-execution.maven", locations.maven().toString())
                .withProperty("atoms.project-execution.policy", policyFile.toString());
        var settings = new ExecutionSettings(config);
        var repository = new DependencyPreparationRepository(jdbc);
        var service = new DependencyPreparationService(settings, repository, projects, source.readGateway(), source.gateway(),
                source.environment(), config, transactions);
        var task = service.submit(owner, base.projectId(), new DependencyPreparationService.Input(base.versionId(), base.revision()),
                "lock-only-" + UUID.randomUUID());
        // Whole-task contract uses the approved production default, including cold registry resolution.
        // The earlier 300-second override timed out with an actual stop and was retained as separate evidence.
        assertThat(jdbc.queryForObject("SELECT task_timeout_seconds FROM dependency_preparations WHERE id=?", Integer.class, task.id())).isEqualTo(1800);
        var result = new DependencyPreparationWorker(settings, projects, jdbc, transactions, paths, source.readGateway(),
                source.gateway(), service, protectedRoots).run(owner, base.projectId(), task.id(), () -> false, () -> {
                    if (!coordination.healthy()) throw new java.io.IOException("EXECUTION_STATE_UNAVAILABLE");
                });
        assertThat(result.status()).isEqualTo(changed ? "SOURCE_SAVED" : "NO_CHANGES");
        assertThat(service.handle(task.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE task_id=?", Integer.class, task.id())).isZero();
        var row = jdbc.queryForMap("SELECT source_operation_id,result_version_id FROM dependency_preparations WHERE id=?", task.id());
        System.out.printf("lock_preparation_task id=%s status=%s sourceOperation=%s result=%s%n", task.id(), result.status(),
                row.get("source_operation_id"), row.get("result_version_id"));
        if (!changed) {
            assertThat(row).containsEntry("source_operation_id", null).containsEntry("result_version_id", null);
            return base;
        }
        assertThat(row.get("source_operation_id")).isNotNull();
        return new SourceAgentFixture.Base(base.projectId(), (UUID) row.get("result_version_id"), base.revision() + 1);
    }

    private static Path required(String key) {
        String raw = System.getProperty(key);
        assertThat(raw).as("Explicit %s required", key).isNotBlank();
        Path path = Path.of(raw);
        assertThat(path.isAbsolute() && path.normalize().equals(path)).isTrue();
        return path;
    }
}
