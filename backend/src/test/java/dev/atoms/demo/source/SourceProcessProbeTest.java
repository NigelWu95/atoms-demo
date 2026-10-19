package dev.atoms.demo.source;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SourceProcessProbeTest {
    @Test void usesOnlyTheExplicitTestSchemaAndKeepsTheDatabaseFixed() {
        for (String schema : new String[]{"atoms", "atoms_verify_20261019_pa32b"}) {
            assertThat(SourceProcessProbe.databaseUrl(schema)).isEqualTo(
                    "jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=" + schema
                            + "&connectTimeout=3&socketTimeout=5");
        }
    }

    @Test void refusesMissingSchemasSearchPathsAndConnectionParameterInjection() {
        for (String schema : new String[]{null, "", "public", "atoms,public", "atoms&user=postgres",
                "atoms_verify_", "atoms_verify_x?host=example.com", "atoms_verify_" + "x".repeat(51)}) {
            assertThatThrownBy(() -> SourceProcessProbe.databaseUrl(schema))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid test schema");
        }
    }
}
