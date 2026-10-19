package dev.atoms.demo.engineering;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class TemplatePackagingIT {
    @TempDir Path temporary;

    @Test void sourceAllowlistMavenIncludesAndJarBytesAreIdentical() throws Exception {
        Path templateRoot = Path.of("../templates").toAbsolutePath().normalize();
        var sourceLoader = new ClassLoader(null) {
            @Override public Enumeration<URL> getResources(String name) throws java.io.IOException {
                return Collections.enumeration(java.util.List.of(templateRoot.resolve(
                        name.substring(TemplateCatalog.ROOT.length())).toUri().toURL()));
            }
        };
        // Reads original sources too: Maven's copy phase must not hide symlink inputs.
        var sources = new TemplateCatalog(sourceLoader).load();
        assertThat(new TemplateCatalog().load().files()).containsExactlyElementsOf(sources.files());
        var parser = DocumentBuilderFactory.newInstance();
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var pom = parser.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        // Scope parity to the unchanged legacy catalog; native templates have a separate namespace and test.
        var includes = (org.w3c.dom.NodeList) javax.xml.xpath.XPathFactory.newInstance().newXPath().evaluate(
                "/project/build/resources/resource[starts-with(targetPath,'engineering-templates')]/includes/include",
                pom, javax.xml.xpath.XPathConstants.NODESET);
        var configured = new java.util.ArrayList<String>();
        for (int i = 0; i < includes.getLength(); i++) configured.add(includes.item(i).getTextContent());
        var expectedIncludes = new java.util.ArrayList<String>();
        expectedIncludes.add("standalone-v1.files");
        sources.files().forEach(file -> expectedIncludes.add(file.path()));
        assertThat(configured).containsExactlyElementsOf(expectedIncludes);
        String prefix = "BOOT-INF/classes/" + TemplateCatalog.ROOT;
        try (var jar = new ZipFile(Path.of("target/atoms-demo-0.1.0-SNAPSHOT.jar").toFile())) {
            var expected = new HashSet<String>();
            expected.add(prefix + "standalone-v1.files");
            for (var file : sources.files()) {
                String name = prefix + "standalone-v1/" + file.path();
                expected.add(name);
                var entry = jar.getEntry(name);
                assertThat(entry).as("Packaged template entry %s", name).isNotNull();
                try (var input = jar.getInputStream(entry)) { assertThat(input.readAllBytes()).isEqualTo(file.content()); }
            }
            assertThat(jar.stream().filter(e -> !e.isDirectory() && e.getName().startsWith(prefix)).map(e -> e.getName()))
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(jar.stream().map(e -> e.getName())).noneMatch(name -> name.startsWith("BOOT-INF/classes/dev/atoms/fixtures/")
                    || name.equals("BOOT-INF/classes/dev/atoms/demo/engineering/BundleFixture.class")
                    || name.startsWith("BOOT-INF/classes/fixtures/"));
        }
    }

    @Test void actualBootJarLoadsAndAssemblesOutsideRepositoryWithoutStartingPlatform() throws Exception {
        Path jar = Path.of("target/atoms-demo-0.1.0-SNAPSHOT.jar").toAbsolutePath();
        Path fixtureClasses = Path.of("target/test-classes").toAbsolutePath();
        Path output = temporary.resolve("bundle.json");
        Path errors = temporary.resolve("fixture-errors.txt");
        // Test-only entrypoint; Boot's documented launcher loads production classes/resources from the real JAR.
        // https://docs.spring.io/spring-boot/3.5/specification/executable-jar/property-launcher.html
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Dloader.path=" + fixtureClasses, "-Dloader.main=dev.atoms.demo.engineering.BundleFixture",
                "-cp", jar.toString(), "org.springframework.boot.loader.launch.PropertiesLauncher")
                .directory(temporary.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile());
        command.environment().clear();
        var process = command.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as("Isolated fixture exit; inspect target/failsafe-reports on failure").isZero();
            assertThat(Files.size(output)).isLessThan(16L * 1024 * 1024);
            var bundle = new ObjectMapper().readTree(Files.readAllBytes(output));
            assertThat(bundle.path("bundleId").asText()).matches("[a-f0-9]{64}");
            assertThat(bundle.path("files")).hasSize(new TemplateCatalog().load().files().size() + 5);
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }
}
