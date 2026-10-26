package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionEnvironmentTest {
    @TempDir Path temporary;
    private MockEnvironment config;
    private ProjectSourceReadGateway source;
    private ProjectSourceEnvironment sourceEnvironment;
    private JdbcTemplate jdbc;
    private ExecutionEnvironment environment;
    private ProjectSourceEditGateway edits;
    private DependencyPreparationService preparations;
    private Path root;

    @BeforeEach void prepare() throws Exception {
        temporary = temporary.toRealPath(); root = temporary.resolve("execution");
        config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", UUID.randomUUID().toString());
        for (String name : List.of("node", "npm", "java", "maven", "policy", "root"))
            config.withProperty("atoms.project-execution." + name, (name.equals("root") ? root : temporary.resolve(name)).toString());
        source = mock(ProjectSourceReadGateway.class); when(source.readyForExecution()).thenReturn(true);
        sourceEnvironment = mock(ProjectSourceEnvironment.class);
        var paths = mock(ProjectSourcePaths.class);
        when(paths.root()).thenReturn(temporary.resolve("source")); when(paths.workRoot()).thenReturn(temporary.resolve("work"));
        when(sourceEnvironment.settings()).thenReturn(new ProjectSourceEnvironment.Settings(paths, Path.of("/usr/bin/git")));
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap("SELECT current_database() AS database_name, current_user AS role_name"))
                .thenReturn(Map.of("database_name", "custom_platform", "role_name", "custom_platform_role"));
        edits = mock(ProjectSourceEditGateway.class); preparations = mock(DependencyPreparationService.class);
        environment = new ExecutionEnvironment(new ExecutionSettings(config), config, source, sourceEnvironment,
                mock(ProjectService.class), jdbc, mock(PlatformTransactionManager.class), edits, preparations);
    }

    @Test void disabledOrUnreadyNeverOpensStorageOrReadsDatabaseIdentity() {
        verifyNoInteractions(jdbc);
        config.withProperty("atoms.project-execution.enabled", "false").withProperty("atoms.project-execution.test-databases", "private-invalid-json")
                .withProperty("atoms.project-execution.runtime-databases", "private-runtime-invalid-json");
        assertThatThrownBy(environment::open).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE").hasNoCause();
        verifyNoInteractions(source, sourceEnvironment, jdbc);
        config.withProperty("atoms.project-execution.enabled", "true"); when(source.readyForExecution()).thenReturn(false);
        assertThatThrownBy(environment::open).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE");
        verifyNoInteractions(sourceEnvironment, jdbc); assertThat(root).doesNotExist();
    }

    @Test void rejectsMalformedRegistryBeforeReadingPrivateFilesOrOpeningRoot() throws Exception {
        String key = ExecutionTestDatabaseTest.PROJECT.toString();
        var tooMany = new HashMap<String, String>();
        for (int i = 0; i < 5; i++) tooMany.put(UUID.randomUUID().toString(), "/private/missing-" + i);
        for (String raw : List.of("", "{}", "[]", "null", "x".repeat(16385),
                new ObjectMapper().writeValueAsString(tooMany),
                "{\"" + key + "\":\"/private/a\",\"" + key + "\":\"/private/b\"}",
                "{\"1-1-1-1-1\":\"/private/a\"}", "{\"" + key + "\":42}",
                "{\"" + key + "\":\"relative\"}", "{\"" + key + "\":\"/private/../a\"}",
                "{\"" + key + "\":\"/private/a\"} {}")) {
            config.withProperty("atoms.project-execution.test-databases", raw);
            assertThatThrownBy(environment::open).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE").hasNoCause();
            assertThat(root).doesNotExist();
        }
        verifyNoInteractions(jdbc);
    }

    @Test void nativeResourcesHoldExclusiveRootAndDetectSourceAndConfigurationChanges() throws Exception {
        register(privateConfig(Map.of()));
        try (var resources = environment.open()) {
            resources.check();
            assertThat(root.resolve("builds")).isEmptyDirectory();
            assertThatThrownBy(environment::open).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE");
            when(source.readyForExecution()).thenReturn(false);
            assertThatThrownBy(resources::check).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE");
            when(source.readyForExecution()).thenReturn(true); resources.check();
            config.withProperty("atoms.project-execution.root", temporary.resolve("changed").toString());
            assertThatThrownBy(resources::check).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE");
            config.withProperty("atoms.project-execution.root", root.toString()); resources.check();
            register(privateConfig(Map.of()));
            assertThatThrownBy(resources::check).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE");
        }
        try (var reopened = environment.open()) { reopened.check(); }
    }

    @Test void rejectsActualPlatformIdentityAndProtectedLocationsWithoutRetainingRoot() throws Exception {
        register(privateConfig(Map.of("user", "custom_platform_role")));
        assertThatThrownBy(environment::open).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE").hasNoCause();
        assertThat(root).doesNotExist();
        register(privateConfig(Map.of()));
        config.withProperty("atoms.agent.state-root", root.toString());
        assertThatThrownBy(environment::open).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE");
        config.withProperty("atoms.agent.state-root", temporary.resolve("agent").toString());
        try (var resources = environment.open()) { resources.check(); }
    }

    @Test void cannotOpenInsideATransactionAndDatabaseErrorsNeverLeakDetails() throws Exception {
        register(privateConfig(Map.of()));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(environment::open).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE"); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verifyNoInteractions(jdbc);
        when(jdbc.queryForMap(anyString())).thenThrow(new IllegalStateException("private-database-canary"));
        assertThatThrownBy(environment::open).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE").hasNoCause();
        assertThat(root).doesNotExist();
    }

    @Test void preparationEntryUsesTheSameReadinessGateWithoutClaimingAnEditDuringOpen() throws Exception {
        register(privateConfig(Map.of()));
        try (var resources = environment.open()) {
            verifyNoInteractions(edits, preparations);
            when(source.readyForExecution()).thenReturn(false);
            assertThatThrownBy(() -> resources.runPreparation(mock(DependencyPreparationRepository.Task.class), () -> false, () -> {}))
                    .hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE").hasNoCause();
            verifyNoInteractions(edits, preparations);
            assertThat(root.resolve("builds")).isEmptyDirectory();
        }
    }

    @Test void absentRuntimeConfigurationKeepsBuildResourcesAvailableWithoutOpeningRuntimeTools() throws Exception {
        register(privateConfig(Map.of()));
        try (var resources = environment.open()) {
            assertThat(resources.runtime(() -> { throw new AssertionError("No runtime configured"); })).isNull();
            assertThat(root.resolve("runs")).isEmptyDirectory();
            assertThat(temporary.resolve("node")).doesNotExist();
        }
    }

    @Test void runtimeRegistryMustBeValidAndReferOnlyToConfiguredTestProjects() throws Exception {
        register(privateConfig(Map.of()));
        String key = ExecutionTestDatabaseTest.PROJECT.toString();
        for (String raw : List.of("{}", "null", "[]", "x".repeat(16385),
                "{\"" + key + "\":\"/private/a\",\"" + key + "\":\"/private/b\"}",
                "{\"" + UUID.randomUUID() + "\":\"/private/missing\"}",
                "{\"" + key + "\":\"relative\"}", "{\"" + key + "\":42}",
                "{\"" + key + "\":\"/private/a\"} {}")) {
            config.withProperty("atoms.project-execution.runtime-databases", raw);
            assertThatThrownBy(environment::open).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE").hasNoCause();
            assertThat(root).doesNotExist();
        }
        verifyNoInteractions(jdbc);
    }

    @Test void runtimeAssemblyHoldsTheSameRootAndAttachesExactlyOneCoordinationCallback() throws Exception {
        register(privateConfig(Map.of())); registerRuntime(businessConfig(Map.of()));
        var controls = new java.util.concurrent.atomic.AtomicInteger();
        try (var resources = environment.open()) {
            assertThatThrownBy(() -> resources.runtime(null)).hasMessage("RUN_RESOURCES_ALREADY_ATTACHED");
            var runtime = resources.runtime(controls::incrementAndGet);
            assertThat(runtime).isNotNull(); assertThat(controls).hasValue(0); assertThat(runtime.hasHeldProcesses()).isFalse();
            assertThatThrownBy(() -> resources.runtime(() -> {})).hasMessage("RUN_RESOURCES_ALREADY_ATTACHED");
            assertThatThrownBy(() -> runtime.submit(UUID.randomUUID(), ExecutionTestDatabaseTest.PROJECT, UUID.randomUUID(), UUID.randomUUID(), null, "unauthorized"))
                    .isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class, error -> assertThat(error.code()).isEqualTo("EXECUTION_FORBIDDEN"));
            assertThat(controls).hasValue(1);
            verify(jdbc, times(1)).queryForMap("SELECT current_database() AS database_name, current_user AS role_name");
            verifyNoMoreInteractions(jdbc); verifyNoInteractions(edits, preparations);
            assertThat(root.resolve("runs")).isEmptyDirectory(); assertThat(root.resolve("builds")).isEmptyDirectory();
            assertThatThrownBy(environment::open).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE");
        }
        try (var reopened = environment.open()) { assertThat(reopened.runtime(() -> {})).isNotNull(); }
    }

    @Test void runtimeRejectsTestPurposePlatformRoleAndCrossPurposeReuseBeforeOpeningRoot() throws Exception {
        register(privateConfig(Map.of()));
        for (Path file : List.of(privateConfig(Map.of()), businessConfig(Map.of("user", "custom_platform_role")),
                businessConfig(Map.of("user", "execution_a_role")), businessConfig(Map.of("bindingId", ExecutionTestDatabaseTest.BINDING.toString())))) {
            registerRuntime(file);
            assertThatThrownBy(environment::open).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE").hasNoCause();
            assertThat(root).doesNotExist();
        }
    }

    @Test void runtimeMappingAndPrivateFileDriftRevokeResourcesButDoNotPreventOwnedStopOrClose() throws Exception {
        register(privateConfig(Map.of())); Path original = businessConfig(Map.of()); registerRuntime(original);
        try (var resources = environment.open()) {
            var runtime = resources.runtime(() -> {});
            registerRuntime(businessConfig(Map.of()));
            assertThatThrownBy(resources::check).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE").hasNoCause();
            registerRuntime(original); resources.check();
            Files.writeString(original, Files.readString(original).replace(ExecutionTestDatabaseTest.PASSWORD, "changed-test-only-password"));
            assertThatThrownBy(resources::check).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE");
            assertThatThrownBy(() -> runtime.stop(UUID.randomUUID(), ExecutionTestDatabaseTest.PROJECT, UUID.randomUUID()))
                    .hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE");
            config.withProperty("atoms.project-execution.enabled", "false");
            runtime.requestStopOwned(); assertThat(runtime.hasHeldProcesses()).isFalse();
        }
        try (var reopened = new ExecutionPaths(root, List.of())) { reopened.checkedRoot(); }
    }

    @Test void deliveryReadsUseOriginalRootAndTheSameReadinessGate() throws Exception {
        register(privateConfig(Map.of()));
        try (var resources = environment.open()) {
            assertThat(resources.ownedProcess(UUID.randomUUID())).isNull();
            when(source.readyForExecution()).thenReturn(false);
            assertThatThrownBy(() -> resources.ownedProcess(UUID.randomUUID())).hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE");
            assertThatThrownBy(() -> resources.artifact(mock(ExecutionBuild.class), "unused", "unused", () -> java.time.Duration.ofSeconds(5)))
                    .hasMessage("EXECUTION_ENVIRONMENT_UNAVAILABLE");
            assertThat(root.resolve("builds")).isEmptyDirectory();
        }
    }

    private Path privateConfig(Map<String, String> changes) throws Exception {
        var fixture = new ExecutionTestDatabaseTest(); fixture.temporary = temporary;
        return fixture.config(changes);
    }
    private void register(Path file) throws Exception {
        config.withProperty("atoms.project-execution.test-databases", new ObjectMapper()
                .writeValueAsString(Map.of(ExecutionTestDatabaseTest.PROJECT.toString(), file.toString())));
    }
    private Path businessConfig(Map<String, String> changes) throws Exception {
        var fixture = new ExecutionTestDatabaseTest(); fixture.temporary = temporary;
        return ExecutionRuntimeRegistryTest.businessConfig(fixture, changes);
    }
    private void registerRuntime(Path file) throws Exception {
        config.withProperty("atoms.project-execution.runtime-databases", new ObjectMapper()
                .writeValueAsString(Map.of(ExecutionTestDatabaseTest.PROJECT.toString(), file.toString())));
    }
}
