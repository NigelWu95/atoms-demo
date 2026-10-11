package dev.atoms.demo.engineering;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Defense in depth for the catalog's explicit allowlist, never a recursive file selector. */
final class TemplatePolicy {
    private static final Set<String> EXCLUDED_COMPONENTS = Set.of(".git", ".local", "node_modules", "target",
            "dist", "coverage", "test-results", "playwright-report", "application.properties");

    private TemplatePolicy() {}

    static void validate(Set<String> allowedPaths, List<EngineeringBundle.File> files) {
        EngineeringBundle.checkSize(files);
        var actual = new HashSet<String>();
        var foldedFiles = new HashSet<String>();
        for (var file : files) {
            String path = file.path();
            validatePath(path);
            if (!actual.add(path) || !foldedFiles.add(path.toLowerCase(Locale.ROOT))) throw EngineeringBundle.rejected();
        }
        if (files.isEmpty() || !actual.equals(allowedPaths)) throw EngineeringBundle.rejected();

        var spellings = new HashMap<String, String>();
        for (String path : actual) {
            String prefix = "";
            String[] components = path.split("/");
            for (int i = 0; i < components.length; i++) {
                prefix = prefix.isEmpty() ? components[i] : prefix + "/" + components[i];
                String folded = prefix.toLowerCase(Locale.ROOT);
                String existing = spellings.putIfAbsent(folded, prefix);
                if ((existing != null && !existing.equals(prefix))
                        || (i < components.length - 1 && foldedFiles.contains(folded))) throw EngineeringBundle.rejected();
            }
        }
    }

    private static void validatePath(String path) {
        if (!path.matches("[A-Za-z0-9._/-]+")) throw EngineeringBundle.rejected();
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.equals("application") || lower.startsWith("application/")
                || lower.equals("bundle-manifest.json") || lower.startsWith("bundle-manifest.json/"))
            throw EngineeringBundle.rejected();
        for (String part : lower.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".")
                    || part.startsWith(".env") || EXCLUDED_COMPONENTS.contains(part)
                    || part.endsWith(".key") || part.endsWith(".pem") || part.endsWith(".log"))
                throw EngineeringBundle.rejected();
        }
    }
}
