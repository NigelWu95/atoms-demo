package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.project.ProjectService;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarFile;
import javax.sql.DataSource;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Real npm/Maven and SQL business tests; opt-in only, no model, SMTP or business DB writes. */
@Tag("project-execution-contract")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionTodoBackendContractIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource database;

    @Test void bothBusinessVersionsBuildAndExerciseRealPostgresWithoutChangingTheTestSchema() throws Exception {
        verifyVersions(false);
    }

    @Test void bothVueVersionsBuildAndShipTheirExactFrontendResources() throws Exception {
        verifyVersions(true);
    }

    private void verifyVersions(boolean withFrontend) throws Exception {
        try (var fixture = new ExecutionTodoFixture(jdbc, projects, transactions, database)) {
            String previousTree = null, previousJar = null, previousFrontend = null;
            for (int version : new int[]{1, 2}) {
                var tree = withFrontend ? ExecutionTodoFixture.application(version) : ExecutionTodoFixture.backend(version);
                var build = fixture.build(tree);
                assertThat(build.status()).as("todo V%s build: %s", version, build.errorCode()).isEqualTo("SUCCEEDED");
                assertThat(tree.sha256()).isNotEqualTo(previousTree);
                Path artifact = fixture.artifact(build);
                var manifest = new ObjectMapper().readTree(Files.readAllBytes(artifact.resolve("manifest.json")));
                assertThat(manifest.path("sourceVersionId").asText()).isEqualTo(build.sourceVersionId().toString());
                assertThat(manifest.path("treeSha256").asText()).isEqualTo(tree.sha256());
                assertThat(manifest.at("/evidence/javaTests/tests").asInt()).isEqualTo(version == 1 ? 10 : 11);
                assertThat(manifest.at("/evidence/frontend/tests").asInt()).isEqualTo(withFrontend ? 22 : 15);
                String digest = manifest.path("jarSha256").asText();
                assertThat(digest).matches("[a-f0-9]{64}").isNotEqualTo(previousJar);
                try (var jar = new JarFile(artifact.resolve("app.jar").toFile())) {
                    assertThat(jar.getEntry("BOOT-INF/classes/dev/atoms/generated/TodoController.class")).isNotNull();
                    assertThat(jar.getEntry("BOOT-INF/classes/db/migration/V002__todos.sql")).isNotNull();
                    assertThat(jar.getEntry("BOOT-INF/classes/db/migration/V003__todo_priority.sql") != null).isEqualTo(version == 2);
                    assertThat(jar.stream().map(java.util.zip.ZipEntry::getName).toList())
                            .noneMatch(name -> name.contains("TodoControllerTest") || name.contains(".properties") && name.contains("private"));
                    if (withFrontend) {
                        var frontend = fixture.frontendDistribution(build);
                        var hash = MessageDigest.getInstance("SHA-256");
                        try (var paths = Files.walk(frontend)) {
                            var files = paths.filter(Files::isRegularFile).sorted().toList();
                            assertThat(files.size()).isGreaterThan(1);
                            assertThat(jar.stream().filter(entry -> entry.getName().startsWith("BOOT-INF/classes/static/") && !entry.isDirectory()).count()).isEqualTo(files.size());
                            for (var file : files) {
                                String name = frontend.relativize(file).toString().replace('\\', '/');
                                var entry = jar.getEntry("BOOT-INF/classes/static/" + name); assertThat(entry).isNotNull();
                                byte[] bytes = Files.readAllBytes(file);
                                try (var input = jar.getInputStream(entry)) { assertThat(input.readAllBytes()).isEqualTo(bytes); }
                                hash.update(name.getBytes(StandardCharsets.UTF_8)); hash.update(bytes);
                            }
                        }
                        String current = HexFormat.of().formatHex(hash.digest());
                        assertThat(current).isNotEqualTo(previousFrontend); previousFrontend = current;
                        System.out.printf("execution_todo_frontend version=%d build=%s sha256=%s tests=%d%n", version, build.id(), current, manifest.at("/evidence/frontend/tests").asInt());
                    }
                }
                fixture.assertTestDatabaseUnchanged();
                System.out.printf("execution_todo_acceptance version=%d source=%s build=%s tree=%s jar=%s javaTests=%d%n",
                        version, build.sourceVersionId(), build.id(), tree.sha256(), digest, manifest.at("/evidence/javaTests/tests").asInt());
                previousTree = tree.sha256(); previousJar = digest;
            }
        }
    }

    @Test void staleWriteMutationIsRejectedByTheActualBusinessTests() throws Exception {
        try (var fixture = new ExecutionTodoFixture(jdbc, projects, transactions, database)) {
            var build = fixture.build(ExecutionTodoFixture.staleWriteMutation());
            assertThat(build.status()).isEqualTo("FAILED");
            assertThat(build.errorCode()).isEqualTo("EXECUTION_PROCESS_FAILED");
            assertThat(fixture.artifact(build)).doesNotExist();
            var report = fixture.businessReport(build);
            assertThat(Files.size(report)).isLessThan(1L << 20);
            var parser = DocumentBuilderFactory.newInstance();
            parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            parser.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            try (var input = Files.newInputStream(report)) {
                var suite = parser.newDocumentBuilder().parse(input).getDocumentElement();
                assertThat(suite.getAttribute("tests")).isEqualTo("6");
                assertThat(suite.getAttribute("failures")).isEqualTo("1");
                assertThat(suite.getAttribute("errors")).isEqualTo("0");
                assertThat(suite.getAttribute("skipped")).isEqualTo("0");
                var failure = suite.getElementsByTagName("failure").item(0);
                assertThat(failure.getParentNode().getAttributes().getNamedItem("name").getNodeValue())
                        .isEqualTo("updatesAtomicallyAndRejectsStaleAndMissingRows");
                assertThat(failure.getTextContent()).contains("Status expected:<409> but was:<200>");
            }
            fixture.assertTestDatabaseUnchanged();
            System.out.printf("execution_todo_mutation build=%s status=%s rejectedBy=stale-write-test%n", build.id(), build.status());
        }
    }
}
