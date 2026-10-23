package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import dev.atoms.demo.source.SourceTree;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Source-external approval of exact bytes. No network, install, lock repair or approval from project files. */
final class ExecutionNpmPolicy {
    private static final String REGISTRY = "https://registry.npmjs.org/";
    private static final byte[] SEED_NPMRC = "ignore-scripts=true\nengine-strict=true\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String NAME = "(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*";
    private static final Pattern LOCATION = Pattern.compile("node_modules/" + NAME + "(?:/node_modules/" + NAME + ")*");
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?(?:\\+[A-Za-z0-9.-]+)?");
    private static final Set<String> MANIFEST_FIELDS = Set.of("name", "version", "private", "type", "packageManager",
            "engines", "scripts", "dependencies", "devDependencies");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String manifestSha, lockSha, npmrcSha, policySha;

    record Preparation(String manifestSha256, String baseLockSha256, String policySha256) {}
    record Verified(String manifestSha256, String lockSha256, String policySha256, int packages) {}

    ExecutionNpmPolicy(byte[] approvedPolicy) throws IOException {
        try {
            JsonNode policy = object(approvedPolicy, 65536);
            fields(policy, Set.of("schema", "npm", "maven"));
            if (!policy.path("schema").isInt() || policy.path("schema").intValue() != 1) throw rejected();
            JsonNode npm = policy.path("npm");
            fields(npm, Set.of("registry", "manifestSha256", "lockSha256", "npmrcSha256"));
            if (!REGISTRY.equals(text(npm, "registry"))) throw rejected();
            manifestSha = text(npm, "manifestSha256");
            lockSha = npm.has("lockSha256") ? text(npm, "lockSha256") : null;
            npmrcSha = npm.has("npmrcSha256") ? text(npm, "npmrcSha256") : null;
            if (!manifestSha.matches("[a-f0-9]{64}") || lockSha != null && !lockSha.matches("[a-f0-9]{64}")
                    || npmrcSha != null && !npmrcSha.matches("[a-f0-9]{64}")) throw rejected();
            policySha = hash(approvedPolicy);
        } catch (IOException | RuntimeException error) { throw new IOException("EXECUTION_DEPENDENCY_POLICY_INVALID"); }
    }

    Preparation checkPreparation(SourceTree tree) throws IOException {
        JsonNode manifest = manifest(tree);
        byte[] lock = file(tree, "frontend/package-lock.json");
        if (lock != null) {
            approveLock(lock);
            lock(manifest, object(lock, 2 << 20), false);
        }
        return new Preparation(manifestSha, lock == null ? null : hash(lock), policySha);
    }

    Verified checkBuild(SourceTree tree) throws IOException {
        JsonNode manifest = manifest(tree);
        byte[] bytes = file(tree, "frontend/package-lock.json");
        if (bytes == null) throw new IOException("EXECUTION_NPM_LOCK_REQUIRED");
        approveLock(bytes);
        int packages = lock(manifest, object(bytes, 2 << 20), true);
        return new Verified(manifestSha, lockSha, policySha, packages);
    }

    private void approveLock(byte[] bytes) throws IOException {
        if (lockSha == null || !lockSha.equals(hash(bytes))) throw new IOException("EXECUTION_NPM_APPROVAL_REQUIRED");
    }

    private JsonNode manifest(SourceTree tree) throws IOException {
        for (var file : tree.files()) {
            String name = file.path().substring(file.path().lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (name.equals(".npmrc")) {
                // npm reads project config separately from userconfig. Permit no general INI expansion/overrides.
                // https://raw.githubusercontent.com/npm/cli/v11.19.0/docs/lib/content/configuring-npm/npmrc.md
                if (!file.path().equals("frontend/.npmrc") || !Arrays.equals(file.content(), SEED_NPMRC)) throw rejected();
                if (npmrcSha == null || !npmrcSha.equals(hash(file.content()))) throw new IOException("EXECUTION_NPM_APPROVAL_REQUIRED");
            }
            if (name.equals("npm-shrinkwrap.json")
                    || name.equals("package.json") && !file.path().equals("frontend/package.json")
                    || name.equals("package-lock.json") && !file.path().equals("frontend/package-lock.json")) throw rejected();
        }
        byte[] bytes = file(tree, "frontend/package.json");
        if (bytes == null) throw rejected();
        if (!manifestSha.equals(hash(bytes))) throw new IOException("EXECUTION_NPM_APPROVAL_REQUIRED");
        JsonNode manifest = object(bytes, 2 << 20);
        fields(manifest, MANIFEST_FIELDS);
        if (!name(text(manifest, "name")) || !VERSION.matcher(text(manifest, "version")).matches()
                || !manifest.path("private").isBoolean() || !manifest.path("private").booleanValue()
                || !text(manifest, "type").equals("module") || !text(manifest, "packageManager").equals("npm@11.19.0"))
            throw rejected();
        JsonNode engines = manifest.path("engines");
        fields(engines, Set.of("node", "npm"));
        if (!text(engines, "node").equals("24.20.x") || !text(engines, "npm").equals("11.19.x")) throw rejected();
        JsonNode scripts = manifest.path("scripts");
        fields(scripts, Set.of("dev", "preview", "typecheck", "lint", "test:unit", "build"));
        for (var iterator = scripts.fieldNames(); iterator.hasNext();) text(scripts, iterator.next());
        dependencies(manifest.path("dependencies"), true);
        dependencies(manifest.path("devDependencies"), true);
        return manifest;
    }

    private static int lock(JsonNode manifest, JsonNode lock, boolean matchManifest) throws IOException {
        fields(lock, Set.of("name", "version", "lockfileVersion", "requires", "packages"));
        if (!lock.path("lockfileVersion").isInt() || lock.path("lockfileVersion").intValue() != 3
                || !lock.path("requires").isBoolean() || !lock.path("requires").booleanValue()) throw rejected();
        JsonNode packages = lock.path("packages"), root = packages.path("");
        if (!packages.isObject() || packages.size() < 2 || packages.size() > 4096) throw rejected();
        fields(root, Set.of("name", "version", "dependencies", "devDependencies", "engines"));
        if (!text(lock, "name").equals(text(root, "name")) || !text(lock, "version").equals(text(root, "version"))) throw rejected();
        if (matchManifest) {
            for (String key : List.of("name", "version", "dependencies", "devDependencies", "engines"))
                if (!root.path(key).equals(manifest.path(key))) throw rejected();
        }
        for (var iterator = packages.fields(); iterator.hasNext();) {
            var item = iterator.next(); String location = item.getKey(); JsonNode entry = item.getValue();
            if (!entry.isObject()) throw rejected();
            if (!location.isEmpty()) {
                if (location.length() > 2048 || !LOCATION.matcher(location).matches()
                        || entry.has("link") || entry.has("inBundle") || entry.has("hasShrinkwrap")) throw rejected();
                String version = text(entry, "version");
                if (!VERSION.matcher(version).matches()) throw rejected();
                String pkg = location.substring(location.lastIndexOf("node_modules/") + "node_modules/".length());
                registry(text(entry, "resolved"), pkg, version);
                String integrity = text(entry, "integrity");
                try {
                    if (!integrity.startsWith("sha512-")) throw rejected();
                    String encoded = integrity.substring(7);
                    byte[] digest = Base64.getDecoder().decode(encoded);
                    if (digest.length != 64 || !Base64.getEncoder().encodeToString(digest).equals(encoded)) throw rejected();
                } catch (IllegalArgumentException error) { throw rejected(); }
            }
            for (String kind : List.of("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")) {
                JsonNode deps = entry.path(kind);
                dependencies(deps, location.isEmpty());
                if (!deps.isObject()) continue;
                for (var names = deps.fieldNames(); names.hasNext();) {
                    String dependency = names.next();
                    boolean optional = kind.equals("optionalDependencies") || kind.equals("peerDependencies")
                            && entry.path("peerDependenciesMeta").path(dependency).path("optional").asBoolean(false);
                    if (resolve(packages, location, dependency) == null && !optional) throw rejected();
                }
            }
        }
        return packages.size() - 1;
    }

    private static JsonNode resolve(JsonNode packages, String location, String name) {
        String current = location;
        while (true) {
            JsonNode candidate = packages.get((current.isEmpty() ? "" : current + "/") + "node_modules/" + name);
            if (candidate != null) return candidate;
            if (current.isEmpty()) return null;
            int parent = current.lastIndexOf("/node_modules/");
            current = parent < 0 ? "" : current.substring(0, parent);
        }
    }

    private static void dependencies(JsonNode dependencies, boolean exact) throws IOException {
        if (dependencies.isMissingNode()) return;
        if (!dependencies.isObject()) throw rejected();
        for (var entries = dependencies.fields(); entries.hasNext();) {
            var entry = entries.next();
            if (!name(entry.getKey()) || !entry.getValue().isTextual()) throw rejected();
            String range = entry.getValue().textValue();
            if (range.length() > 256 || (exact ? !VERSION.matcher(range).matches()
                    : !range.matches("[0-9A-Za-z*^~<>=| .+\\-]+") || !range.matches(".*[0-9xX*].*"))) throw rejected();
        }
    }

    private static void registry(String resolved, String name, String version) throws IOException {
        try {
            URI uri = URI.create(resolved);
            String shortName = name.substring(name.lastIndexOf('/') + 1);
            if (!"https".equals(uri.getScheme()) || !"registry.npmjs.org".equals(uri.getHost()) || uri.getPort() != -1
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !("/" + name + "/-/" + shortName + "-" + version + ".tgz").equals(uri.getRawPath())) throw rejected();
        } catch (IllegalArgumentException error) { throw rejected(); }
    }
    private static boolean name(String value) { return value.length() <= 214 && value.matches(NAME); }
    private static JsonNode object(byte[] bytes, int limit) throws IOException {
        try {
            if (bytes == null || bytes.length == 0 || bytes.length > limit) throw rejected();
            JsonNode value = JSON.readTree(bytes);
            if (value == null || !value.isObject()) throw rejected();
            return value;
        } catch (IOException | RuntimeException error) { throw rejected(); }
    }
    private static void fields(JsonNode value, Set<String> allowed) throws IOException {
        if (!value.isObject()) throw rejected();
        for (var fields = value.fieldNames(); fields.hasNext();) if (!allowed.contains(fields.next())) throw rejected();
    }
    private static String text(JsonNode value, String field) throws IOException {
        JsonNode result = value.path(field);
        if (!result.isTextual() || result.textValue().isEmpty() || result.textValue().length() > 4096) throw rejected();
        return result.textValue();
    }
    private static byte[] file(SourceTree tree, String path) throws IOException {
        var matches = tree.files().stream().filter(file -> file.path().equals(path)).toList();
        if (matches.size() > 1) throw rejected();
        return matches.isEmpty() ? null : matches.get(0).content();
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IOException rejected() { return new IOException("EXECUTION_NPM_POLICY_REJECTED"); }
}
