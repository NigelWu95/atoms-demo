package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.jar.JarFile;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Opt-in real tools/network contract. No fake runner, model call, shell, app migration or listening service. */
@Tag("project-execution-contract")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false",
        "atoms.generation.worker-enabled=false"})
class ExecutionSeedBuildContractIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource database;

    @Test void originalSeedBuildsWithRealToolsAndBrokenVersionsCannotReplaceIt() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
        // Source startup must not reconcile another test's retained evidence in a new temporary root.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE status IN ('PREPARING','CANDIDATE_READY','NEEDS_RECONCILIATION')", Integer.class))
                .as("No unrelated source startup recovery in this native contract").isZero();
        // Missing environment fails before allocating a task; never silently skip an explicit contract.
        var config = new MockEnvironment();
        for (String kind : List.of("node", "npm", "java", "maven"))
            config.withProperty("atoms.project-execution." + kind, requiredPath("execution.contract-" + kind).toString());
        Path databaseConfig = requiredPath("execution.test-config-directory");
        Path parent = requiredPath("execution.contract-directory").toRealPath();
        assertThat(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)).isTrue();
        Path root = Files.createTempDirectory(parent, "seed-build-", PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------"))).toRealPath();
        UUID owner = UUID.randomUUID();
        List<Path> protectedRoots = List.of(root.resolve("source"), root.resolve("work"));
        // Preserve real outputs and failures; there is no automatic recursive deletion or unknown-task settlement.
        try (var source = new SourceAgentFixture(jdbc, projects, transactions, root)) {
            source.start(database);
            jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
            var seed = originalSeed();
            var base = source.accept(owner, source.create(owner), seed);
            Path approval = root.resolve("approval.json");
            Files.copy(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json"), approval);
            Files.setPosixFilePermissions(approval, PosixFilePermissions.fromString("rw-------"));
            config.withProperty("atoms.project-execution.enabled", "true")
                    .withProperty("atoms.project-execution.maintainer-ids", owner.toString())
                    .withProperty("atoms.project-execution.root", root.resolve("execution").toString())
                    .withProperty("atoms.project-execution.policy", approval.toString())
                    .withProperty("atoms.project-execution.test-databases", new ObjectMapper().writeValueAsString(Map.of(
                            base.projectId().toString(), databaseConfig.resolve("atoms_exec_a_test_20261020.properties").toString())));
            var databases = ExecutionTestDatabase.load(Map.of(base.projectId(), databaseConfig.resolve("atoms_exec_a_test_20261020.properties")),
                    protectedRoots, Set.of("atoms_demo", "atoms_demo_test"), Set.of("atoms", "atoms_test"));
            var before = databases.verify(base.projectId(), new ExecutionProcess.Deadline(Duration.ofSeconds(30)));
            try (var runtime = runtime(config, source)) {
                var service = runtime.getBean(ProjectExecutionService.class);
                var coordinator = runtime.getBean(ExecutionCoordinator.class);
                var success = build(owner, base, seed, service, coordinator);
                assertThat(success.status()).as("real original-seed build: %s", success.errorCode()).isEqualTo("SUCCEEDED");
                Path artifact = root.resolve("execution/artifacts/" + success.id());
                var manifest = new ObjectMapper().readTree(Files.readAllBytes(artifact.resolve("manifest.json")));
                assertThat(manifest.path("sourceVersionId").asText()).isEqualTo(base.versionId().toString());
                assertThat(manifest.path("treeSha256").asText()).isEqualTo(seed.sha256());
                assertThat(manifest.at("/evidence/frontend/tests").asInt()).isPositive();
                // Four health/identity checks and four private test-configuration checks.
                assertThat(manifest.at("/evidence/javaTests/tests").asInt()).isEqualTo(8);
                String digest = sha256(artifact.resolve("app.jar"));
                assertThat(manifest.path("jarSha256").asText()).isEqualTo(digest);
                assertThat(jdbc.queryForObject("SELECT jar_sha256 FROM execution_builds WHERE id=?", String.class, success.id())).isEqualTo(digest);
                try (var jar = new JarFile(artifact.resolve("app.jar").toFile())) {
                    assertThat(jar.getManifest().getMainAttributes().getValue("Main-Class")).contains("JarLauncher");
                    assertThat(jar.getEntry("BOOT-INF/classes/dev/atoms/generated/HealthController.class")).isNotNull();
                    assertThat(jar.getEntry("BOOT-INF/classes/db/migration/V001__application_metadata.sql")).isNotNull();
                    var index = jar.getEntry("BOOT-INF/classes/static/index.html");
                    assertThat(index).isNotNull();
                    try (var input = jar.getInputStream(index)) {
                        assertThat(input.readNBytes(1 << 20)).isEqualTo(Files.readAllBytes(
                                root.resolve("execution/builds/" + success.id() + "/project/frontend/dist/index.html")));
                    }
                    assertThat(jar.stream().map(java.util.zip.ZipEntry::getName).toList())
                            .noneMatch(name -> name.contains(".env") || name.contains("test-results") || name.contains("surefire-reports"));
                }
                assertThat(source.snapshot(owner, base.projectId(), base.versionId()).sha256()).isEqualTo(seed.sha256());
                var saved = jdbc.queryForMap("SELECT * FROM execution_builds WHERE id=?", success.id());
                for (String failure : List.of("frontend", "java")) {
                    var broken = brokenSeed(seed, failure);
                    base = source.accept(owner, base, broken);
                    var rejected = build(owner, base, broken, service, coordinator);
                    assertThat(rejected.status()).as("real %s failure: %s", failure, rejected.errorCode()).isEqualTo("FAILED");
                    assertThat(rejected.errorCode()).isEqualTo("EXECUTION_PROCESS_FAILED");
                    assertThat(jdbc.queryForObject("SELECT payload->>'operation' FROM execution_build_events WHERE build_id=? AND payload->>'operation' IS NOT NULL ORDER BY seq DESC LIMIT 1",
                            String.class, rejected.id()))
                            .isEqualTo(failure.equals("frontend") ? "UNIT_TEST" : "MAVEN_PACKAGE");
                    assertThat(root.resolve("execution/artifacts/" + rejected.id())).doesNotExist();
                    assertThat(jdbc.queryForMap("SELECT * FROM execution_builds WHERE id=?", success.id())).isEqualTo(saved);
                    assertThat(sha256(artifact.resolve("app.jar"))).isEqualTo(digest);
                }
                assertThat(databases.verify(base.projectId(), new ExecutionProcess.Deadline(Duration.ofSeconds(30)))).isEqualTo(before);
                System.out.printf("execution_seed_acceptance build=%s frontendTests=%s javaTests=%s jarSha256=%s%n",
                        success.id(), manifest.at("/evidence/frontend/tests").asInt(), manifest.at("/evidence/javaTests/tests").asInt(), digest);
            }
            // Context shutdown joins workers before releasing filesystem and database coordination.
            try (var released = new ExecutionPaths(root.resolve("execution"), protectedRoots);
                 var lease = new ExecutionCoordinatorLease(database)) {
                assertThat(released.checkedRoot()).isEqualTo(root.resolve("execution"));
                assertThat(lease.healthy()).isTrue();
            }
        }
    }

    private AnnotationConfigApplicationContext runtime(MockEnvironment config, SourceAgentFixture source) {
        var context = new AnnotationConfigApplicationContext();
        try {
            context.setEnvironment(config);
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(PlatformTransactionManager.class, () -> transactions);
            context.registerBean(ProjectService.class, () -> projects);
            // The enclosing test context owns the pool; the execution context must not destroy it.
            context.registerBean(DataSource.class, () -> database, definition -> definition.setDestroyMethodName(""));
            context.registerBean(ProjectSourceReadGateway.class, source::readGateway);
            context.registerBean(ProjectSourceEditGateway.class, source::gateway);
            context.registerBean(DependencyPreparationService.class, () -> new DependencyPreparationService(
                    context.getBean(ExecutionSettings.class), context.getBean(DependencyPreparationRepository.class), projects,
                    context.getBean(ProjectSourceReadGateway.class), source.gateway(), source.environment(), config, transactions));
            // Keep the fixture-owned source environment (including its @PreDestroy) outside this child context.
            context.registerBean(ExecutionEnvironment.class, () -> new ExecutionEnvironment(context.getBean(ExecutionSettings.class),
                    config, context.getBean(ProjectSourceReadGateway.class), source.environment(), projects, jdbc, transactions,
                    source.gateway(), context.getBean(DependencyPreparationService.class)));
            context.register(ExecutionSettings.class, ExecutionBuildRepository.class,
                    ProjectExecutionService.class, DependencyPreparationRepository.class, ExecutionCoordinator.class);
            context.refresh();
            assertThat(context.getBean(ExecutionCoordinator.class).isRunning()).isTrue();
            return context;
        } catch (RuntimeException | Error error) { context.close(); throw error; }
    }

    private ExecutionBuild build(UUID owner, SourceAgentFixture.Base base, SourceTree tree, ProjectExecutionService service,
            ExecutionCoordinator coordinator) throws Exception {
        var build = service.submit(owner, base.projectId(), base.versionId(), UUID.randomUUID().toString());
        assertThat(build.treeSha256()).isEqualTo(tree.sha256());
        long start = System.nanoTime();
        System.out.printf("execution_seed_started build=%s source=%s%n", build.id(), base.versionId());
        var repository = new ExecutionBuildRepository(jdbc);
        var result = repository.get(base.projectId(), build.id());
        while (result.finishedAt() == null && coordinator.isRunning() && System.nanoTime() - start < Duration.ofSeconds(1830).toNanos()) {
            Thread.sleep(250);
            result = repository.get(base.projectId(), build.id());
        }
        assertThat(result.finishedAt()).as("Native dispatcher must settle the build: %s", result.status()).isNotNull();
        System.out.printf("execution_seed_finished build=%s status=%s code=%s elapsedMillis=%d%n", result.id(), result.status(),
                result.errorCode(), (System.nanoTime() - start) / 1_000_000);
        assertThat(jdbc.queryForObject("SELECT execution_pending FROM execution_builds WHERE id=?", Boolean.class, build.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT worker_slot FROM execution_builds WHERE id=?", Integer.class, build.id())).isNull();
        return result;
    }
    private static SourceTree originalSeed() throws Exception {
        var files = new ArrayList<SourceTree.File>();
        for (String name : Files.readAllLines(Path.of("../templates/full-project-v1.files")))
            files.add(new SourceTree.File(name, Files.readAllBytes(Path.of("../templates/full-project-v1").resolve(name)),
                    name.equals("backend/mvnw") ? 0100755 : 0100644));
        assertThat(files).hasSize(37);
        return new ProjectSourcePolicy().validate(files);
    }
    private static SourceTree brokenSeed(SourceTree seed, String failure) {
        var files = new ArrayList<>(seed.files());
        String path = failure.equals("frontend") ? "frontend/src/acceptance-failure.spec.ts"
                : "backend/src/main/java/dev/atoms/generated/AcceptanceFailure.java";
        String content = failure.equals("frontend") ? "import { expect, it } from 'vitest'\nit('rejects broken app', () => expect(1).toBe(2))\n"
                : "package dev.atoms.generated; public class AcceptanceFailure { MissingType missing; }\n";
        files.add(new SourceTree.File(path, content.getBytes(StandardCharsets.UTF_8), 0100644));
        return new ProjectSourcePolicy().validate(files);
    }
    private static Path requiredPath(String key) {
        String raw = System.getProperty(key);
        assertThat(raw).as("Explicit %s required", key).isNotBlank();
        Path path = Path.of(raw);
        assertThat(path.isAbsolute() && path.normalize().equals(path)).as("Absolute normalized %s required", key).isTrue();
        return path;
    }
    private static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = new byte[65536]; int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
