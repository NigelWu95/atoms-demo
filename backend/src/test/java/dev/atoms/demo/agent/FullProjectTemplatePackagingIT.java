package dev.atoms.demo.agent;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.NodeList;
import static org.assertj.core.api.Assertions.*;

class FullProjectTemplatePackagingIT {
    @TempDir Path temporary;

    @Test void manifestSourcePolicyResourceIncludesAndPackagedBytesAgree() throws Exception {
        Path sources = Path.of("../templates").toRealPath();
        var loader = new ClassLoader(null) {
            @Override public Enumeration<URL> getResources(String name) throws java.io.IOException {
                return Collections.enumeration(List.of(sources.resolve(name.substring(FullProjectTemplateCatalog.ROOT.length())).toUri().toURL()));
            }
        };
        var source = new FullProjectTemplateCatalog(loader).load();
        assertThat(source.files()).hasSize(37);
        assertThat(new FullProjectTemplateCatalog().load().sha256()).isEqualTo(source.sha256());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var pom = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        NodeList includes = (NodeList) XPathFactory.newInstance().newXPath().evaluate(
                "/project/build/resources/resource[starts-with(targetPath,'project-agent-templates')]/includes/include", pom, XPathConstants.NODESET);
        var actual = new java.util.ArrayList<String>();
        for (int i = 0; i < includes.getLength(); i++) actual.add(includes.item(i).getTextContent());
        var expected = new java.util.ArrayList<String>();
        expected.add("full-project-v1.files");
        source.files().forEach(file -> expected.add(file.path()));
        assertThat(actual).containsExactlyElementsOf(expected);
        String prefix = "BOOT-INF/classes/" + FullProjectTemplateCatalog.ROOT;
        try (var jar = new ZipFile(Path.of("target/atoms-demo-0.1.0-SNAPSHOT.jar").toFile())) {
            var names = new HashSet<String>();
            String manifest = prefix + "full-project-v1.files";
            names.add(manifest);
            try (var input = jar.getInputStream(jar.getEntry(manifest))) {
                assertThat(input.readAllBytes()).isEqualTo(Files.readAllBytes(sources.resolve("full-project-v1.files")));
            }
            for (var file : source.files()) {
                String name = prefix + "full-project-v1/" + file.path();
                names.add(name);
                assertThat(jar.getEntry(name)).as("Packaged native source %s", name).isNotNull();
                try (var input = jar.getInputStream(jar.getEntry(name))) { assertThat(input.readAllBytes()).isEqualTo(file.content()); }
            }
            assertThat(jar.stream().filter(entry -> !entry.isDirectory() && entry.getName().startsWith(prefix)).map(entry -> entry.getName()))
                    .containsExactlyInAnyOrderElementsOf(names);
            assertThat(jar.stream().map(entry -> entry.getName())).noneMatch(name ->
                    name.startsWith("BOOT-INF/classes/dev/atoms/demo/agent/FullProjectTemplateFixture")
                    || name.startsWith("BOOT-INF/classes/dev/atoms/demo/agent/AgentChildFixture")
                    || name.startsWith("BOOT-INF/classes/dev/atoms/demo/agent/AgentProtocolFixture"));
        }
    }

    @Test void actualBootJarLoadsNativeTemplateOutsideRepositoryWithEmptyEnvironment() throws Exception {
        Path jar = Path.of("target/atoms-demo-0.1.0-SNAPSHOT.jar").toAbsolutePath();
        Path fixtures = Path.of("target/test-classes").toAbsolutePath();
        Path output = temporary.resolve("template.txt");
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Dloader.path=" + fixtures, "-Dloader.main=dev.atoms.demo.agent.FullProjectTemplateFixture",
                "-cp", jar.toString(), "org.springframework.boot.loader.launch.PropertiesLauncher")
                .directory(temporary.toFile()).redirectOutput(output.toFile()).redirectError(temporary.resolve("errors.txt").toFile());
        command.environment().clear();
        var process = command.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            var tree = new FullProjectTemplateCatalog().load();
            assertThat(Files.readString(output)).isEqualTo("full-project-v1 " + tree.sha256() + " 37\n");
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }
}
