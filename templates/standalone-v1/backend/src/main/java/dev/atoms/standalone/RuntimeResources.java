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
import java.util.Set;
import java.util.function.Function;

/** Bounded immutable classpath inputs. The build fingerprint is checked against the loaded bytes. */
record RuntimeResources(ApplicationArtifactLoader.Artifact artifact, String bootstrap, String document) {
    static final List<String> PATHS = List.of("application/main.js", "application/manifest.json",
            "application/styles.css", "application/view.html", "runtime/bootstrap.js", "runtime/document.html");

    static RuntimeResources fromClasspath() { return fromClasspath(RuntimeResources.class.getClassLoader()); }
    static RuntimeResources fromClasspath(ClassLoader loader) { return load(loader::getResourceAsStream); }

    static RuntimeResources load(Function<String, InputStream> resources) {
        var files = new HashMap<String, byte[]>();
        for (String path : PATHS) files.put(path, read(resources, path,
                path.endsWith("manifest.json") ? 272 * 1024 : 120 * 1024));
        String expected = ApplicationArtifactLoader.utf8(read(resources, "runtime/content.sha256", 65));
        String actual = contentId(files);
        if (!(actual + "\n").equals(expected)) throw ApplicationArtifactLoader.invalid();
        var sources = new HashMap<String, byte[]>();
        for (String path : ApplicationArtifactLoader.FILES) sources.put(path, files.get(path));
        return new RuntimeResources(ApplicationArtifactLoader.parse(sources, actual),
                ApplicationArtifactLoader.utf8(files.get("runtime/bootstrap.js")),
                ApplicationArtifactLoader.utf8(files.get("runtime/document.html")));
    }

    static String contentId(Map<String, byte[]> files) {
        if (!files.keySet().equals(Set.copyOf(PATHS))) throw ApplicationArtifactLoader.invalid();
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update("atoms-runtime-content-v1\0".getBytes(StandardCharsets.UTF_8));
            for (String path : PATHS) {
                byte[] bytes = files.get(path);
                if (bytes == null) throw ApplicationArtifactLoader.invalid();
                hash.update((path + "\0" + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
                hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable."); }
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
