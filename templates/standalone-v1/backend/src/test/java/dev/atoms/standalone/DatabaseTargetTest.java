package dev.atoms.standalone;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTargetTest {
    private static final String ID = "82fe0a0b-19d9-45be-a6d3-578635e5be9e";

    @Test
    void independentlyPreparedRuntimeDatabaseMayBeUnmarked() {
        target(null, null, true, true, true).require("dedicated", "app_role", false);
    }

    @Test
    void runtimeAndTestPurposeCannotBeSwappedEvenWithOtherwiseSafePermissions() {
        String test = "atoms-standalone:test:" + ID;
        String runtime = "atoms-standalone:runtime:" + ID;
        target(test, test, true, true, true).require("dedicated", "app_role", true);
        target(runtime, runtime, true, true, true).require("dedicated", "app_role", false);
        assertThatThrownBy(() -> target(test, test, true, true, true).require("dedicated", "app_role", false))
                .hasMessageStartingWith("DATABASE_UNSAFE:").hasNoCause();
        assertThatThrownBy(() -> target(runtime, runtime, true, true, true).require("dedicated", "app_role", true))
                .hasMessageStartingWith("DATABASE_UNSAFE:").hasNoCause();
        assertThatThrownBy(() -> target(null, null, true, true, true).require("dedicated", "app_role", true))
                .hasMessageStartingWith("DATABASE_UNSAFE:").hasNoCause();
    }

    @ParameterizedTest
    @MethodSource("unsafeTargets")
    void refusesUnsafeIdentityPermissionsOrConflictingProvisioningMarkers(DatabaseTarget target) {
        assertThatThrownBy(() -> target.require("dedicated", "app_role", false))
                .hasMessageStartingWith("DATABASE_UNSAFE:").hasNoCause()
                .hasMessageNotContaining("private-canary");
    }

    static Stream<DatabaseTarget> unsafeTargets() {
        return Stream.of(
                new DatabaseTarget("platform", "app_role", null, null, true, true, true),
                new DatabaseTarget("dedicated", "admin", null, null, true, true, true),
                target(null, null, false, true, true), target(null, null, true, false, true),
                target(null, null, true, true, false),
                target("private-canary", "private-canary", true, true, true),
                target("atoms-standalone:runtime:" + ID, null, true, true, true),
                target("atoms-standalone:runtime:" + ID, "atoms-standalone:runtime:another-id", true, true, true));
    }

    private static DatabaseTarget target(String databaseMarker, String roleMarker,
                                         boolean role, boolean database, boolean schema) {
        return new DatabaseTarget("dedicated", "app_role", databaseMarker, roleMarker, role, database, schema);
    }
}
