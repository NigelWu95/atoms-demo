package dev.atoms.demo.agent;

import dev.atoms.demo.source.ProjectSourcePolicy;
import dev.atoms.demo.source.SourceTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FullProjectWrapperTest {
    @Test void wrapperAndLicensesRetainTheReviewedSourceBytesAndModes() throws Exception {
        Path source = Path.of("../templates/standalone-v1"), target = Path.of("../templates/full-project-v1");
        var files = new ArrayList<SourceTree.File>();
        for (String path : List.of("backend/mvnw", "backend/.mvn/wrapper/maven-wrapper.properties",
                "licenses/maven-wrapper-LICENSE.txt", "licenses/maven-wrapper-NOTICE.txt")) {
            byte[] bytes = Files.readAllBytes(target.resolve(path));
            assertThat(bytes).isEqualTo(Files.readAllBytes(source.resolve(path)));
            assertThat(Files.isSymbolicLink(target.resolve(path))).isFalse();
            files.add(new SourceTree.File(path, bytes, path.equals("backend/mvnw") ? 0100755 : 0100644));
        }
        assertThat(Files.isExecutable(target.resolve("backend/mvnw"))).isTrue();
        assertThat(new ProjectSourcePolicy().validate(files).files()).hasSize(4);
    }
}
