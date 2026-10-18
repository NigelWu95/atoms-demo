package dev.atoms.demo.source;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Separate from legacy TemplatePolicy: a full source tree may contain mutable Java, Vue and SQL. */
public final class ProjectSourcePolicy {
    public record Limits(int files, long fileBytes, long treeBytes, int pathBytes, int depth) {
        public Limits {
            if (files < 1 || fileBytes < 1 || treeBytes < fileBytes || pathBytes < 1 || depth < 1) throw rejected();
        }
    }
    public static final Limits DEFAULTS = new Limits(512, 2L << 20, 16L << 20, 240, 16);
    private static final Set<String> EXCLUDED = Set.of(".git", ".claude", ".codex", ".agents", ".local", ".mcp.json",
            "node_modules", "target", "dist", "coverage", "test-results", "playwright-report", "logs");
    private static final Pattern RESERVED = Pattern.compile("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?");
    private static final String KEY = "[\\w.-]*(?:password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)";
    private static final Pattern CONFIG_SECRET = Pattern.compile("(?im)(?:^\\s*|,\\s*|(?<!\\$)\\{\\s*)(?:" + KEY
            + ")\\s*[:=]\\s*(\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\r\\n,]+)");
    private static final Pattern JSON_SECRET = Pattern.compile("(?i)[\"'](?:" + KEY + ")[\"']\\s*:\\s*[\"']([^\"']*)[\"']");
    // Literal placeholders already present in the controlled template, not a blanket exemption for tests.
    private static final Set<String> TEMPLATE_PLACEHOLDERS = Set.of("REPLACE_WITH_DATABASE_PASSWORD", "RUNTIME_CANARY", "MODEL_CANARY", "PRIVATE_CANARY");
    private final Limits limits;

    public ProjectSourcePolicy() { this(DEFAULTS); }
    public ProjectSourcePolicy(Limits limits) { this.limits = java.util.Objects.requireNonNull(limits); }

    public SourceTree validate(List<SourceTree.File> input) {
        if (input == null || input.isEmpty() || input.size() > limits.files()) throw rejected();
        long total = 0;
        var names = new HashSet<String>();
        var components = new java.util.HashMap<String, String>();
        for (var file : input) {
            if (file == null || file.size() > limits.fileBytes()) throw rejected();
            validatePath(file.path());
            if (!names.add(file.path().toLowerCase(Locale.ROOT))) throw rejected();
            String prefix = "";
            for (String part : file.path().split("/")) {
                prefix = prefix.isEmpty() ? part : prefix + "/" + part;
                String prior = components.putIfAbsent(prefix.toLowerCase(Locale.ROOT), prefix);
                if (prior != null && !prior.equals(prefix)) throw rejected();
            }
            if (file.mode() != 0100644 && !(file.mode() == 0100755 && file.path().equals("backend/mvnw"))) throw rejected();
            total += file.size();
            if (total > limits.treeBytes()) throw rejected();
            validateText(file.content());
        }
        for (String name : names) {
            for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
                if (names.contains(name.substring(0, slash))) throw rejected();
            }
        }
        var files = input.stream().sorted(Comparator.comparing(SourceTree.File::path)).toList();
        return new SourceTree(files, digest(files), total);
    }

    public void validatePath(String path) {
        if (path == null || path.isEmpty() || path.getBytes(StandardCharsets.UTF_8).length > limits.pathBytes()
                || !Normalizer.isNormalized(path, Normalizer.Form.NFC) || path.codePoints().anyMatch(c -> Character.isISOControl(c)
                || "\\:*?\"<>|".indexOf(c) >= 0)) throw rejected();
        String[] parts = path.split("/", -1);
        if (parts.length > limits.depth()) throw rejected();
        for (String part : parts) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || EXCLUDED.contains(lower) || RESERVED.matcher(part).matches()
                    || lower.startsWith(".env") && !lower.equals(".env.example")
                    || lower.matches(".*\\.(pem|key|p12|pfx|jks|keystore|log)")
                    || lower.equals("application.properties")) throw rejected();
        }
    }

    private void validateText(byte[] bytes) {
        final String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) { throw rejected(); }
        if (value.indexOf('\0') >= 0 || value.matches("(?s).*-----BEGIN (?:[A-Z ]*PRIVATE KEY)-----.*")
                || Pattern.compile("(?i)Authorization\\s*:\\s*Bearer\\s+[^\\s]+").matcher(value).find()) throw rejected();
        for (Pattern pattern : List.of(CONFIG_SECRET, JSON_SECRET)) {
            var match = pattern.matcher(value);
            while (match.find()) {
                String candidate = match.group(1).strip().replaceAll("^[\"']|[\"']$", "");
                if (!candidate.isEmpty() && !candidate.matches("\\$\\{[A-Z_][A-Z_0-9]*}")
                        && !TEMPLATE_PLACEHOLDERS.contains(candidate)
                        && !candidate.matches("(?i)(CHANGE_ME|REPLACE_ME|YOUR_[A-Z_]+|<[^>]+>)")) throw rejected();
            }
        }
    }

    private static String digest(List<SourceTree.File> files) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                output.write("atoms-source-tree-v1\0".getBytes(StandardCharsets.UTF_8));
                for (var file : files) {
                    byte[] name = file.path().getBytes(StandardCharsets.UTF_8);
                    output.writeInt(name.length); output.write(name); output.writeInt(file.mode()); output.writeLong(file.size());
                    output.write(MessageDigest.getInstance("SHA-256").digest(file.content()));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException error) { throw new IllegalStateException("Source digest unavailable", error); }
    }

    static IllegalArgumentException rejected() { return new IllegalArgumentException("Source tree violates file, content or size policy"); }
}
