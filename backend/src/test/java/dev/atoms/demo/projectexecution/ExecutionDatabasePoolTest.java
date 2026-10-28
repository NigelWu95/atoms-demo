package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ExecutionDatabasePoolTest {
    private static final String KEY = "atoms.project-execution.database-pool";
    private final UUID id = UUID.randomUUID();
    @Test void optionalRegistryAndStablePrivatePair() throws Exception {
        assertThat(ExecutionDatabasePool.read(new MockEnvironment())).isEmpty();
        var pool = ExecutionDatabasePool.read(new MockEnvironment().withProperty(KEY,
                "{\"" + id + "\":{\"test\":\"/private/config/test.properties\",\"runtime\":\"/private/config/runtime.properties\"}}"));
        assertThat(ExecutionDatabasePool.merge(Map.of(), pool, false)).containsEntry(id, Path.of("/private/config/test.properties"));
        assertThat(ExecutionDatabasePool.merge(Map.of(), pool, true)).containsEntry(id, Path.of("/private/config/runtime.properties"));
        assertThatThrownBy(() -> ExecutionDatabasePool.merge(Map.of(id, Path.of("/some/config")), pool, false)).isInstanceOf(IOException.class);
    }
    @Test void rejectsUnknownFieldsDuplicateIdsAndNonPrivatePathShapes() {
        for (String raw : new String[]{"[]", "{}", "{\"1-1-1-1-1\":{}}", "{\"" + id + "\":{\"test\":\"relative\",\"runtime\":\"/safe/runtime\"}}",
                "{\"" + id + "\":{\"test\":\"/safe/../test\",\"runtime\":\"/safe/runtime\"}}",
                "{\"" + id + "\":{\"test\":\"/safe/test\",\"runtime\":\"/safe/runtime\",\"password\":\"x\"}}",
                "{\"" + id + "\":{},\"" + id + "\":{}}"})
            assertThatThrownBy(() -> ExecutionDatabasePool.read(new MockEnvironment().withProperty(KEY, raw))).isInstanceOf(IOException.class);
    }
}
