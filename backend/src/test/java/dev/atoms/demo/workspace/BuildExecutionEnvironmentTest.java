package dev.atoms.demo.workspace;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuildExecutionEnvironmentTest {
    @TempDir Path temporary;
    @Test void retainsOneLeaseUntilClosedAndNeverReopensAfterShutdown() throws Exception {
        Path root = temporary.toRealPath();
        java.nio.file.Files.setPosixFilePermissions(root, WorkspacePaths.DIRECTORY_MODE);
        var paths = new WorkspacePaths(root);
        var configuration = mock(BuildEnvironment.class);
        var settings = new BuildEnvironment.Settings(paths, root, root, root, Map.of());
        when(configuration.settings()).thenReturn(settings);
        var environment = new BuildExecutionEnvironment(configuration, ignored -> {});
        assertThat(environment.admissionFailure()).isNull();
        assertThat(environment.settings()).isSameAs(settings);
        assertThatThrownBy(() -> new BuildRootLease(paths)).isInstanceOf(java.io.IOException.class);
        environment.close();
        assertThat(environment.admissionFailure().code()).isEqualTo("BUILD_ENVIRONMENT_NOT_READY");
        try (var lease = new BuildRootLease(paths)) { lease.checkCapacity(); }
        verify(configuration, times(1)).settings();
    }
    @Test void disabledConfigurationIsReturnedWithoutStartingAnything() {
        var configuration = mock(BuildEnvironment.class);
        when(configuration.settings()).thenThrow(new ApiException(503, "BUILD_DISABLED", "未启用"));
        var environment = new BuildExecutionEnvironment(configuration, ignored -> { throw new AssertionError(); });
        assertThat(environment.admissionFailure().code()).isEqualTo("BUILD_DISABLED");
    }
}
