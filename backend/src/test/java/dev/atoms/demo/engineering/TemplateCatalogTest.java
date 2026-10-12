package dev.atoms.demo.engineering;

import dev.atoms.demo.common.ApiException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class TemplateCatalogTest {
    @TempDir Path temporary;

    @Test void loadsOnlyExplicitResourcesAndPreservesBytes() throws Exception {
        Path root = fixture("README.md\nbackend/pom.xml\n");
        Files.writeString(root.resolve("standalone-v1/.env"), "SECRET_CANARY");
        try (var loader = loader(root.getParent())) {
            var files = new TemplateCatalog(loader).load().files();
            assertThat(files).extracting(EngineeringBundle.File::path).containsExactly("README.md", "backend/pom.xml");
            assertThat(files.get(0).content()).isEqualTo(new byte[]{0, 1, 2});
        }
    }

    @Test void rejectsMissingOrAmbiguousClasspathResources() throws Exception {
        Path root = fixture("README.md\nbackend/pom.xml\n");
        try (var loader = loader(root.getParent(), root.getParent())) {
            // URLClassLoader deduplicates identical roots; use distinct copies below.
            assertThat(new TemplateCatalog(loader).load().files()).hasSize(2);
        }
        Path second = Files.createDirectory(temporary.resolve("second"));
        Files.createDirectories(second.resolve("engineering-templates"));
        Files.copy(root.resolve("standalone-v1.files"), second.resolve("engineering-templates/standalone-v1.files"));
        try (var loader = loader(root.getParent(), second)) { reject(loader); }
        Files.delete(root.resolve("standalone-v1/backend/pom.xml"));
        try (var loader = loader(root.getParent())) { reject(loader); }
    }

    @Test void rejectsUnsafeDuplicateUnsortedAndUnboundedIndexesBeforeLoadingFiles() throws Exception {
        Path root = fixture("README.md\n");
        for (String index : List.of("", "README.md", "README.md\nREADME.md\n", "../escape\n", ".env\n",
                "Readme.md\nreadme.md\n", "backend/pom.xml\nREADME.md\n", "README.md\r\n", "x".repeat(32769))) {
            Files.writeString(root.resolve("standalone-v1.files"), index);
            try (var loader = loader(root.getParent())) { reject(loader); }
        }
    }

    @Test void rejectsSymlinkFilesAndAncestorsAndOversizedResources() throws Exception {
        Path root = fixture("README.md\nbackend/pom.xml\n");
        Path file = root.resolve("standalone-v1/README.md");
        Files.delete(file);
        Files.createSymbolicLink(file, root.resolve("standalone-v1/backend/pom.xml"));
        try (var loader = loader(root.getParent())) { reject(loader); }
        Files.delete(file);
        Files.write(file, new byte[EngineeringBundle.MAX_FILE_BYTES + 1]);
        try (var loader = loader(root.getParent())) { reject(loader); }
        Files.write(file, new byte[]{1});
        Files.move(root.resolve("standalone-v1/backend"), root.resolve("outside"));
        Files.createSymbolicLink(root.resolve("standalone-v1/backend"), root.resolve("outside"));
        try (var loader = loader(root.getParent())) { reject(loader); }
    }

    @Test void realCatalogIncludesBuildChecksAndFitsReservedApplicationBudget() {
        var files = new TemplateCatalog().load().files();
        assertThat(files.size()).isLessThanOrEqualTo(EngineeringBundle.MAX_FILES - 5);
        assertThat(files).extracting(EngineeringBundle.File::path).contains("frontend/package-lock.json",
                "backend/mvnw", "scripts/build.mjs", "scripts/start.test.mjs", "THIRD-PARTY-NOTICES.md",
                "backend/src/test/java/dev/atoms/standalone/PackagedRuntimeIT.java");
        assertThat(files.stream().mapToLong(EngineeringBundle.File::size).sum()).isLessThan(EngineeringBundle.MAX_TOTAL_BYTES);
    }

    private Path fixture(String index) throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("resources/engineering-templates"));
        Files.createDirectories(root.resolve("standalone-v1/backend"));
        Files.writeString(root.resolve("standalone-v1.files"), index);
        Files.write(root.resolve("standalone-v1/README.md"), new byte[]{0, 1, 2});
        Files.writeString(root.resolve("standalone-v1/backend/pom.xml"), "<project/>");
        return root;
    }
    private URLClassLoader loader(Path... roots) throws Exception {
        var urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) urls[i] = roots[i].toUri().toURL();
        return new URLClassLoader(urls, null);
    }
    private void reject(ClassLoader loader) {
        assertThatThrownBy(() -> new TemplateCatalog(loader).load()).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_ENGINEERING_BUNDLE");
    }
}
