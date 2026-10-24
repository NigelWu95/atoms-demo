package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.source.SourceTree;
import dev.atoms.demo.source.ProjectSourcePolicy;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fixed UTF-8 versioned SQL only. Reads the sealed artifact; never loads classes or invokes Flyway. */
final class ExecutionMigrations {
    static final String SOURCE = "backend/src/main/resources/db/migration/", JAR = "BOOT-INF/classes/db/migration/";
    static final int MAX_FILES = 128, MAX_FILE_BYTES = 2 << 20;
    record Migration(int version, String script, String description, int checksum, String sha256) {}
    record Target(UUID project, UUID build, UUID source, String treeSha256, String jarSha256, String manifestSha256,
                  List<Migration> migrations, String sha256) {
        Target { migrations = List.copyOf(migrations); }
    }

    static Target inspect(SourceTree source, ExecutionArtifact artifact, ExecutionBuild build, OwnedProcess.Budget budget) throws IOException {
        check(budget);
        if (!"SUCCEEDED".equals(build.status())) throw invalid();
        artifact.evidenceFor(build); artifact.verify(budget);
        var checked = new ProjectSourcePolicy().validate(source.files());
        if (!checked.sha256().equals(build.treeSha256()) || !checked.sha256().equals(source.sha256())) throw invalid();
        var expected = new TreeMap<String, byte[]>();
        for (var file : checked.files()) {
            check(budget);
            if (file.path().startsWith(SOURCE)) expected.put(file.path().substring(SOURCE.length()), file.content());
            if (file.path().startsWith("backend/src/main/java/db/migration/")) throw invalid();
        }
        var migrations = catalog(expected);
        var found = new HashSet<String>(); var names = new HashSet<String>(); int entries = 0;
        try (var zip = new ZipFile(artifact.jar().toFile())) {
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                check(budget); var entry = iterator.nextElement(); String name = entry.getName();
                if (++entries > 20000 || name.length() > 1024 || !names.add(name)) throw invalid();
                if (name.startsWith("db/migration/")) throw invalid();
                if (!name.startsWith(JAR) || name.equals(JAR) && entry.isDirectory()) continue;
                String script = name.substring(JAR.length()); var bytes = expected.get(script);
                if (bytes == null || entry.isDirectory() || entry.getSize() != bytes.length || !found.add(script)) throw invalid();
                try (var input = zip.getInputStream(entry)) {
                    if (!Arrays.equals(bytes, input.readNBytes(bytes.length + 1))) throw invalid();
                }
            }
        } catch (ZipException error) { throw invalid(); }
        if (found.size() != expected.size()) throw invalid();
        artifact.verify(budget); check(budget);
        StringBuilder canonical = new StringBuilder("execution-migrations-v1\n");
        for (var migration : migrations) canonical.append(migration.version()).append(':').append(migration.script()).append(':').append(migration.sha256()).append('\n');
        var summary = artifact.summary();
        return new Target(build.projectId(), build.id(), build.sourceVersionId(), build.treeSha256(), summary.jarSha256(),
                summary.manifestSha256(), migrations, hash(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    static List<Migration> catalog(Map<String, byte[]> files) throws IOException {
        if (files.isEmpty() || files.size() > MAX_FILES) throw invalid();
        var result = new TreeMap<Integer, Migration>(); long total = 0;
        for (var file : files.entrySet()) {
            String name = file.getKey(); byte[] bytes = file.getValue();
            if (!name.matches("V[0-9]{1,9}__[A-Za-z][A-Za-z0-9_]{0,99}\\.sql") || bytes == null
                    || bytes.length == 0 || bytes.length > MAX_FILE_BYTES || (total += bytes.length) > (16 << 20)) throw invalid();
            int delimiter = name.indexOf("__"), version = Integer.parseInt(name.substring(1, delimiter));
            if (version < 1) throw invalid();
            String sql;
            try { sql = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (java.nio.charset.CharacterCodingException error) { throw invalid(); }
            if (sql.isBlank() || sql.indexOf('\0') >= 0 || sql.contains("${")) throw invalid();
            // Matches the pinned Flyway 11.7.2 CRC32 algorithm; byte SHA additionally binds exact line endings.
            // https://github.com/flyway/flyway/blob/flyway-11.7.2/flyway-core/src/main/java/org/flywaydb/core/internal/resolver/ChecksumCalculator.java
            var crc = new CRC32();
            if (sql.startsWith("\ufeff")) sql = sql.substring(1);
            sql.lines().forEach(line -> crc.update(line.getBytes(StandardCharsets.UTF_8)));
            var migration = new Migration(version, name, name.substring(delimiter + 2, name.length() - 4).replace('_', ' '), (int) crc.getValue(), hash(bytes));
            if (result.putIfAbsent(version, migration) != null) throw invalid();
        }
        return List.copyOf(result.values());
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static void check(OwnedProcess.Budget budget) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
        var remaining = budget.remaining();
        if (Thread.currentThread().isInterrupted() || remaining == null || remaining.isZero() || remaining.isNegative()) throw new IOException("EXECUTION_TIMEOUT");
    }
    private static IOException invalid() { return new IOException("RUN_MIGRATION_TARGET_INVALID"); }
}
