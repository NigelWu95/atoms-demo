package dev.atoms.demo.source;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceDiffTest {
    private SourceTree tree(SourceTree.File... files) { return new ProjectSourcePolicy().validate(List.of(files)); }

    @Test void reportsAddedDeletedEditedAndModeOnlyFilesWithoutChangingEitherTree() {
        var before = tree(file("keep.txt", "same"), file("delete.txt", "old"), file("edit.txt", "prefix\nold\nsuffix\n"), file("backend/mvnw", "#!/bin/sh\n"));
        var after = tree(file("keep.txt", "same"), file("add.txt", "new"), file("edit.txt", "prefix\nnew\nsuffix\n"),
                new SourceTree.File("backend/mvnw", "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8), 0100755));
        var diff = new SourceDiff().compare(before, after);
        assertThat(diff.changes()).extracting(SourceDiff.Change::path).containsExactly("add.txt", "backend/mvnw", "delete.txt", "edit.txt");
        assertThat(diff.changes()).extracting(SourceDiff.Change::kind).containsExactly("ADDED", "MODIFIED", "DELETED", "MODIFIED");
        var mode = diff.changes().get(1);
        assertThat(mode.beforeSha256()).isEqualTo(mode.afterSha256());
        assertThat(mode.beforeMode()).isNotEqualTo(mode.afterMode());
        assertThat(diff.preview()).contains("-old", "+new").doesNotContain("-prefix", "+suffix");
        assertThat(diff.truncated()).isFalse();
        assertThat(new SourceDiff().compare(before, before).changes()).isEmpty();
        assertThat(new SourceDiff().compare(before, before).preview()).isEmpty();
    }

    @Test void truncatesUtf8PreviewWithoutDroppingFileStatistics() {
        var before = tree(file("a.txt", "before"), file("z.txt", "before"));
        var after = tree(file("a.txt", "汉字🙂".repeat(30000)), file("z.txt", "changed"));
        var diff = new SourceDiff().compare(before, after);
        assertThat(diff.truncated()).isTrue();
        assertThat(diff.preview().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(128 << 10);
        assertThat(diff.preview()).doesNotContain("\uFFFD");
        assertThat(diff.changes()).hasSize(2);
        assertThat(diff.changes().get(1).path()).isEqualTo("z.txt");
    }
}
