package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.atoms.demo.source.ProjectSourcePolicy;
import dev.atoms.demo.source.SourceTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExecutionNpmPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Path seed = Path.of("../templates/full-project-v1/frontend");

    @Test void approvedSeedCoversTheEntireRealLockAndKeepsPreparationSeparate() throws Exception {
        byte[] manifest = manifest(), lock = lock();
        var policy = new ExecutionNpmPolicy(Files.readAllBytes(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json")));
        var verified = policy.checkBuild(tree(manifest, lock));
        assertThat(verified.packages()).isEqualTo(237);
        assertThat(verified.manifestSha256()).isEqualTo(hash(manifest));
        assertThat(verified.lockSha256()).isEqualTo(hash(lock));
        assertThat(verified.policySha256()).matches("[a-f0-9]{64}");
        assertThat(policy.checkPreparation(tree(manifest, null)).manifestSha256()).isEqualTo(hash(manifest));
        assertThatThrownBy(() -> policy.checkBuild(tree(manifest, null))).hasMessage("EXECUTION_NPM_LOCK_REQUIRED");
        assertThat(verified.toString()).doesNotContain("https://", "node_modules", "scripts");
    }

    @Test void preparedLockMayBeSavedButIsNotAutomaticallyApprovedForBuild() throws Exception {
        byte[] manifest = manifest(), lock = lock();
        var policy = new ExecutionNpmPolicy(approval(manifest, null));
        var prepared = policy.checkPrepared(tree(manifest, null), tree(manifest, lock));
        assertThat(prepared.changed()).isTrue();
        assertThat(prepared.lockSha256()).isEqualTo(hash(lock));
        assertThat(prepared.packages()).isEqualTo(237);
        assertThatThrownBy(() -> policy.checkBuild(tree(manifest, lock))).hasMessage("EXECUTION_NPM_APPROVAL_REQUIRED");
        var approved = new ExecutionNpmPolicy(approval(manifest, lock));
        assertThat(approved.checkPrepared(tree(manifest, lock), tree(manifest, lock)).changed()).isFalse();
    }

    @Test void preparationRejectsNonLockChangesAndInvalidResolvedGraphs() throws Exception {
        byte[] manifest = manifest(), lock = lock();
        var policy = new ExecutionNpmPolicy(approval(manifest, null));
        var base = tree(manifest, null);
        for (String path : List.of("frontend/App.vue", "backend/App.java", "README.md", "frontend/node_modules/pkg/index.js")) {
            var files = new ArrayList<>(tree(manifest, lock).files());
            files.add(new SourceTree.File(path, "changed".getBytes(), 0100644));
            // The real source gateway also rejects generated/dependency directories; do not rely on it alone.
            assertThatThrownBy(() -> policy.checkPrepared(base, new SourceTree(files, "untrusted", 1)))
                    .hasMessage("EXECUTION_LOCK_CHANGES_REJECTED");
        }
        var executable = new ArrayList<>(base.files());
        executable.add(new SourceTree.File("frontend/package-lock.json", lock, 0100755));
        assertThatThrownBy(() -> policy.checkPrepared(base, new SourceTree(executable, "untrusted", 1)))
                .hasMessage("EXECUTION_LOCK_CHANGES_REJECTED");
        assertThatThrownBy(() -> policy.checkPrepared(base, base)).hasMessage("EXECUTION_NPM_LOCK_REQUIRED");
        ObjectNode changed = (ObjectNode) json.readTree(lock);
        ((ObjectNode) changed.path("packages").path("").path("dependencies")).put("vue", "1.0.0");
        byte[] mismatched = json.writeValueAsBytes(changed);
        assertThatThrownBy(() -> policy.checkPrepared(base, tree(manifest, mismatched)))
                .hasMessage("EXECUTION_NPM_POLICY_REJECTED");
        changed = (ObjectNode) json.readTree(lock);
        ((ObjectNode) changed.path("packages").path("node_modules/vue")).put("resolved", "https://unapproved.invalid/vue.tgz");
        byte[] unapproved = json.writeValueAsBytes(changed);
        assertThatThrownBy(() -> policy.checkPrepared(base, tree(manifest, unapproved))).hasMessage("EXECUTION_NPM_POLICY_REJECTED");
    }

    @Test void eitherInputChangingInvalidatesApprovalEvenWhenTheOtherStillMatches() throws Exception {
        byte[] manifest = manifest(), lock = lock();
        var policy = new ExecutionNpmPolicy(approval(manifest, lock));
        byte[] reformat = (new String(manifest, java.nio.charset.StandardCharsets.UTF_8) + "\n").getBytes();
        assertThatThrownBy(() -> policy.checkBuild(tree(reformat, lock))).hasMessage("EXECUTION_NPM_APPROVAL_REQUIRED");
        byte[] changedLock = (new String(lock, java.nio.charset.StandardCharsets.UTF_8) + "\n").getBytes();
        assertThatThrownBy(() -> policy.checkBuild(tree(manifest, changedLock))).hasMessage("EXECUTION_NPM_APPROVAL_REQUIRED");
        var preparationOnly = new ExecutionNpmPolicy(approval(manifest, null));
        preparationOnly.checkPreparation(tree(manifest, null));
        assertThatThrownBy(() -> preparationOnly.checkBuild(tree(manifest, lock))).hasMessage("EXECUTION_NPM_APPROVAL_REQUIRED");
        assertThatThrownBy(() -> preparationOnly.checkPreparation(tree(manifest, lock))).hasMessage("EXECUTION_NPM_APPROVAL_REQUIRED");
        assertThatThrownBy(() -> policy.checkPreparation(tree(manifest, changedLock))).hasMessage("EXECUTION_NPM_APPROVAL_REQUIRED");
        assertThat(policy.checkPreparation(tree(manifest, lock)).baseLockSha256()).isEqualTo(hash(lock));
    }

    @Test void evenAnApprovedManifestCannotEnableWorkspaceUrlOrConfigurationBypasses() throws Exception {
        for (String kind : List.of("workspace", "url", "git", "file", "alias", "override")) {
            ObjectNode manifest = (ObjectNode) json.readTree(manifest());
            switch (kind) {
                case "workspace" -> manifest.putArray("workspaces").add("../outside");
                case "url" -> ((ObjectNode) manifest.get("dependencies")).put("vue", "https://example.invalid/vue.tgz");
                case "git" -> ((ObjectNode) manifest.get("dependencies")).put("vue", "git+https://example.invalid/repo");
                case "file" -> ((ObjectNode) manifest.get("dependencies")).put("vue", "file:../outside");
                case "alias" -> ((ObjectNode) manifest.get("dependencies")).put("vue", "npm:other@1.0.0");
                case "override" -> manifest.putObject("overrides").put("vue", "1.0.0");
                default -> throw new AssertionError();
            }
            byte[] bytes = json.writeValueAsBytes(manifest);
            assertThatThrownBy(() -> new ExecutionNpmPolicy(approval(bytes, lock())).checkPreparation(tree(bytes, null)))
                    .as(kind).hasMessage("EXECUTION_NPM_POLICY_REJECTED");
        }
        for (String path : List.of(".npmrc", "frontend/.npmrc", "frontend/npm-shrinkwrap.json", "package.json")) {
            var files = new ArrayList<>(tree(manifest(), lock()).files());
            files.add(new SourceTree.File(path, "{}".getBytes(), 0100644));
            var source = new ProjectSourcePolicy().validate(files);
            assertThatThrownBy(() -> new ExecutionNpmPolicy(approval(manifest(), lock())).checkBuild(source))
                    .as(path).hasMessage("EXECUTION_NPM_POLICY_REJECTED");
        }
    }

    @Test void everyTransitiveEntryRequiresRegistryIntegrityAndNoLinksOrShrinkwrap() throws Exception {
        for (String kind : List.of("registry", "userinfo", "port", "query", "encoded", "file", "git", "link", "integrity", "shrinkwrap", "bundle")) {
            ObjectNode lock = (ObjectNode) json.readTree(lock());
            ObjectNode entry = (ObjectNode) lock.get("packages").get("node_modules/vue");
            switch (kind) {
                case "registry" -> entry.put("resolved", "https://example.invalid/vue/-/vue-3.5.43.tgz");
                case "userinfo" -> entry.put("resolved", "https://registry.npmjs.org@example.invalid/vue/-/vue-3.5.43.tgz");
                case "port" -> entry.put("resolved", "https://registry.npmjs.org:8443/vue/-/vue-3.5.43.tgz");
                case "query" -> entry.put("resolved", entry.get("resolved").asText() + "?redirect=private");
                case "encoded" -> entry.put("resolved", "https://registry.npmjs.org/%2e%2e/private.tgz");
                case "file" -> entry.put("resolved", "file:../outside");
                case "git" -> entry.put("resolved", "git+https://example.invalid/repo");
                case "link" -> entry.put("link", true);
                case "integrity" -> entry.remove("integrity");
                case "shrinkwrap" -> entry.put("hasShrinkwrap", true);
                case "bundle" -> entry.put("inBundle", true);
                default -> throw new AssertionError();
            }
            byte[] bytes = json.writeValueAsBytes(lock);
            assertThatThrownBy(() -> new ExecutionNpmPolicy(approval(manifest(), bytes)).checkBuild(tree(manifest(), bytes)))
                    .as(kind).hasMessage("EXECUTION_NPM_POLICY_REJECTED");
        }
    }

    @Test void rootManifestMismatchMissingRequiredNodesAndAmbiguousJsonAreRejected() throws Exception {
        for (String kind : List.of("root", "missing", "version", "path")) {
            ObjectNode lock = (ObjectNode) json.readTree(lock());
            ObjectNode packages = (ObjectNode) lock.get("packages");
            switch (kind) {
                case "root" -> ((ObjectNode) packages.get("").get("dependencies")).put("vue", "1.0.0");
                case "missing" -> packages.remove("node_modules/vue");
                case "version" -> lock.put("lockfileVersion", 2);
                case "path" -> packages.set("../outside", packages.get("node_modules/vue"));
                default -> throw new AssertionError();
            }
            byte[] bytes = json.writeValueAsBytes(lock);
            assertThatThrownBy(() -> new ExecutionNpmPolicy(approval(manifest(), bytes)).checkBuild(tree(manifest(), bytes)))
                    .as(kind).hasMessage("EXECUTION_NPM_POLICY_REJECTED");
        }
        for (String invalid : List.of("{\"name\":\"one\",\"name\":\"two\"}", "{} {}", "[]")) {
            byte[] manifest = invalid.getBytes();
            assertThatThrownBy(() -> new ExecutionNpmPolicy(approval(manifest, null)).checkPreparation(tree(manifest, null)))
                    .hasMessage("EXECUTION_NPM_POLICY_REJECTED");
        }
        assertThatThrownBy(() -> new ExecutionNpmPolicy("{\"npm\":{\"registry\":\"PRIVATE_CANARY\"}}".getBytes()))
                .hasMessage("EXECUTION_DEPENDENCY_POLICY_INVALID").hasNoCause();
    }

    @Test void numericVersionsCannotWrapOrCoerceIntoTheApprovedFormat() throws Exception {
        ObjectNode approved = (ObjectNode) json.readTree(approval(manifest(), lock()));
        approved.put("schema", 4294967297L);
        assertThatThrownBy(() -> new ExecutionNpmPolicy(json.writeValueAsBytes(approved)))
                .hasMessage("EXECUTION_DEPENDENCY_POLICY_INVALID");
        for (long version : List.of(4294967299L, 3L << 32)) {
            ObjectNode lock = (ObjectNode) json.readTree(lock());
            lock.put("lockfileVersion", version);
            byte[] bytes = json.writeValueAsBytes(lock);
            assertThatThrownBy(() -> new ExecutionNpmPolicy(approval(manifest(), bytes)).checkBuild(tree(manifest(), bytes)))
                    .hasMessage("EXECUTION_NPM_POLICY_REJECTED");
        }
    }

    @Test void originalSeedNpmrcNeedsExplicitExternalApprovalAndExactSafeBytes() throws Exception {
        byte[] npmrc = Files.readAllBytes(seed.resolve(".npmrc"));
        var source = withNpmrc("frontend/.npmrc", npmrc);
        assertThatThrownBy(() -> new ExecutionNpmPolicy(approval(manifest(), lock())).checkBuild(source))
                .hasMessage("EXECUTION_NPM_APPROVAL_REQUIRED");
        var policy = new ExecutionNpmPolicy(approvalWithNpmrc(npmrc));
        assertThat(policy.checkBuild(source).packages()).isEqualTo(237);
        assertThat(policy.checkPreparation(source).manifestSha256()).isEqualTo(hash(manifest()));
        // The optional grant permits this file; legacy snapshots without it remain valid.
        assertThat(policy.checkBuild(tree(manifest(), lock())).packages()).isEqualTo(237);
        byte[] reformatted = (new String(npmrc, java.nio.charset.StandardCharsets.UTF_8) + "\n").getBytes();
        assertThatThrownBy(() -> policy.checkBuild(withNpmrc("frontend/.npmrc", reformatted)))
                .hasMessage("EXECUTION_NPM_POLICY_REJECTED");
        assertThatThrownBy(() -> new ExecutionNpmPolicy(approvalWithNpmrc("other".getBytes())).checkBuild(source))
                .hasMessage("EXECUTION_NPM_APPROVAL_REQUIRED");
    }

    @Test void externalHashCannotApproveArbitraryNpmConfigOrAnotherLocation() throws Exception {
        for (String content : List.of("ignore-scripts=false\nengine-strict=true\n",
                "ignore-scripts=true\nengine-strict=false\n",
                "ignore-scripts=true\nengine-strict=true\nregistry=https://example.invalid/\n",
                "ignore-scripts=true\nengine-strict=true\nnode-options=--require=./hook.js\n",
                "ignore-scripts=true\nengine-strict=true\n//registry.npmjs.org/:_authToken=${TOKEN}\n",
                "ignore-scripts=true\r\nengine-strict=true\r\n")) {
            byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var policy = new ExecutionNpmPolicy(approvalWithNpmrc(bytes));
            assertThatThrownBy(() -> policy.checkBuild(withNpmrc("frontend/.npmrc", bytes)))
                    .hasMessage("EXECUTION_NPM_POLICY_REJECTED").hasNoCause();
            assertThatThrownBy(() -> policy.checkPreparation(withNpmrc("frontend/.npmrc", bytes)))
                    .hasMessage("EXECUTION_NPM_POLICY_REJECTED");
        }
        byte[] safe = Files.readAllBytes(seed.resolve(".npmrc"));
        var policy = new ExecutionNpmPolicy(approvalWithNpmrc(safe));
        for (String name : List.of(".npmrc", "backend/.npmrc", "frontend/nested/.npmrc", "frontend/.NPMRC"))
            assertThatThrownBy(() -> policy.checkBuild(withNpmrc(name, safe)))
                    .as(name).hasMessage("EXECUTION_NPM_POLICY_REJECTED");
    }

    @Test void originalCompleteTemplatePassesThePublishedSeedApprovalWithoutFiltering() throws Exception {
        var files = new ArrayList<SourceTree.File>();
        for (String name : Files.readAllLines(Path.of("../templates/full-project-v1.files")))
            files.add(new SourceTree.File(name, Files.readAllBytes(Path.of("../templates/full-project-v1").resolve(name)),
                    name.equals("backend/mvnw") ? 0100755 : 0100644));
        var policy = new ExecutionNpmPolicy(Files.readAllBytes(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json")));
        assertThat(files).hasSize(35);
        assertThat(policy.checkBuild(new ProjectSourcePolicy().validate(files)).packages()).isEqualTo(237);
    }

    @Test void npmrcGrantMustBeAnExactLowercaseSha256String() throws Exception {
        for (Object value : List.of("", "A".repeat(64), "0".repeat(63), 17, true)) {
            ObjectNode policy = (ObjectNode) json.readTree(approval(manifest(), lock()));
            ((ObjectNode) policy.path("npm")).set("npmrcSha256", json.valueToTree(value));
            assertThatThrownBy(() -> new ExecutionNpmPolicy(json.writeValueAsBytes(policy)))
                    .hasMessage("EXECUTION_DEPENDENCY_POLICY_INVALID").hasNoCause();
        }
    }

    private SourceTree withNpmrc(String name, byte[] bytes) throws Exception {
        var files = new ArrayList<>(tree(manifest(), lock()).files());
        files.add(new SourceTree.File(name, bytes, 0100644));
        return new ProjectSourcePolicy().validate(files);
    }
    private byte[] approvalWithNpmrc(byte[] npmrc) throws Exception {
        ObjectNode policy = (ObjectNode) json.readTree(approval(manifest(), lock()));
        ((ObjectNode) policy.path("npm")).put("npmrcSha256", hash(npmrc));
        return json.writeValueAsBytes(policy);
    }

    private byte[] manifest() throws Exception { return Files.readAllBytes(seed.resolve("package.json")); }
    private byte[] lock() throws Exception { return Files.readAllBytes(seed.resolve("package-lock.json")); }
    private byte[] approval(byte[] manifest, byte[] lock) throws Exception {
        var npm = new java.util.LinkedHashMap<String, Object>();
        npm.put("registry", "https://registry.npmjs.org/"); npm.put("manifestSha256", hash(manifest));
        if (lock != null) npm.put("lockSha256", hash(lock));
        return json.writeValueAsBytes(Map.of("schema", 1, "npm", npm));
    }
    private SourceTree tree(byte[] manifest, byte[] lock) {
        var files = new ArrayList<SourceTree.File>();
        files.add(new SourceTree.File("frontend/package.json", manifest, 0100644));
        if (lock != null) files.add(new SourceTree.File("frontend/package-lock.json", lock, 0100644));
        return new ProjectSourcePolicy().validate(files);
    }
    private String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
