package dev.atoms.demo.agent;

import dev.atoms.demo.source.ProjectSourcePolicy;
import dev.atoms.demo.source.SourceTree;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

/** Independent controlled source template; never scans a working directory or loads a user path. */
final class FullProjectTemplateCatalog {
    static final String ROOT = "project-agent-templates/";
    static final String ID = "full-project-v1";
    private final ClassLoader loader;

    FullProjectTemplateCatalog() { this(FullProjectTemplateCatalog.class.getClassLoader()); }
    FullProjectTemplateCatalog(ClassLoader loader) { this.loader = java.util.Objects.requireNonNull(loader); }

    SourceTree load() {
        try {
            byte[] index = read(ROOT + ID + ".files", 32768);
            String text = new String(index, StandardCharsets.US_ASCII);
            if (!text.endsWith("\n") || !Arrays.equals(index, text.getBytes(StandardCharsets.US_ASCII))) throw rejected();
            var paths = Arrays.asList(text.substring(0, text.length() - 1).split("\n", -1));
            if (!paths.equals(paths.stream().sorted().toList())) throw rejected();
            var policy = new ProjectSourcePolicy();
            // Validate every name, collision and file count before opening any resource listed in the index.
            policy.validate(paths.stream().map(path -> new SourceTree.File(path, new byte[0], mode(path))).toList());
            var files = new ArrayList<SourceTree.File>();
            long total = 0;
            for (String path : paths) {
                byte[] bytes = read(ROOT + ID + "/" + path, (int) ProjectSourcePolicy.DEFAULTS.fileBytes());
                total += bytes.length;
                if (total > ProjectSourcePolicy.DEFAULTS.treeBytes()) throw rejected();
                files.add(new SourceTree.File(path, bytes, mode(path)));
            }
            return policy.validate(files);
        } catch (IOException | URISyntaxException | IllegalArgumentException error) {
            throw rejected();
        }
    }

    private static int mode(String path) { return path.equals("backend/mvnw") ? 0100755 : 0100644; }

    private byte[] read(String name, int limit) throws IOException, URISyntaxException {
        var resources = loader.getResources(name);
        if (!resources.hasMoreElements()) throw rejected();
        var resource = resources.nextElement();
        if (resources.hasMoreElements()) throw rejected();
        if (resource.getProtocol().equals("file")) {
            Path path = Path.of(resource.toURI());
            for (Path part = path; part != null; part = part.getParent())
                if (Files.isSymbolicLink(part)) throw rejected();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw rejected();
        } else if (!resource.getProtocol().equals("jar")) throw rejected();
        try (var input = resource.openStream()) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw rejected();
            return bytes;
        }
    }

    private static IllegalStateException rejected() { return new IllegalStateException("Full project template rejected"); }
}
