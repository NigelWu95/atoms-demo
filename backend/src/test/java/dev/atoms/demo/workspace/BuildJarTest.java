package dev.atoms.demo.workspace;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class BuildJarTest {
    @TempDir Path temporary;
    @Test void verifiesActualPackagedBytesRatherThanTrustingAReportedHash() throws Exception {
        var files = resources();
        String identity = identity(files);
        Path jar = jar(files, identity);
        assertThat(BuildJar.verify(jar, identity)).isEqualTo(BuildArtifacts.hash(BuildArtifacts.read(jar, BuildJar.MAX_BYTES)));
        files.put("static/index.html", "tampered");
        Path changed = jar(files, identity);
        assertThatThrownBy(() -> BuildJar.verify(changed, identity)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> BuildJar.verify(jar, "b".repeat(64))).isInstanceOf(java.io.IOException.class);
    }
    @Test void refusesMissingResourcesInvalidIndexAndOversizedEntries() throws Exception {
        for (String fault : java.util.List.of("missing", "index", "large")) {
            var files = resources();
            String hash = identity(files);
            if (fault.equals("missing")) files.remove("runtime/bootstrap.js");
            if (fault.equals("index")) files.put("runtime/build.txt", "a".repeat(64)+"\n../secret\n");
            if (fault.equals("large")) files.put("static/index.html", "x".repeat(2 * 1024 * 1024 + 1));
            Path bad = jar(files, hash);
            assertThatThrownBy(() -> BuildJar.verify(bad, hash)).isInstanceOf(java.io.IOException.class);
        }
    }
    static TreeMap<String,String> resources() {
        var files = new TreeMap<String,String>();
        for (String name : java.util.List.of("application/main.js", "application/manifest.json", "application/styles.css",
                "application/view.html", "runtime/bootstrap.js", "runtime/document.html", "static/index.html", "static/assets/app.js")) files.put(name, name);
        files.put("runtime/build.txt", "a".repeat(64)+"\nstatic/assets/app.js\nstatic/index.html\n");
        return files;
    }
    static String identity(TreeMap<String,String> files) throws Exception {
        var bytes = new ByteArrayOutputStream();
        bytes.write("atoms-runtime-content-v2\0".getBytes(StandardCharsets.UTF_8));
        for (var entry : files.entrySet()) {
            byte[] content=entry.getValue().getBytes(StandardCharsets.UTF_8);
            bytes.write((entry.getKey()+"\0"+content.length+"\0").getBytes(StandardCharsets.UTF_8)); bytes.write(content);
        }
        return BuildArtifacts.hash(bytes.toByteArray());
    }
    Path jar(TreeMap<String,String> files, String identity) throws Exception {
        Path file = temporary.toRealPath().resolve(java.util.UUID.randomUUID()+".jar");
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            files = new TreeMap<>(files); files.put("runtime/content.sha256", identity+"\n");
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry("BOOT-INF/classes/"+entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        WorkspaceStore.writeNew(file, bytes.toByteArray()); return file;
    }
}
