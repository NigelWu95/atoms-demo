package dev.atoms.demo.engineering;

import java.util.List;
import java.util.Set;

/** Only the trusted template catalog (and package tests) can construct a snapshot. */
public final class TemplateSnapshot {
    public static final String ID = "vue-spring-postgres";
    public static final String VERSION = "1.0.0";
    private final List<EngineeringBundle.File> files;

    TemplateSnapshot(Set<String> allowedPaths, List<EngineeringBundle.File> files) {
        this.files = List.copyOf(files);
        TemplatePolicy.validate(Set.copyOf(allowedPaths), this.files);
    }
    public List<EngineeringBundle.File> files() { return files; }

    /** Length-prefixing separates file names and content, independent of input order. */
    public String digest() {
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            for (var file : files.stream().sorted(java.util.Comparator.comparing(EngineeringBundle.File::path)).toList()) {
                byte[] name = file.path().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(name.length).array());
                hash.update(name);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(file.size()).array());
                hash.update(file.content());
            }
            return java.util.HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
