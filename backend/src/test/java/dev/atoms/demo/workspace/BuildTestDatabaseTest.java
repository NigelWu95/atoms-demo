package dev.atoms.demo.workspace;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class BuildTestDatabaseTest {
    @TempDir Path directory;
    @Test void refusesMissingMarkerUnsafeUrlAndUnknownKeysWithoutRevealingValues() throws Exception {
        Path config = Files.createFile(directory.resolve("test.properties"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        for (String value : new String[]{"standalone.test-database=false", "standalone.test-database=true\nSTANDALONE_DB_URL=jdbc:postgresql://localhost/atoms?user=secret-canary",
                "standalone.test-database=true\nspring.config.import=secret-canary"}) {
            Files.writeString(config, value);
            assertThatThrownBy(() -> BuildTestDatabase.verify(config)).isInstanceOf(ApiException.class)
                    .hasMessageNotContaining("secret-canary").hasMessageNotContaining(config.toString())
                    .extracting("code").isEqualTo("BUILD_ENVIRONMENT_NOT_READY");
        }
    }
    /** Explicit read-only local verification, not a skipped test or an automatic schema initializer. */
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("one explicit config required");
        BuildTestDatabase.verify(Path.of(args[0]));
        System.out.println("DEDICATED_BUILD_TEST_DATABASE_VERIFIED_READ_ONLY");
    }
}
