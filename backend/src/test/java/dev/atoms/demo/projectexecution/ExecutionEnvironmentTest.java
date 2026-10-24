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
        environment = new ExecutionEnvironment(new ExecutionSettings(config), config, source, sourceEnvironment,
                mock(ProjectService.class), jdbc, mock(PlatformTransactionManager.class));
    }

    @Test void disabledOrUnreadyNeverOpensStorageOrReadsDatabaseIdentity() {
        verifyNoInteractions(jdbc);
        config.withProperty("atoms.project-execution.enabled", "false").withProperty("atoms.project-execution.test-databases", "private-invalid-json");
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

    private Path privateConfig(Map<String, String> changes) throws Exception {
        var fixture = new ExecutionTestDatabaseTest(); fixture.temporary = temporary;
        return fixture.config(changes);
    }
    private void register(Path file) throws Exception {
        config.withProperty("atoms.project-execution.test-databases", new ObjectMapper()
                .writeValueAsString(Map.of(ExecutionTestDatabaseTest.PROJECT.toString(), file.toString())));
    }
}
