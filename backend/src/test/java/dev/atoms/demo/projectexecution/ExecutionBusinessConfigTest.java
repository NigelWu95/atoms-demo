package dev.atoms.demo.projectexecution;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExecutionBusinessConfigTest {
    @TempDir Path temporary;

    @Test void businessAndTestConfigurationCannotBeInterchanged() throws Exception {
        var fixture = new ExecutionTestDatabaseTest(); fixture.temporary = temporary;
        Path business = fixture.config(Map.of("purpose", "runtime", "port", "18181"));
        var config = ExecutionPrivateConfig.readBusiness(business, List.of());
        assertThat(config.marker()).isEqualTo("atoms-project-execution:runtime:" + ExecutionTestDatabaseTest.BINDING);
        assertThat(config.applicationPort()).isEqualTo(18181);
        assertThat(config.databasePort()).isEqualTo(5432);
        assertThatThrownBy(() -> ExecutionPrivateConfig.read(business, List.of())).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
        assertThatThrownBy(() -> ExecutionPrivateConfig.readBusiness(fixture.config(Map.of()), List.of())).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
        var process = new ProcessBuilder("never-started"); process.environment().clear();
        assertThatThrownBy(() -> config.applyTestEnvironment(process)).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
        assertThat(process.environment()).isEmpty();
    }

    @Test void businessConfigurationRetainsStrictPrivateFileIdentityAndRejectsExtraOptions() throws Exception {
        var fixture = new ExecutionTestDatabaseTest(); fixture.temporary = temporary;
        for (var extra : List.of(Map.of("port", "0"), Map.of("port", "80"), Map.of("port", "65536"), Map.of("port", "018181"),
                Map.of("purpose", "test"), Map.of("purpose", "production"), Map.of("applicationPort", "18181"),
                Map.of("jdbcUrl", "jdbc:postgresql://127.0.0.1:5432/execution_a_test?currentSchema=generated_app&options=unsafe"))) {
            var changes = new HashMap<>(extra); changes.putIfAbsent("purpose", "runtime");
            changes.putIfAbsent("port", "18181");
            Path path = fixture.config(changes);
            assertThatThrownBy(() -> ExecutionPrivateConfig.readBusiness(path, List.of())).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
        }
        assertThatThrownBy(() -> ExecutionPrivateConfig.readBusiness(fixture.config(Map.of("purpose", "runtime")), List.of()))
                .hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
        Path path = fixture.config(Map.of("purpose", "runtime", "port", "18181"));
        assertThatThrownBy(() -> ExecutionPrivateConfig.readBusiness(path, List.of(path.getParent()))).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
        var config = ExecutionPrivateConfig.readBusiness(path, List.of());
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(config::verify).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
    }
}
