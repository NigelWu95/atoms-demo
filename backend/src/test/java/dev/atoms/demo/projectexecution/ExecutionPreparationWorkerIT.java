package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Real Node parent/child and Git/PG; controlled CLI contents, not registry resolution or a real npm installation. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionPreparationWorkerIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID();
    SourceAgentFixture source;
    ExecutionPaths paths;
    MockEnvironment config;
    DependencyPreparationRepository repository;
    DependencyPreparationService service;
    Path policy;
    ExecutionToolchain.Locations locations;

    @BeforeEach void fixture() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo("atoms_demo_test.atoms_verify_20261021_ex10");
        Path root = temporary.toRealPath();
        source = new SourceAgentFixture(jdbc, projects, transactions, root);
        source.start(Objects.requireNonNull(jdbc.getDataSource()));
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        locations = ExecutionToolchainTest.installation(root.resolve("tools"));
        Path node = Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator)).map(p -> Path.of(p).resolve("node"))
                .filter(Files::isExecutable).findFirst().orElseThrow().toRealPath();
        Files.delete(locations.node()); Files.createSymbolicLink(locations.node(), node);
        Files.copy(Path.of("../templates/full-project-v1/frontend/package-lock.json"), locations.npm().toRealPath().resolveSibling("fixture-lock.json"));
        cli("");
        policy = root.resolve("policy.json");
        var json = new ObjectMapper();
        var approval = (ObjectNode) json.readTree(Files.readAllBytes(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json")));
        ((ObjectNode) approval.path("npm")).remove("lockSha256");
        Files.write(policy, json.writeValueAsBytes(approval));
        Files.setPosixFilePermissions(policy, PosixFilePermissions.fromString("rw-------"));
        config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString())
                .withProperty("atoms.project-execution.root", root.resolve("execution").toString())
                .withProperty("atoms.project-execution.node", locations.node().toString())
                .withProperty("atoms.project-execution.npm", locations.npm().toString())
                .withProperty("atoms.project-execution.java", locations.java().toString())
                .withProperty("atoms.project-execution.maven", locations.maven().toString())
                .withProperty("atoms.project-execution.policy", policy.toString());
        repository = new DependencyPreparationRepository(jdbc);
        service = new DependencyPreparationService(new ExecutionSettings(config), repository, projects,
                source.readGateway(), source.gateway(), source.environment(), config, transactions);
        paths = new ExecutionPaths(root.resolve("execution"), protectedRoots());
    }

    @AfterEach void close() throws Exception {
        if (paths != null) paths.close();
        if (source != null) source.close();
    }

    @Test void wholeWorkerPublishesOnlyTheLockAndSecondTaskHasNoChanges() throws Exception {
        var base = base(); var task = submit(base);
        assertThat(run(task, () -> false).status()).isEqualTo("SOURCE_SAVED");
        UUID saved = jdbc.queryForObject("SELECT result_version_id FROM dependency_preparations WHERE id=?", UUID.class, task.id());
        assertThat(source.changedPaths(owner, base.projectId(), base.versionId(), saved)).containsOnlyKeys("frontend/package-lock.json");
        assertThatThrownBy(() -> new ExecutionNpmPolicy(Files.readAllBytes(policy)).checkBuild(source.snapshot(owner, base.projectId(), saved)))
                .hasMessage("EXECUTION_NPM_APPROVAL_REQUIRED");
        assertReleased(task);
        Files.write(policy, Files.readAllBytes(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json")));
        var again = submit(new SourceAgentFixture.Base(base.projectId(), saved, base.revision() + 1));
        assertThat(run(again, () -> false).status()).isEqualTo("NO_CHANGES");
        assertReleased(again);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(3);
    }

    @Test void changingAnyOtherSourceFileFailsWithoutPublishingOrReplacingBaseline() throws Exception {
        cli("fs.writeFileSync('README.md','unapproved change');");
        var base = base(); var task = submit(base);
        assertThat(run(task, () -> false).status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM dependency_preparations WHERE id=?", String.class, task.id()))
                .isEqualTo("EXECUTION_LOCK_CHANGES_REJECTED");
        assertThat(jdbc.queryForObject("SELECT current_source_version_id FROM project_sources WHERE project_id=?", UUID.class, base.projectId())).isEqualTo(base.versionId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(2);
        assertReleased(task);
    }

    @Test void approvalDriftAfterAdmissionIsRejectedBeforeStartingAnyProcess() throws Exception {
        var task = submit(base());
        Files.writeString(policy, Files.readString(policy) + "\n");
        assertThat(run(task, () -> false).status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT execution_id FROM source_edit_leases WHERE id=?", UUID.class, task.editLeaseId())).isNull();
        assertThat(jdbc.queryForObject("SELECT error_code FROM dependency_preparations WHERE id=?", String.class, task.id()))
                .isEqualTo("EXECUTION_DEPENDENCY_POLICY_INVALID");
        assertReleased(task);
    }

    @Test void cancellationDuringRealChildExecutionStopsBeforeReleasingTheEdit() throws Exception {
        Path marker = temporary.toRealPath().resolve("child-started");
        cli("fs.writeFileSync(" + new ObjectMapper().writeValueAsString(marker.toString()) + ",'yes'); setInterval(()=>{},1000);");
        var task = submit(base());
        assertThat(run(task, () -> Files.exists(marker)).status()).isEqualTo("CANCELLED");
        assertThat(marker).exists();
        assertThat(jdbc.queryForObject("SELECT stopped_sha256 FROM source_edit_leases WHERE id=?", String.class, task.editLeaseId())).matches("[a-f0-9]{64}");
        assertReleased(task);
    }

    private void cli(String extra) throws Exception {
        Files.writeString(locations.npm().toRealPath(), """
                const fs=require('node:fs');
                if(process.argv.includes('--version')) console.log('11.19.0');
                else {
                  const frontend=process.argv[process.argv.indexOf('--prefix')+1];
                  fs.copyFileSync(require('node:path').join(__dirname,'fixture-lock.json'),frontend+'/package-lock.json');
                """ + extra + "\n}");
    }
    private List<Path> protectedRoots() throws Exception {
        return List.of(temporary.toRealPath().resolve("source"), temporary.toRealPath().resolve("work"));
    }
    private SourceAgentFixture.Base base() throws Exception {
        var initial = source.create(owner); var files = new ArrayList<SourceTree.File>();
        for (String name : Files.readAllLines(Path.of("../templates/full-project-v1.files")))
            if (!name.equals("frontend/package-lock.json")) files.add(new SourceTree.File(name,
                    Files.readAllBytes(Path.of("../templates/full-project-v1").resolve(name)), name.equals("backend/mvnw") ? 0100755 : 0100644));
        return source.accept(owner, initial, new ProjectSourcePolicy().validate(files));
    }
    private DependencyPreparationRepository.Task submit(SourceAgentFixture.Base base) {
        var task = service.submit(owner, base.projectId(), new DependencyPreparationService.Input(base.versionId(), base.revision()), UUID.randomUUID().toString());
        return repository.get(base.projectId(), task.id());
    }
    private DependencyPreparationRepository.Task run(DependencyPreparationRepository.Task task, BooleanSupplier cancelled) throws Exception {
        return new DependencyPreparationWorker(new ExecutionSettings(config), projects, jdbc, transactions, paths,
                source.readGateway(), source.gateway(), service, protectedRoots()).run(owner, task.projectId(), task.id(), cancelled, () -> {});
    }
    private void assertReleased(DependencyPreparationRepository.Task task) {
        assertThat(service.handle(task.id())).isNull();
        assertThat(jdbc.queryForMap("SELECT status,execution_pending FROM source_edit_leases WHERE id=?", task.editLeaseId()))
                .containsEntry("status", "RELEASED").containsEntry("execution_pending", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE task_id=?", Integer.class, task.id())).isZero();
    }
}
