package dev.atoms.demo.engineering;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.Artifact;
import dev.atoms.demo.artifact.ArtifactValidator;
import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class TemplatePolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"/tmp/file", "../escape", "a/../b", "a/./b", "a//b", "a/", "a\\b", "a\0b",
            "C:/file", "file\nname", "", ".", "..", "backend/dir.", "file:name", "file name"})
    void rejectsNonPortablePathsEvenIfTheyAppearInAnAllowlist(String path) {
        assertRejected(() -> snapshot(List.of(file(path, 1))));
    }

    @ParameterizedTest
    @ValueSource(strings = {".env", ".env.local", "backend/.env.example", ".git/config", ".local/config",
            "frontend/node_modules/a.js", "backend/target/app.jar", "frontend/dist/a.js", "private.key",
            "db.pem", "debug.log", "application.properties", "config/application.properties",
            "application/main.js", "Application/manifest.json", "bundle-manifest.json", "bundle-manifest.json/extra"})
    void rejectsExcludedFilesAndReservedApplicationPaths(String path) {
        assertRejected(() -> snapshot(List.of(new EngineeringBundle.File(path, "PRIVATE_CANARY".getBytes(StandardCharsets.UTF_8)))));
    }

    @Test void rejectsCaseCollisionsDuplicatePathsAndFilesThatShadowDirectories() {
        for (var paths : List.of(List.of("README.md", "readme.md"), List.of("README.md", "README.md"),
                List.of("backend/src", "backend/src/App.java"), List.of("backend/src/App.java", "Backend/src/B.java"))) {
            assertRejected(() -> snapshot(paths.stream().map(path -> file(path, 0)).toList()));
        }
    }

    @Test void requiresAnExactAllowlistAndAllowsPortableTestAndWrapperFiles() {
        var files = List.of(file(".gitignore", 0), file("backend/.mvn/wrapper/maven-wrapper.properties", 0),
                file("backend/src/test/java/ExampleTest.java", 0), file("config/application.properties.example", 0));
        assertThat(snapshot(files).files()).containsExactlyElementsOf(files);
        assertRejected(() -> new TemplateSnapshot(Set.of("README.md"), files));
        assertRejected(() -> new TemplateSnapshot(Set.of("README.md", "extra.md"), List.of(file("README.md", 0))));
        assertRejected(() -> new TemplateSnapshot(Set.of(), List.of()));
    }

    @Test void countsUtf8BytesAndEnforcesSingleFileAndAggregateLimits() {
        assertThat(file("large.bin", EngineeringBundle.MAX_FILE_BYTES).size()).isEqualTo(EngineeringBundle.MAX_FILE_BYTES);
        assertRejected(() -> file("large.bin", EngineeringBundle.MAX_FILE_BYTES + 1));
        String unicode = "界".repeat(EngineeringBundle.MAX_FILE_BYTES / 3 + 1);
        assertRejected(() -> new EngineeringBundle.File("large.txt", unicode.getBytes(StandardCharsets.UTF_8)));
        var exact = IntStream.range(0, 4).mapToObj(i -> file("file" + i, EngineeringBundle.MAX_FILE_BYTES)).toList();
        assertThat(new EngineeringBundle("not-an-identity-test", exact).files()).hasSize(4);
        var over = new ArrayList<>(exact);
        over.add(file("one-more", 1));
        assertRejected(() -> new EngineeringBundle("not-an-identity-test", over));
    }

    @Test void reservesFileCountAndTotalBytesForGeneratedApplicationAndBundleManifest() {
        var assembler = new EngineeringBundleAssembler(new ArtifactValidator());
        var templateFiles = IntStream.range(0, EngineeringBundle.MAX_FILES - 5).mapToObj(i -> file("file" + i, 0)).toList();
        assertThat(assembler.assemble(source(), snapshot(templateFiles)).files()).hasSize(EngineeringBundle.MAX_FILES);
        var tooMany = new ArrayList<>(templateFiles);
        tooMany.add(file("extra", 0));
        assertRejected(() -> assembler.assemble(source(), snapshot(tooMany)));
        var fullBytes = IntStream.range(0, 4).mapToObj(i -> file("file" + i, EngineeringBundle.MAX_FILE_BYTES)).toList();
        assertRejected(() -> assembler.assemble(source(), snapshot(fullBytes)));
    }

    @Test void acceptsExactlyTheTotalByteLimitIncludingTheManifestButRejectsOneMoreByte() {
        var assembler = new EngineeringBundleAssembler(new ArtifactValidator());
        var files = new ArrayList<>(IntStream.range(0, 3)
                .mapToObj(i -> file("file" + i, EngineeringBundle.MAX_FILE_BYTES)).toList());
        files.add(file("padding", 0));
        var initial = assembler.assemble(source(), snapshot(files));
        int available = (int) (EngineeringBundle.MAX_TOTAL_BYTES - initial.files().stream().mapToLong(EngineeringBundle.File::size).sum());
        int padding = available - (Integer.toString(available).length() - 1);
        files.set(3, file("padding", padding));
        var exact = assembler.assemble(source(), snapshot(files));
        assertThat(exact.files().stream().mapToLong(EngineeringBundle.File::size).sum()).isEqualTo(EngineeringBundle.MAX_TOTAL_BYTES);
        files.set(3, file("padding", padding + 1));
        assertRejected(() -> assembler.assemble(source(), snapshot(files)));
    }

    private TemplateSnapshot snapshot(List<EngineeringBundle.File> files) {
        return new TemplateSnapshot(files.stream().map(EngineeringBundle.File::path).collect(Collectors.toSet()), files);
    }
    private EngineeringBundle.File file(String path, int size) { return new EngineeringBundle.File(path, new byte[size]); }
    private BundleSource source() {
        return new BundleSource(new UUID(0, 1), "ab".repeat(32), new Artifact(1,
                new Artifact.Manifest("Fixture", "Reviewed test fixture", "web-static-v1", 1, new ObjectMapper().createObjectNode()),
                Map.of("view.html", "<main>Fixture</main>", "styles.css", "", "main.js", "")));
    }
    private void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_ENGINEERING_BUNDLE");
    }
}
