package dev.atoms.demo.projectexecution;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Approved local runtime DBs are read-only. Two stable metadata bindings intentionally survive repeat runs. */
@Tag("project-execution-contract")
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionBindingContractIT {
    static final UUID A = UUID.fromString("f7a7917c-7b41-4fd6-b224-25aeeaa9e579"), B = UUID.fromString("cdcd56cb-98a1-4ef4-aac9-180589576a91");
    static final UUID OWNER = UUID.fromString("f0818b24-e2e7-4a67-bf5d-248eae92fa92");
    static final String A_DB = "atoms_exec_a_app_20261020", B_DB = "atoms_exec_b_app_20261020", A_TEST = "atoms_exec_a_test_20261020";
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    Path configuration;
    ExecutionBindingRepository repository;

    @BeforeEach void approvedEnvironment() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo("atoms_demo_test.atoms_verify_20261024_ex16");
        String configured = System.getProperty("execution.test-config-directory");
        assertThat(configured).as("Explicit approved private database directory is required").isNotBlank();
        configuration = Path.of(configured); ExecutionRootLease.noLinks(configuration);
        repository = new ExecutionBindingRepository(jdbc);
        jdbc.update("INSERT INTO guest_users(id) VALUES (?) ON CONFLICT (id) DO NOTHING", OWNER);
        for (UUID project : List.of(A, B)) {
            jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,?) ON CONFLICT (id) DO NOTHING", project, OWNER, "execution-binding-contract");
            assertThat(jdbc.queryForMap("SELECT owner_id,name,archived FROM projects WHERE id=?", project))
                    .containsEntry("owner_id", OWNER).containsEntry("name", "execution-binding-contract").containsEntry("archived", false);
        }
    }

    @Test void concurrentRegistrationPersistsOneBindingPerProjectAndKeepsRuntimeSchemasUnchanged() throws Exception {
        var registry = registry(Map.of(A, file(A_DB), B, file(B_DB)));
        var first = registry.verify(A, budget()); var second = registry.verify(B, budget());
        long aBefore = inspect(file(A_DB)).relationCount(), bBefore = inspect(file(B_DB)).relationCount();
        assertThat(first.databaseOid()).isNotEqualTo(second.databaseOid());
        assertThat(first.roleOid()).isNotEqualTo(second.roleOid()); assertThat(first.port()).isEqualTo(18181); assertThat(second.port()).isEqualTo(18182);
        var start = new CountDownLatch(1); var threads = Executors.newFixedThreadPool(2);
        try {
            Callable<ExecutionBindingRepository.Binding> register = () -> { start.await(); return reserve(first); };
            var left = threads.submit(register); var right = threads.submit(register); start.countDown();
            var binding = left.get(10, TimeUnit.SECONDS);
            assertThat(right.get(10, TimeUnit.SECONDS)).isEqualTo(binding);
            assertThat(reserve(second).target()).isEqualTo(second);
            assertThat(reserve(registry(Map.of(A, file(A_DB))).verify(A, budget()))).isEqualTo(binding);
        } finally { threads.shutdownNow(); assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_database_bindings WHERE project_id IN (?,?)", Integer.class, A, B)).isEqualTo(2);
        assertThat(inspect(file(A_DB)).relationCount()).isEqualTo(aBefore);
        assertThat(inspect(file(B_DB)).relationCount()).isEqualTo(bBefore);
    }

    @Test void changingRuntimePortAfterRestartCannotRebindAPreviouslyRegisteredProject() throws Exception {
        var first = reserve(registry(Map.of(A, file(A_DB))).verify(A, budget()));
        Path changed = copy(A_DB, Map.of("port", "18183"));
        var newRegistry = registry(Map.of(A, changed));
        assertThatThrownBy(() -> reserve(newRegistry.verify(A, budget())))
                .isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class, e -> assertThat(e.code()).isEqualTo("RUN_DB_BINDING_CONFLICT"));
        assertThat(repository.get(A)).isEqualTo(first);
    }

    @Test void testPurposeAndForgedMarkersCannotBecomeBusinessDatabaseAuthority() throws Exception {
        assertThatThrownBy(() -> registry(Map.of(A, file(A_TEST)))).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
        Path forged = copy(A_TEST, Map.of("purpose", "runtime", "port", "18181"));
        assertThatThrownBy(() -> registry(Map.of(A, forged))).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
        // The server-side purpose marker also rejects forgery, independently of the configured name denylist.
        var config = ExecutionPrivateConfig.readBusiness(forged, List.of());
        assertThatThrownBy(() -> ExecutionDatabaseInspection.verify(config, budget()))
                .hasMessage("EXECUTION_DB_TARGET_REJECTED").hasNoCause().hasNoSuppressedExceptions();
        for (var change : List.of(Map.of("bindingId", UUID.randomUUID().toString()), Map.of("password", "wrong-business-password-fixture"))) {
            Path path = copy(A_DB, change);
            assertThatThrownBy(() -> registry(Map.of(A, path)).verify(A, budget()))
                    .isInstanceOf(java.io.IOException.class).hasNoCause().hasNoSuppressedExceptions();
        }
    }

    @Test void registryRejectsSharedTargetsAndConfigurationDriftWithoutChangingPersistentBinding() throws Exception {
        var oversized = new HashMap<UUID,Path>();
        for (int i = 0; i < 5; i++) oversized.put(UUID.randomUUID(), file(A_DB));
        assertThatThrownBy(() -> registry(oversized)).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
        assertThatThrownBy(() -> registry(Map.of(A, file(A_DB), B, file(A_DB)))).hasMessage("RUN_DB_BINDING_CONFLICT");
        Path samePort = copy(B_DB, Map.of("port", "18181"));
        assertThatThrownBy(() -> registry(Map.of(A, file(A_DB), B, samePort))).hasMessage("RUN_DB_BINDING_CONFLICT");
        Path databasePort = copy(A_DB, Map.of("port", "5432"));
        assertThatThrownBy(() -> registry(Map.of(A, databasePort))).hasMessage("RUN_DB_BINDING_CONFLICT");
        var before = reserve(registry(Map.of(A, file(A_DB))).verify(A, budget()));
        Path path = copy(A_DB, Map.of()); var registry = registry(Map.of(A, path));
        Files.writeString(path, "changed-private-configuration");
        assertThatThrownBy(() -> registry.verify(A, budget())).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID").hasNoCause();
        assertThat(repository.get(A)).isEqualTo(before);
        var complete = registry(Map.of(A, file(A_DB)));
        assertThatThrownBy(() -> complete.verify(B, budget())).hasMessage("RUN_DB_BINDING_NOT_FOUND");
        assertThatThrownBy(() -> complete.verify(A, () -> Duration.ZERO)).hasMessage("EXECUTION_TIMEOUT");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(tx -> {
            try { return complete.verify(A, budget()); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        })).hasCauseInstanceOf(java.io.IOException.class);
    }

    private ExecutionBindingRepository.Binding reserve(ExecutionBindingRepository.Target target) {
        return new TransactionTemplate(transactions).execute(tx -> repository.reserve(target, 4));
    }
    private ExecutionDatabaseBinding registry(Map<UUID, Path> files) throws Exception {
        return ExecutionDatabaseBinding.load(files, List.of(temporary.toRealPath().resolve("protected-source")),
                Set.of("atoms_demo", "atoms_demo_test", A_TEST, "atoms_exec_b_test_20261020"), Set.of("postgres", "atoms_test", A_TEST, "atoms_exec_b_test_20261020"));
    }
    private ExecutionDatabaseInspection.Identity inspect(Path path) throws Exception {
        return ExecutionDatabaseInspection.verify(ExecutionPrivateConfig.readBusiness(path, List.of()), budget());
    }
    private Path file(String name) { return configuration.resolve(name + ".properties"); }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(15)); }
    private Path copy(String name, Map<String,String> changes) throws Exception {
        Path directory = Files.createDirectory(temporary.toRealPath().resolve(UUID.randomUUID().toString()), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path copy = Files.createFile(directory.resolve("fixture.properties"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        var values = new Properties();
        try (var input = Files.newInputStream(file(name), LinkOption.NOFOLLOW_LINKS)) { values.load(input); }
        values.putAll(changes);
        try (var output = Files.newOutputStream(copy)) { values.store(output, "private contract fixture"); }
        return copy;
    }
}
