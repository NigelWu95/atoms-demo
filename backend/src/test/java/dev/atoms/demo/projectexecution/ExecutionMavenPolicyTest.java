package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.atoms.demo.source.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class ExecutionMavenPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Path seed = Path.of("../templates/full-project-v1/backend");

    @Test void seedAndBusinessCodeChangesDoNotGrantUnreviewedPomChanges() throws Exception {
        byte[] pom = Files.readAllBytes(seed.resolve("pom.xml"));
        var policy = new ExecutionMavenPolicy(approval(pom));
        assertThat(policy.check(tree(pom)).pomSha256()).isEqualTo(hash(pom));
        var files = new ArrayList<>(tree(pom).files());
        files.add(new SourceTree.File("backend/src/main/java/demo/Tasks.java", "class Tasks {}".getBytes(), 0100644));
        policy.check(new ProjectSourcePolicy().validate(files));
        assertThatThrownBy(() -> policy.check(tree((new String(pom) + "\n").getBytes())))
                .hasMessage("EXECUTION_MAVEN_APPROVAL_REQUIRED");
    }

    @Test void projectConfigAndAlternatePomEntrypointsAreRefused() throws Exception {
        byte[] pom = Files.readAllBytes(seed.resolve("pom.xml"));
        for (String path : List.of("backend/.mvn/jvm.config", ".mvn/maven.config", "backend/.mvn/extensions.xml",
                "backend/settings.xml", "backend/toolchains.xml", ".mavenrc", "pom.xml", "backend/child/pom.xml")) {
            var files = new ArrayList<>(tree(pom).files());
            files.add(new SourceTree.File(path, "unapproved".getBytes(), 0100644));
            assertThatThrownBy(() -> new ExecutionMavenPolicy(approval(pom)).check(new ProjectSourcePolicy().validate(files)))
                    .as(path).hasMessage("EXECUTION_MAVEN_POLICY_REJECTED");
        }
    }

    @Test void unsupportedProfilesRepositoriesPropertiesAndPluginsRemainRejectedEvenWithMatchingDigest() throws Exception {
        String pom = Files.readString(seed.resolve("pom.xml"));
        List<String> changed = List.of(
                pom.replace("</project>", "<repositories><repository><id>bad</id><url>https://example.invalid/</url></repository></repositories></project>"),
                pom.replace("<java.version>17</java.version>", "<java.version>17</java.version><skipTests>true</skipTests>"),
                pom.replace("<failIfNoTests>true</failIfNoTests>", "<failIfNoTests>false</failIfNoTests>"),
                pom.replace("spring-boot-maven-plugin", "exec-maven-plugin"),
                pom.replace("<id>full-project-package</id>", "<id>unexpected</id>"),
                pom.replace("<relativePath/>", "<relativePath>../pom.xml</relativePath>"),
                pom.replace("<profile>", "<profile><activation><activeByDefault>true</activeByDefault></activation>"),
                pom.replace("<properties>", "<modules><module>../outside</module></modules><properties>"),
                pom.replace("<properties>", "<properties><java.version>17</java.version>"));
        for (String value : changed)
            assertThatThrownBy(() -> new ExecutionMavenPolicy(approval(value.getBytes())).check(tree(value.getBytes())))
                    .hasMessage("EXECUTION_MAVEN_POLICY_REJECTED");
    }

    @Test void xmlExternalEntitiesAndMalformedInputAreNeverResolvedOrLogged(CapturedOutput output) throws Exception {
        for (String value : List.of("<project", """
                <!DOCTYPE project [<!ENTITY external SYSTEM "file:///PRIVATE_XML_CANARY">]>
                <project xmlns="http://maven.apache.org/POM/4.0.0"><name>&external;</name></project>
                """)) {
            assertThatThrownBy(() -> new ExecutionMavenPolicy(approval(value.getBytes())).check(tree(value.getBytes())))
                    .hasMessage("EXECUTION_MAVEN_POLICY_REJECTED").hasNoCause();
        }
        assertThat(output.getAll()).doesNotContain("PRIVATE_XML_CANARY", "Fatal Error", "<project");
    }

    private byte[] approval(byte[] pom) throws Exception {
        ObjectNode policy = (ObjectNode) json.readTree(Files.readAllBytes(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json")));
        ((ObjectNode) policy.path("maven")).put("pomSha256", hash(pom));
        return json.writeValueAsBytes(policy);
    }
    private SourceTree tree(byte[] pom) throws Exception {
        return new ProjectSourcePolicy().validate(List.of(new SourceTree.File("backend/pom.xml", pom, 0100644),
                new SourceTree.File("backend/.mvn/wrapper/maven-wrapper.properties",
                        Files.readAllBytes(seed.resolve(".mvn/wrapper/maven-wrapper.properties")), 0100644)));
    }
    private String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
