package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceStorageBudgetTest {
    @TempDir Path temporary;
    @Test void rejectsRootAndFreeSpaceBudgetsWithoutRemovingExistingEvidence() throws Exception {
        Path root = temporary.toRealPath();
        try (var paths = new ProjectSourcePaths(root.resolve("source"), root.resolve("work"))) {
            Path evidence = Files.writeString(paths.root().resolve("existing-evidence"), "retain this evidence");
            assertThatThrownBy(() -> new SourceStorageBudget(paths, 8, 0).check(0)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> new SourceStorageBudget(paths, 8L << 30, Long.MAX_VALUE).check(1)).isInstanceOf(ApiException.class);
            assertThat(Files.readString(evidence)).isEqualTo("retain this evidence");
            assertThatCode(() -> new SourceStorageBudget(paths, 8L << 30, 0).check(1024)).doesNotThrowAnyException();
        }
    }

    @Test void refusesLinksAndRootReplacementInsteadOfCountingOutsideStorage() throws Exception {
        Path root = temporary.toRealPath();
        try (var paths = new ProjectSourcePaths(root.resolve("source"), root.resolve("work"))) {
            var budget = new SourceStorageBudget(paths, 8L << 30, 0);
            Path external = Files.writeString(root.resolve("outside"), "not source");
            Path link = Files.createSymbolicLink(paths.root().resolve("link"), external);
            assertThatThrownBy(() -> budget.check(0)).isInstanceOf(java.io.IOException.class);
            Files.delete(link);
            Path source = paths.root();
            Files.move(source, source.resolveSibling("old-source"));
            Files.createDirectory(source);
            assertThatThrownBy(() -> budget.check(0)).isInstanceOf(java.io.IOException.class);
            assertThat(Files.readString(external)).isEqualTo("not source");
        }
    }
}
