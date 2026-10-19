package dev.atoms.demo.agent;

import dev.atoms.demo.source.ProjectSourcePolicy;
import dev.atoms.demo.source.SourceTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FullProjectScriptContractTest {
    @Test void runtimeContractKeepsConfigurationOutsideSourceAndDoesNotClaimExecutionAcceptance() throws Exception {
        Path root = Path.of("../templates/full-project-v1");
        var files = new ArrayList<SourceTree.File>();
        for (String path : List.of("scripts/start.mjs", "scripts/start.test.mjs", "README.md", "THIRD-PARTY-NOTICES.md", ".gitignore"))
            files.add(new SourceTree.File(path, Files.readAllBytes(root.resolve(path)), 0100644));
        assertThat(new ProjectSourcePolicy().validate(files).files()).hasSize(5);
        assertThat(Files.readString(root.resolve("scripts/start.mjs")))
                .contains("toolEnvironment(env)", "--server.address=127.0.0.1", "relative(root, config)", "0o077")
                .doesNotContain("npm", "mvnw", "DEEPSEEK_API_KEY");
        assertThat(Files.readString(root.resolve("README.md")))
                .contains("node scripts/build.mjs", "node scripts/start.mjs --config", "0600", "完整脚本执行及数据库迁移仍待实际验收");
    }

    @Test void fixedBuildGatesAndExternalResourcesArePartOfTheNativeTemplate() throws Exception {
        Path root = Path.of("../templates/full-project-v1");
        var files = new ArrayList<SourceTree.File>();
        for (String path : List.of("scripts/process.mjs", "scripts/process.test.mjs", "scripts/build.mjs",
                "scripts/build.test.mjs", "scripts/package-frontend.mjs", "backend/pom.xml"))
            files.add(new SourceTree.File(path, Files.readAllBytes(root.resolve(path)), 0100644));
        assertThat(new ProjectSourcePolicy().validate(files).files()).hasSize(6);
        assertThat(Files.readString(root.resolve("scripts/process.mjs")))
                .contains("shell: false", "signal?.addEventListener", "toolEnvironment");
        assertThat(Files.readString(root.resolve("scripts/build.mjs")))
                .contains("'--ignore-scripts'", "'typecheck'", "'lint'", "'test:unit'", "'clean'", "'verify'", "'-Pfull-project-package'")
                .doesNotContain("skipTests", "DEEPSEEK_API_KEY", "APP_DB_PASSWORD");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var pom = factory.newDocumentBuilder().parse(root.resolve("backend/pom.xml").toFile());
        var profiles = pom.getElementsByTagName("profile");
        assertThat(profiles.getLength()).isEqualTo(1);
        assertThat(profiles.item(0).getTextContent()).contains("full-project-package", "${full-project.resources}", "static/**");
    }
}
