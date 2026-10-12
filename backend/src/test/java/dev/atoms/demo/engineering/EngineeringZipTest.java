package dev.atoms.demo.engineering;

import dev.atoms.demo.common.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EngineeringZipTest {
    @Test void writesStableStoredEntriesWithExactBytesAndCrcInSortedOrder() throws Exception {
        var files = List.of(file("application/main.js", "// 测试"), file("README.md", "readme"));
        var bundle = new EngineeringBundle("fixture", files);
        byte[] first = EngineeringZip.write(bundle);
        assertThat(EngineeringZip.write(new EngineeringBundle("fixture", List.of(files.get(1), files.get(0)))))
                .isEqualTo(first);
        assertThat(first.length).isLessThan(EngineeringZip.MAX_ZIP_BYTES);
        var names = new ArrayList<String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(first))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                byte[] bytes = zip.readAllBytes();
                var crc = new CRC32();
                crc.update(bytes);
                assertThat(entry.getCrc()).isEqualTo(crc.getValue());
                assertThat(entry.getMethod()).isEqualTo(java.util.zip.ZipEntry.STORED);
                assertThat(entry.getTimeLocal()).isEqualTo(LocalDateTime.of(1980, 1, 2, 0, 0));
                String name = entry.getName();
                assertThat(bytes).isEqualTo(files.stream().filter(f -> f.path().equals(name)).findFirst().orElseThrow().content());
            }
        }
        assertThat(names).containsExactly("README.md", "application/main.js");
    }

    @Test void rejectsUnsafePathsAndDuplicateOrConflictingEntriesBeforeWriting() {
        for (String path : List.of("../escape", "/absolute", "x\\y", ".env", "target/out", "a//b", "a/./b",
                "application/other.js", "bundle-manifest.json/file", "x".repeat(65536))) {
            reject(List.of(file(path, "x")));
        }
        reject(List.of());
        reject(List.of(file("a", "1"), file("a", "2")));
        reject(List.of(file("a", "1"), file("A", "2")));
        reject(List.of(file("a", "1"), file("a/b", "2")));
        reject(List.of(file("App/a", "1"), file("app/b", "2")));
    }

    @Test void boundsZipMetadataEvenWhenSourceBytesAreSmall() {
        var files = new ArrayList<EngineeringBundle.File>();
        for (int i = 0; i < 128; i++) files.add(file(i + "x".repeat(40000), ""));
        reject(files);
    }

    private void reject(List<EngineeringBundle.File> files) {
        assertThatThrownBy(() -> EngineeringZip.write(new EngineeringBundle("fixture", files)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_ENGINEERING_BUNDLE");
    }
    private EngineeringBundle.File file(String path, String content) {
        return new EngineeringBundle.File(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
