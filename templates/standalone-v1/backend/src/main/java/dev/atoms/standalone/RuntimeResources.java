package dev.atoms.standalone;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.function.Function;

/** Bounded immutable classpath inputs. The build fingerprint is checked against the loaded bytes. */
record RuntimeResources(ApplicationArtifactLoader.Artifact artifact, String bootstrap, String document,
                        Map<String, byte[]> shellFiles) {
    static final List<String> PATHS = List.of("application/main.js", "application/manifest.json",
            "application/styles.css", "application/view.html", "runtime/bootstrap.js", "runtime/document.html");

    RuntimeResources { shellFiles = copy(shellFiles); }
    @Override public Map<String, byte[]> shellFiles() { return copy(shellFiles); }
    byte[] shellResource(String path) {
        byte[] bytes = shellFiles.get(path);
        return bytes == null ? null : bytes.clone();
    }
    private static Map<String, byte[]> copy(Map<String, byte[]> files) {
        var copied = new HashMap<String, byte[]>();
        files.forEach((path, bytes) -> copied.put(path, bytes.clone()));
        return Map.copyOf(copied);
    }

    static RuntimeResources fromClasspath() { return fromClasspath(RuntimeResources.class.getClassLoader()); }
    static RuntimeResources fromClasspath(ClassLoader loader) { return load(loader::getResourceAsStream); }

    static RuntimeResources load(Function<String, InputStream> resources) {
        var files = new HashMap<String, byte[]>();
        for (String path : PATHS) files.put(path, read(resources, path,
                path.endsWith("manifest.json") ? 272 * 1024 : 120 * 1024));
        files.put("runtime/build.txt", read(resources, "runtime/build.txt", 8192));
        int total = files.values().stream().mapToInt(bytes -> bytes.length).sum();
        for (String path : staticPaths(files.get("runtime/build.txt"))) {
            byte[] bytes = read(resources, path, 2 * 1024 * 1024);
            total += bytes.length;
            if (total > 8 * 1024 * 1024) throw ApplicationArtifactLoader.invalid();
            files.put(path, bytes);
        }
        String expected = ApplicationArtifactLoader.utf8(read(resources, "runtime/content.sha256", 65));
        String actual = contentId(files);
        if (!(actual + "\n").equals(expected)) throw ApplicationArtifactLoader.invalid();
        var sources = new HashMap<String, byte[]>();
        for (String path : ApplicationArtifactLoader.FILES) sources.put(path, files.get(path));
        var shell = new HashMap<String, byte[]>();
        for (String path : staticPaths(files.get("runtime/build.txt"))) shell.put(path, files.get(path));
        return new RuntimeResources(ApplicationArtifactLoader.parse(sources, actual),
                ApplicationArtifactLoader.utf8(files.get("runtime/bootstrap.js")),
                ApplicationArtifactLoader.utf8(files.get("runtime/document.html")), shell);
    }

    static String contentId(Map<String, byte[]> files) {
        var paths = new TreeSet<>(PATHS);
        paths.add("runtime/build.txt");
        paths.addAll(staticPaths(files.get("runtime/build.txt")));
        if (!files.keySet().equals(paths)) throw ApplicationArtifactLoader.invalid();
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update("atoms-runtime-content-v2\0".getBytes(StandardCharsets.UTF_8));
            int total = 0;
            for (String path : paths) {
                byte[] bytes = files.get(path);
                if (bytes == null || bytes.length > 2 * 1024 * 1024) throw ApplicationArtifactLoader.invalid();
                total += bytes.length;
                if (total > 8 * 1024 * 1024) throw ApplicationArtifactLoader.invalid();
                hash.update((path + "\0" + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
                hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable."); }
    }

    static List<String> staticPaths(byte[] index) {
        if (index == null || index.length > 8192) throw ApplicationArtifactLoader.invalid();
        String[] lines = ApplicationArtifactLoader.utf8(index).split("\n", -1);
        if (lines.length < 4 || lines.length > 66 || !lines[0].matches("[a-f0-9]{64}")
                || !lines[lines.length - 1].isEmpty()) throw ApplicationArtifactLoader.invalid();
        var paths = Arrays.asList(lines).subList(1, lines.length - 1);
        if (!paths.contains("static/index.html") || paths.stream().noneMatch(path -> path.matches("static/assets/[^/]+\\.js"))
                || paths.stream().anyMatch(path -> !path.matches("static/(index\\.html|favicon\\.svg|assets/[a-zA-Z0-9_-]+\\.(js|css))"))
                || paths.stream().map(path -> path.toLowerCase(java.util.Locale.ROOT)).distinct().count() != paths.size()
                || !paths.equals(paths.stream().sorted().toList())) throw ApplicationArtifactLoader.invalid();
        return List.copyOf(paths);
    }

    private static byte[] read(Function<String, InputStream> resources, String path, int max) {
        try (var stream = resources.apply(path)) {
            if (stream == null) throw ApplicationArtifactLoader.invalid();
            byte[] bytes = stream.readNBytes(max + 1);
            if (bytes.length > max) throw ApplicationArtifactLoader.invalid();
            return bytes;
        } catch (IOException error) { throw ApplicationArtifactLoader.invalid(); }
    }
}
