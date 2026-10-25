package dev.atoms.demo.projectexecution;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Private configuration fixtures only; registry assembly must not connect to either database. */
class ExecutionRuntimeRegistryTest {
    @TempDir Path temporary;
    ExecutionTestDatabaseTest fixture;
    ExecutionTestDatabase tests;

    @BeforeEach void setup() throws Exception {
        fixture = new ExecutionTestDatabaseTest(); fixture.temporary = temporary;
        tests = ExecutionTestDatabaseTest.registry(Map.of(ExecutionTestDatabaseTest.PROJECT, fixture.config(Map.of())));
    }

    @Test void differentTestAndRuntimeIdentitiesAreAcceptedWithoutConnecting() throws Exception {
        var runtime = runtime(Map.of()); runtime.requireSeparateFrom(tests); runtime.verifyConfiguration();
        assertThatThrownBy(() -> runtime.requireSeparateFrom(null)).isInstanceOf(java.io.IOException.class).hasNoCause();
    }

    @Test void rejectsDatabaseRoleBindingAndListenerReuseAcrossRegistryPurposes() throws Exception {
        for (var changes : List.of(
                Map.of("database", "execution_a_test", "jdbcUrl", "jdbc:postgresql://127.0.0.1:5432/execution_a_test?currentSchema=generated_app"),
                Map.of("user", "execution_a_role"),
                Map.of("bindingId", ExecutionTestDatabaseTest.BINDING.toString()),
                Map.of("jdbcUrl", "jdbc:postgresql://127.0.0.1:5433/execution_a_app?currentSchema=generated_app", "port", "5432"))) {
            var runtime = runtime(changes);
            assertThatThrownBy(() -> runtime.requireSeparateFrom(tests)).hasMessage("RUN_DB_BINDING_CONFLICT").hasNoCause();
        }
    }

    @Test void privateRuntimeFileChangesRevokeTheLoadedRegistry() throws Exception {
        Path file = businessConfig(fixture, Map.of());
        var runtime = ExecutionDatabaseBinding.load(Map.of(ExecutionTestDatabaseTest.PROJECT, file), List.of(), Set.of("platform"), Set.of("platform_role"));
        runtime.verifyConfiguration();
        Files.writeString(file, Files.readString(file).replace(ExecutionTestDatabaseTest.PASSWORD, "changed-test-only-password"));
        assertThatThrownBy(runtime::verifyConfiguration).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID").hasNoCause();
    }

    @Test void fileVerificationIsRejectedInsideADatabaseTransaction() throws Exception {
        var runtime = runtime(Map.of());
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(runtime::verifyConfiguration).hasMessage("EXECUTION_TRANSACTION_ACTIVE"); }
        finally { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
        runtime.verifyConfiguration();
    }

    private ExecutionDatabaseBinding runtime(Map<String, String> changes) throws Exception {
        return ExecutionDatabaseBinding.load(Map.of(ExecutionTestDatabaseTest.PROJECT, businessConfig(fixture, changes)), List.of(),
                Set.of("platform"), Set.of("platform_role"));
    }
    static Path businessConfig(ExecutionTestDatabaseTest fixture, Map<String, String> changes) throws Exception {
        var values = new HashMap<>(Map.of("purpose", "runtime", "port", "18181", "database", "execution_a_app",
                "user", "execution_app_role", "bindingId", "c29ee403-a48a-41b6-ae92-1e8eb2b18b1b",
                "jdbcUrl", "jdbc:postgresql://127.0.0.1:5432/execution_a_app?currentSchema=generated_app"));
        values.putAll(changes); return fixture.config(values);
    }
}
