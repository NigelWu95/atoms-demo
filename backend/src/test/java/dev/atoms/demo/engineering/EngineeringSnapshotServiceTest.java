package dev.atoms.demo.engineering;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.Artifact;
import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.artifact.ArtifactValidator;
import dev.atoms.demo.common.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EngineeringSnapshotServiceTest {
    @Test void templateIdentityTracksBytesAndNamesButNotInputOrder() {
        var a = new EngineeringBundle.File("README.md", new byte[]{1});
        var b = new EngineeringBundle.File("backend/pom.xml", new byte[]{2});
        var names = Set.of(a.path(), b.path());
        var first = new TemplateSnapshot(names, List.of(a, b));
        assertThat(first.digest()).matches("[a-f0-9]{64}")
                .isEqualTo(new TemplateSnapshot(names, List.of(b, a)).digest())
                .isNotEqualTo(new TemplateSnapshot(names, List.of(a,
                        new EngineeringBundle.File(b.path(), new byte[]{3}))).digest());
        assertThat(new TemplateSnapshot(Set.of("README.md"), List.of(a)).digest())
                .isNotEqualTo(new TemplateSnapshot(Set.of("backend/pom.xml"),
                        List.of(new EngineeringBundle.File("backend/pom.xml", new byte[]{1}))).digest());
    }

    @Test void reusesAuthorizedSourceAndRejectsTemplateDriftBeforeAssembly() throws Exception {
        var artifacts = mock(ArtifactService.class);
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), id = UUID.randomUUID();
        var version = new ArtifactService.Version(id, project, 1, "ab".repeat(32), null, null, Instant.EPOCH,
                new Artifact(1, new Artifact.Manifest("快照", "固定工程", "web-static-v1", 1,
                        new ObjectMapper().createObjectNode()), Map.of("view.html", "<h1>快照</h1>", "styles.css", "", "main.js", "")));
        var template = new TemplateSnapshot(Set.of("README.md"),
                List.of(new EngineeringBundle.File("README.md", new byte[]{1})));
        var service = new EngineeringSnapshotService(artifacts, new ArtifactValidator(), () -> template);
        when(artifacts.get(owner, project.toString(), id.toString())).thenReturn(version);
        assertThat(service.authorize(owner, project.toString(), id.toString())).isEqualTo(version);
        var snapshot = service.assemble(version, template.digest());
        assertThat(snapshot.templateDigest()).isEqualTo(template.digest());
        assertThat(EngineeringZip.write(snapshot.bundle())).isEqualTo(EngineeringZip.write(
                new EngineeringBundleAssembler(new ArtifactValidator()).assemble(
                        new BundleSource(id, version.contentHash(), version.artifact()), template)));
        assertThatThrownBy(() -> service.assemble(version, "cd".repeat(32)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("BUILD_INPUT_CHANGED");
        when(artifacts.get(owner, project.toString(), id.toString())).thenThrow(new ApiException(404, "VERSION_NOT_FOUND", "不存在"));
        assertThatThrownBy(() -> service.authorize(owner, project.toString(), id.toString()))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("VERSION_NOT_FOUND");
    }
}
