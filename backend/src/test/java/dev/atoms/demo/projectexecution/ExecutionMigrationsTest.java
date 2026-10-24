package dev.atoms.demo.projectexecution;

import dev.atoms.demo.source.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExecutionMigrationsTest {
    @TempDir Path temporary;
    static final String SCRIPT = "V001__create_table.sql", SQL = "CREATE TABLE sample(id integer);\n", HASH = "a".repeat(64);
    byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Test void checksumsMatchPinnedFlywayWhileByteDigestDetectsLineEndingChanges() throws Exception {
        var checksums = new HashSet<Integer>(); var hashes = new HashSet<String>();
        for (String sql : List.of("-- 中文\nSELECT 1;\n", "-- 中文\r\nSELECT 1;\r\n", "\ufeff-- 中文\nSELECT 1;\n")) {
            var migration = ExecutionMigrations.catalog(Map.of(SCRIPT, bytes(sql))).get(0);
            assertThat(migration.checksum()).isEqualTo(org.flywaydb.core.internal.resolver.ChecksumCalculator.calculate(
                    new org.flywaydb.core.internal.resource.StringResource(sql)));
            checksums.add(migration.checksum()); hashes.add(migration.sha256());
            assertThat(migration.description()).isEqualTo("create table");
        }
        assertThat(checksums).hasSize(1); assertThat(hashes).hasSize(3);
    }
    @Test void rejectsUnsupportedMigrationKindsDuplicateVersionsAndUnsafeOrOversizedContent() {
        for (String name : List.of("R__repeat.sql", "V1__java.class", "beforeMigrate.sql", "V1__a.sql.conf", "sub/V1__a.sql", "V0__zero.sql", "V1.1__minor.sql"))
            assertThatThrownBy(() -> ExecutionMigrations.catalog(Map.of(name, bytes(SQL)))).hasMessage("RUN_MIGRATION_TARGET_INVALID");
        for (byte[] content : List.of(new byte[0], new byte[]{(byte) 0xff}, bytes("SELECT '${secret}';"), bytes("\0"), new byte[ExecutionMigrations.MAX_FILE_BYTES + 1]))
            assertThatThrownBy(() -> ExecutionMigrations.catalog(Map.of(SCRIPT, content))).hasMessage("RUN_MIGRATION_TARGET_INVALID");
        assertThatThrownBy(() -> ExecutionMigrations.catalog(Map.of(SCRIPT, bytes(SQL), "V1__duplicate.sql", bytes(SQL))))
                .hasMessage("RUN_MIGRATION_TARGET_INVALID");
        var many = new HashMap<String,byte[]>();
        for (int i = 1; i <= 129; i++) many.put("V" + i + "__many.sql", bytes(SQL));
        assertThatThrownBy(() -> ExecutionMigrations.catalog(many)).hasMessage("RUN_MIGRATION_TARGET_INVALID");
    }
    @Test void targetBindsExactSourceAndSealedJarAndRefusesAnyMigrationMismatch() throws Exception {
        for (String mode : List.of("exact", "missing", "changed", "extra", "callback", "java", "root")) {
            try (var paths = new ExecutionPaths(temporary.toRealPath().resolve(mode), List.of())) {
                var source = new ProjectSourcePolicy().validate(List.of(new SourceTree.File(ExecutionMigrations.SOURCE + SCRIPT, bytes(SQL), 0100644)));
                UUID id = UUID.randomUUID(), project = UUID.randomUUID(), owner = UUID.randomUUID(), version = UUID.randomUUID();
                var build = new ExecutionBuild(id, project, owner, version, source.sha256(), HASH, HASH, "PACKAGING", false, null, Instant.now(), null, 0);
                var task = paths.createBuild(id);
                Path backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
                Path jar = Files.createDirectory(backend.resolve("target")).resolve("app.jar");
                try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
                    if (!mode.equals("missing")) add(zip, ExecutionMigrations.JAR + SCRIPT, mode.equals("changed") ? SQL + "-- drift" : SQL);
                    if (mode.equals("extra")) add(zip, ExecutionMigrations.JAR + "V002__other.sql", SQL);
                    if (mode.equals("callback")) add(zip, ExecutionMigrations.JAR + "beforeMigrate.sql", SQL);
                    if (mode.equals("java")) add(zip, ExecutionMigrations.JAR + "V2__Migration.class", "binary");
                    if (mode.equals("root")) add(zip, "db/migration/" + SCRIPT, SQL);
                }
                var artifact = ExecutionArtifact.seal(paths, task, build, evidence(), budget(), () -> {});
                var succeeded = new ExecutionBuild(id, project, owner, version, source.sha256(), HASH, HASH, "SUCCEEDED", false, null, build.createdAt(), Instant.now(), 0);
                if (!mode.equals("exact")) {
                    assertThatThrownBy(() -> ExecutionMigrations.inspect(source, artifact, succeeded, budget())).as(mode).hasMessage("RUN_MIGRATION_TARGET_INVALID");
                } else {
                    var target = ExecutionMigrations.inspect(source, artifact, succeeded, budget());
                    assertThat(target.project()).isEqualTo(project); assertThat(target.build()).isEqualTo(id);
                    assertThat(target.source()).isEqualTo(version); assertThat(target.jarSha256()).isEqualTo(artifact.summary().jarSha256());
                    assertThat(target.migrations()).hasSize(1); assertThat(target.sha256()).matches("[a-f0-9]{64}");
                    assertThatThrownBy(() -> ExecutionMigrations.inspect(source, artifact, build, budget())).hasMessage("RUN_MIGRATION_TARGET_INVALID");
                    assertThatThrownBy(() -> ExecutionMigrations.inspect(source, artifact, succeeded, () -> Duration.ZERO)).hasMessage("EXECUTION_TIMEOUT");
                    Files.write(artifact.jar(), bytes("changed"));
                    assertThatThrownBy(() -> ExecutionMigrations.inspect(source, artifact, succeeded, budget())).hasMessage("EXECUTION_ARTIFACT_INVALID");
                }
            }
        }
    }
    private void add(ZipOutputStream zip, String path, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(path)); zip.write(bytes(value)); zip.closeEntry();
    }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
    private ExecutionArtifact.Evidence evidence() {
        var tools = new EnumMap<ExecutionToolchain.Kind, ExecutionToolFiles.Summary>(ExecutionToolchain.Kind.class);
        for (var kind : ExecutionToolchain.Kind.values()) tools.put(kind, new ExecutionToolFiles.Summary(HASH, 1, 20));
        return new ExecutionArtifact.Evidence(tools, new ExecutionNpmPolicy.Verified(HASH, HASH, HASH, 1),
                new ExecutionMavenPolicy.Verified(HASH, HASH), new ExecutionDiagnostics.Frontend(1, 1, 1),
                new ExecutionJavaReport.Summary(1, 1), new ExecutionPackaging.Summary(1, 1, HASH));
    }
}
