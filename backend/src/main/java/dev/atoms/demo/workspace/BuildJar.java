package dev.atoms.demo.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Recomputes the template's v2 runtime identity from actual JAR entries; never executes the JAR. */
final class BuildJar {
    static final int MAX_BYTES = 128 * 1024 * 1024;
    private static final String PREFIX = "BOOT-INF/classes/";
    private BuildJar() {}

    static String verify(Path jar, String expected) throws IOException {
        String jarHash = BuildArtifacts.hash(BuildArtifacts.read(jar, MAX_BYTES));
        if (expected == null || !expected.matches("[a-f0-9]{64}")) throw WorkspacePaths.rejected();
        try (var zip = new ZipFile(jar.toFile())) {
            var seen = new HashSet<String>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); String name = entry.getName();
                if (seen.size() >= 20000 || !seen.add(name) || name.startsWith("/") || name.contains("\\")
                        || Arrays.asList(name.split("/")).contains("..")) throw WorkspacePaths.rejected();
            }
            var files = new TreeMap<String, byte[]>();
            for (String path : List.of("application/main.js", "application/manifest.json", "application/styles.css",
                    "application/view.html", "runtime/bootstrap.js", "runtime/document.html"))
                files.put(path, read(zip, path, path.endsWith("manifest.json") ? 272 * 1024 : 120 * 1024));
            byte[] index = read(zip, "runtime/build.txt", 8192); files.put("runtime/build.txt", index);
            String[] lines = new String(index, StandardCharsets.UTF_8).split("\n", -1);
            if (lines.length < 4 || lines.length > 66 || !lines[0].matches("[a-f0-9]{64}") || !lines[lines.length-1].isEmpty()) throw WorkspacePaths.rejected();
            var paths = Arrays.asList(lines).subList(1, lines.length-1);
            if (!paths.contains("static/index.html") || paths.stream().noneMatch(path -> path.matches("static/assets/[^/]+\\.js"))
                    || paths.stream().anyMatch(path -> !path.matches("static/(index\\.html|favicon\\.svg|assets/[a-zA-Z0-9_-]+\\.(js|css))"))
                    || paths.stream().map(path -> path.toLowerCase(java.util.Locale.ROOT)).distinct().count() != paths.size()
                    || !paths.equals(paths.stream().sorted().toList())) throw WorkspacePaths.rejected();
            for (String path : paths) files.put(path, read(zip, path, 2 * 1024 * 1024));
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            hash.update("atoms-runtime-content-v2\0".getBytes(StandardCharsets.UTF_8));
            int total = 0;
            for (var entry : files.entrySet()) {
                byte[] bytes = entry.getValue(); total += bytes.length;
                if (total > 8 * 1024 * 1024) throw WorkspacePaths.rejected();
                hash.update((entry.getKey()+"\0"+bytes.length+"\0").getBytes(StandardCharsets.UTF_8)); hash.update(bytes);
            }
            if (!expected.equals(HexFormat.of().formatHex(hash.digest()))
                    || !Arrays.equals(read(zip, "runtime/content.sha256", 65), (expected+"\n").getBytes(StandardCharsets.US_ASCII))) throw WorkspacePaths.rejected();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        if (!jarHash.equals(BuildArtifacts.hash(BuildArtifacts.read(jar, MAX_BYTES)))) throw WorkspacePaths.rejected();
        return jarHash;
    }
    private static byte[] read(ZipFile zip, String path, int limit) throws IOException {
        var entry = zip.getEntry(PREFIX+path);
        if (entry == null || entry.isDirectory() || entry.getSize() > limit) throw WorkspacePaths.rejected();
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit+1);
            if (bytes.length > limit) throw WorkspacePaths.rejected();
            return bytes;
        }
    }
}
