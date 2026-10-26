package dev.atoms.demo.projectexecution;

import dev.atoms.demo.source.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Reviewed test fixtures only. Never adopts an unknown tree or fabricates a successful native application build. */
final class ExecutionContractSupport {
    // Preserve EX26 snapshots and pin the portable-test-config successors; current samples are checked by the unit test.
    // Fault JVMs run outside the repository and must not load template files relative to their working directory.
    private static final Set<String> BUSINESS_TREES = Set.of(
            "f17d13529edc70c4936cabc73e8b3bc4d569dc2416305033c480a5633cb22f73",
            "a786407f59d6ad08be8c77aaad27458c5552b1061c9af5438b5ebc3b4c9363d0",
            "3c99613d599d8a8b273d657582a16a5d1612cda0a14c11de22d7cb63741a5da4",
            "29013c47de04b8a3f645291511a8178cce1646598d45ea3d1fb7d28e84625193");
    private ExecutionContractSupport() {}

    static SourceTree retainedTree(JdbcTemplate jdbc, SourceAgentFixture source, UUID project) throws Exception {
        assertThat(project).isIn(ExecutionBindingContractIT.A, ExecutionBindingContractIT.B);
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
        var versions = jdbc.query("SELECT current_source_version_id FROM project_sources WHERE project_id=?",
                (row, number) -> row.getObject(1, UUID.class), project);
        assertThat(versions.size()).isLessThanOrEqualTo(1);
        if (versions.isEmpty() || versions.get(0) == null) return legacyTree();
        return reviewed(source.snapshot(ExecutionBindingContractIT.OWNER, project, versions.get(0)));
    }

    static SourceTree reviewed(SourceTree tree) {
        var checked = new ProjectSourcePolicy().validate(tree.files());
        assertThat(checked.sha256()).isEqualTo(tree.sha256());
        if (!legacyTree().sha256().equals(checked.sha256()) && !BUSINESS_TREES.contains(checked.sha256()))
            throw new IllegalStateException("Retained contract source is not a reviewed fixture");
        return tree;
    }

    static SourceTree legacyTree() {
        return new ProjectSourcePolicy().validate(List.of(new SourceTree.File(
                ExecutionMigrations.SOURCE + "V001__execution_contract_fixture.sql",
                ExecutionRunAdmissionIT.SQL.getBytes(StandardCharsets.UTF_8), 0100644)));
    }

    /** Copies only the supplied source's SQL. Generic access fixtures with no migration remain SQL-free. */
    static void writeMigrations(ZipOutputStream zip, SourceTree tree) throws IOException {
        zip.putNextEntry(new ZipEntry("META-INF/execution-contract-fixture"));
        zip.write("Controlled metadata fixture; not a native build.\n".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        for (var file : tree.files()) if (file.path().startsWith(ExecutionMigrations.SOURCE)) {
            zip.putNextEntry(new ZipEntry(ExecutionMigrations.JAR + file.path().substring(ExecutionMigrations.SOURCE.length())));
            zip.write(file.content()); zip.closeEntry();
        }
    }
}
