package dev.atoms.demo.agent;

import dev.atoms.demo.source.SourceTree;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class FullProjectTemplateCatalogTest {
    @TempDir Path temporary;

    @Test void explicitSortedFilesHaveDeterministicSourceHashAndExecutableWrapperMode() throws Exception {
        Path root = fixture("README.md\nbackend/mvnw\n");
        Files.writeString(root.resolve("full-project-v1/.env"), "UNLISTED_CANARY");
        try (var loader = loader(root.getParent())) {
            var first = new FullProjectTemplateCatalog(loader).load();
            var second = new FullProjectTemplateCatalog(loader).load();
            assertThat(first.sha256()).isEqualTo(second.sha256()).matches("[a-f0-9]{64}");
            assertThat(first.files()).extracting(SourceTree.File::path).containsExactly("README.md", "backend/mvnw");
            assertThat(first.files().get(0).content()).isEqualTo("native\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(first.files().get(1).mode()).isEqualTo(0100755);
            Files.writeString(root.resolve("full-project-v1/README.md"), "changed\n");
            assertThat(new FullProjectTemplateCatalog(loader).load().sha256()).isNotEqualTo(first.sha256());
        }
    }

    @Test void unsafeDuplicateUnsortedOrOversizedManifestIsRejected() throws Exception {
        Path root = fixture("README.md\n");
        for (String manifest : List.of("", "README.md", "README.md\nREADME.md\n", "../escape\n", ".env\n",
                "Readme.md\nreadme.md\n", "backend/mvnw\nREADME.md\n", "README.md\r\n", "x".repeat(32769), "a\na/b\n")) {
            Files.writeString(root.resolve("full-project-v1.files"), manifest);
            try (var loader = loader(root.getParent())) { reject(loader); }
        }
    }

    @Test void missingAndShadowedResourcesAreRejected() throws Exception {
        Path root = fixture("README.md\nbackend/mvnw\n");
        Path other = Files.createDirectories(temporary.resolve("other/project-agent-templates"));
        Files.copy(root.resolve("full-project-v1.files"), other.resolve("full-project-v1.files"));
        try (var loader = loader(root.getParent(), other.getParent())) { reject(loader); }
        Files.delete(root.resolve("full-project-v1/backend/mvnw"));
        try (var loader = loader(root.getParent())) { reject(loader); }
    }

    @Test void linkedOversizedNonTextAndSecretResourcesAreRejectedWithoutRawContent() throws Exception {
        Path root = fixture("README.md\nbackend/mvnw\n");
        Path readme = root.resolve("full-project-v1/README.md");
        for (byte[] content : List.of(new byte[]{(byte) 0xff}, new byte[(2 << 20) + 1],
                "api_key=PRIVATE_LEAK_CANARY".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            Files.write(readme, content);
            try (var loader = loader(root.getParent())) { reject(loader); }
        }
        Files.delete(readme);
        Files.createSymbolicLink(readme, root.resolve("full-project-v1/backend/mvnw"));
        try (var loader = loader(root.getParent())) { reject(loader); }
        Files.delete(readme);
        Files.writeString(readme, "native");
        Files.move(root.resolve("full-project-v1/backend"), root.resolve("outside"));
        Files.createSymbolicLink(root.resolve("full-project-v1/backend"), root.resolve("outside"));
        try (var loader = loader(root.getParent())) { reject(loader); }
    }

    private Path fixture(String manifest) throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("resources/project-agent-templates"));
        Files.createDirectories(root.resolve("full-project-v1/backend"));
        Files.writeString(root.resolve("full-project-v1.files"), manifest);
        Files.writeString(root.resolve("full-project-v1/README.md"), "native\n");
        Files.writeString(root.resolve("full-project-v1/backend/mvnw"), "#!/bin/sh\nexit 0\n");
        return root;
    }
    private URLClassLoader loader(Path... roots) throws Exception {
        var urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) urls[i] = roots[i].toUri().toURL();
        return new URLClassLoader(urls, null);
    }
    private void reject(ClassLoader loader) {
        assertThatThrownBy(() -> new FullProjectTemplateCatalog(loader).load())
                .isInstanceOf(IllegalStateException.class).hasMessage("Full project template rejected");
    }
}
