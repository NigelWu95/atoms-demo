package dev.atoms.demo.agent;

import dev.atoms.demo.source.ProjectSourcePolicy;
import dev.atoms.demo.source.SourceTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FullProjectBackendSeedTest {
    @Test void nativeBackendUsesJava17AndRequiresExternalDatabaseConfiguration() throws Exception {
        Path root = Path.of("../templates/full-project-v1");
        var files = new ArrayList<SourceTree.File>();
        for (String path : List.of("backend/pom.xml", "backend/src/main/java/dev/atoms/generated/GeneratedApplication.java",
                "backend/src/main/resources/application.yml", "config/application.properties.example"))
            files.add(new SourceTree.File(path, Files.readAllBytes(root.resolve(path)), 0100644));
        assertThat(new ProjectSourcePolicy().validate(files).files()).hasSize(4);
        var xml = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var pom = xml.newDocumentBuilder().parse(root.resolve("backend/pom.xml").toFile());
        assertThat(pom.getElementsByTagName("java.version").item(0).getTextContent()).isEqualTo("17");
        assertThat(pom.getElementsByTagName("parent").item(0).getTextContent()).contains("3.5.16");
        assertThat(Files.readString(root.resolve("backend/pom.xml"))).doesNotContain("dev.atoms.demo", "standalone-package");
        assertThat(Files.readString(root.resolve("backend/src/main/resources/application.yml")))
                .contains("127.0.0.1", "${APP_PORT}", "${APP_DB_URL}", "${APP_DB_USERNAME}", "${APP_DB_PASSWORD}");
        assertThat(Files.readString(root.resolve("backend/src/main/java/dev/atoms/generated/GeneratedApplication.java")))
                .contains("package dev.atoms.generated;", "@SpringBootApplication").doesNotContain("dev.atoms.demo");
    }
}
