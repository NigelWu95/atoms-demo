package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceWorkbenchIT extends SourceIntegrationSupport {
    @Test void readsPublishedFilesAndDiffWithoutExposingRepositoryMetadata() throws Exception {
        var first = publish(null, 0, "hello"); var second = publish(first, 1, "世界");
        var gateway = gateway();
        var page = gateway.versions(owner, project, null, 1);
        assertThat(page.items()).extracting(ProjectSourceWorkbenchGateway.Version::id).containsExactly(second);
        assertThat(page.nextBeforeVersion()).isEqualTo(2);
        assertThat(gateway.versions(owner, project, page.nextBeforeVersion(), 1).items())
                .extracting(ProjectSourceWorkbenchGateway.Version::id).containsExactly(first);
        assertThat(gateway.tree(owner, project, second)).singleElement().satisfies(entry -> {
            assertThat(entry.path()).isEqualTo("README.md"); assertThat(entry.bytes()).isEqualTo(6);
        });
        assertThat(gateway.file(owner, project, first, "README.md").content()).isEqualTo("hello");
        assertThat(gateway.diff(owner, project, first, second).changes()).singleElement()
                .satisfies(change -> assertThat(change.path()).isEqualTo("README.md"));
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(page);
        assertThat(json).doesNotContain("commitId", "ownerId", "templateDigest", temporary.toString());
        assertThatThrownBy(() -> gateway.file(stranger, project, first, "README.md")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> gateway.file(owner, project, first, "../.env"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(422));
        assertThatThrownBy(() -> gateway.versions(owner, project, null, 51)).isInstanceOf(ApiException.class);
    }

    @Test void boundsTextWithoutBreakingUnicodeAndKeepsOriginalBytes() {
        String text = "字".repeat(100_000); UUID version = publish(null, 0, text);
        var file = gateway().file(owner, project, version, "README.md");
        assertThat(file.truncated()).isTrue(); assertThat(file.bytes()).isEqualTo(300_000);
        assertThat(file.content()).doesNotContain("�");
        assertThat(file.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(256 << 10);
        assertThat(new SourceQuery(service, repository, environment).file(owner, project, version, "README.md").size()).isEqualTo(300_000);
    }

    private ProjectSourceWorkbenchGateway gateway() { return new ProjectSourceWorkbenchGateway(service, repository, environment); }
    private UUID publish(UUID base, long revision, String text) {
        var tree = new ProjectSourcePolicy().validate(List.of(file("README.md", text)));
        var op = service.admit(owner, project, new SourceRequest(base == null ? "INITIALIZE" : "ACCEPT", base,
                revision, null, null, null, null, tree.sha256()), "workbench-" + revision);
        var done = new SourceWorker(service, repository, environment).execute(op, tree);
        assertThat(done.status()).isEqualTo("COMMITTED"); return done.resultVersionId();
    }
}
