package dev.atoms.demo.engineering;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/** Fixed classpath catalog, never a working-tree scanner or a user-selected template path. */
public final class TemplateCatalog {
    static final String ROOT = "engineering-templates/";
    private final ClassLoader loader;

    public TemplateCatalog() { this(TemplateCatalog.class.getClassLoader()); }
    TemplateCatalog(ClassLoader loader) { this.loader = loader; }

    public TemplateSnapshot load() {
        try {
            byte[] index = read(ROOT + "standalone-v1.files", 32768);
            String text = new String(index, StandardCharsets.US_ASCII);
            if (!text.endsWith("\n") || !Arrays.equals(index, text.getBytes(StandardCharsets.US_ASCII)))
                throw EngineeringBundle.rejected();
            var paths = text.substring(0, text.length() - 1).split("\n", -1);
            if (paths.length > EngineeringBundle.MAX_FILES - 5) throw EngineeringBundle.rejected();
            var names = Arrays.asList(paths);
            if (!names.equals(names.stream().sorted().toList())) throw EngineeringBundle.rejected();
            var allowed = new HashSet<>(names);
            // Validate names and collisions before opening any listed resource.
            new TemplateSnapshot(allowed, names.stream().map(p -> new EngineeringBundle.File(p, new byte[0])).toList());
            var files = new ArrayList<EngineeringBundle.File>();
            long total = 0;
            for (String path : paths) {
                byte[] bytes = read(ROOT + "standalone-v1/" + path, EngineeringBundle.MAX_FILE_BYTES);
                total += bytes.length;
                if (total > EngineeringBundle.MAX_TOTAL_BYTES) throw EngineeringBundle.rejected();
                files.add(new EngineeringBundle.File(path, bytes));
            }
            return new TemplateSnapshot(allowed, files);
        } catch (IOException | java.net.URISyntaxException error) {
            throw EngineeringBundle.rejected();
        }
    }

    private byte[] read(String name, int limit) throws IOException, java.net.URISyntaxException {
        // getResources detects classpath shadowing; streams work for Boot nested JAR URLs too.
        // https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ClassLoader.html#getResources(java.lang.String)
        var resources = loader.getResources(name);
        if (!resources.hasMoreElements()) throw EngineeringBundle.rejected();
        URL resource = resources.nextElement();
        if (resources.hasMoreElements()) throw EngineeringBundle.rejected();
        if (resource.getProtocol().equals("file")) {
            Path path = Path.of(resource.toURI());
            for (Path part = path; part != null; part = part.getParent())
                if (Files.isSymbolicLink(part)) throw EngineeringBundle.rejected();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw EngineeringBundle.rejected();
        } else if (!resource.getProtocol().equals("jar")) {
            throw EngineeringBundle.rejected();
        }
        try (var input = resource.openStream()) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw EngineeringBundle.rejected();
            return bytes;
        }
    }
}
