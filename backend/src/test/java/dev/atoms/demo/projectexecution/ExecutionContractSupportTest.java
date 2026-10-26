package dev.atoms.demo.projectexecution;

import dev.atoms.demo.source.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Contract-fixture compatibility only; this does not execute SQL or claim a native build. */
class ExecutionContractSupportTest {
    @TempDir Path temporary;

    @Test void reviewedSelectionAlsoWorksInAnIsolatedFaultProbeWorkingDirectory() throws Exception {
        var java = Path.of(System.getProperty("java.home"));
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).split(File.pathSeparator))
                .map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        var invocation = new ExecutionProcess.Invocation(java.resolve("bin/java"), List.of("-cp", classpath, Probe.class.getName()),
                temporary, temporary, temporary, java, java.resolve("bin")).withPrivateJvmHome();
        var result = new ExecutionProcess().run(invocation, new ExecutionProcess.Deadline(Duration.ofSeconds(20)),
                () -> false, UUID.randomUUID(), UUID.randomUUID(), () -> {});
        assertThat(result.stopped()).isNotNull();
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception { ExecutionContractSupport.reviewed(ExecutionContractSupport.legacyTree()); }
    }

    @Test void onlyExactReviewedLegacyOrBusinessSnapshotsAreAccepted() throws Exception {
        var legacy = ExecutionContractSupport.legacyTree();
        for (var tree : List.of(legacy, ExecutionTodoFixture.application(1), ExecutionTodoFixture.application(2)))
            assertThat(ExecutionContractSupport.reviewed(tree)).isSameAs(tree);
        var changed = new ArrayList<>(ExecutionTodoFixture.application(2).files());
        changed.add(new SourceTree.File("UNREVIEWED.md", new byte[]{1}, 0100644));
        assertThatThrownBy(() -> ExecutionContractSupport.reviewed(new ProjectSourcePolicy().validate(changed)))
                .hasMessage("Retained contract source is not a reviewed fixture");
    }

    @Test void bothBusinessVersionsKeepEverySqlByteWithoutAddingExecutableClasses() throws Exception {
        for (int version : new int[]{1, 2}) {
            var tree = ExecutionTodoFixture.application(version);
            var entries = entries(tree);
            assertThat(entries).hasSize(version + 2); // V001/V002[/V003] plus the explicit fixture marker.
            for (var file : tree.files()) if (file.path().startsWith(ExecutionMigrations.SOURCE))
                assertThat(entries.get(ExecutionMigrations.JAR + file.path().substring(ExecutionMigrations.SOURCE.length())))
                        .isEqualTo(file.content());
            assertThat(entries.keySet()).noneMatch(name -> name.endsWith(".class") || name.endsWith(".java"));
        }
    }

    @Test void legacyFixtureKeepsItsMigrationWhileNonRuntimeSourceDoesNotInventSql() throws Exception {
        var legacy = entries(ExecutionContractSupport.legacyTree());
        assertThat(legacy).hasSize(2);
        assertThat(legacy.get(ExecutionMigrations.JAR + "V001__execution_contract_fixture.sql"))
                .isEqualTo(ExecutionRunAdmissionIT.SQL.getBytes(StandardCharsets.UTF_8));
        var generic = new ProjectSourcePolicy().validate(List.of(new SourceTree.File("README.md", new byte[]{1}, 0100644)));
        assertThat(entries(generic).keySet()).containsExactly("META-INF/execution-contract-fixture");
    }

    private Map<String, byte[]> entries(SourceTree tree) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) { ExecutionContractSupport.writeMigrations(zip, tree); }
        var entries = new TreeMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertThat(entries.put(entry.getName(), zip.readAllBytes())).isNull();
            }
        }
        return entries;
    }
}
