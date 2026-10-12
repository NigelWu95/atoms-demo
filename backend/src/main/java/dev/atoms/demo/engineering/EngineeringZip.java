package dev.atoms.demo.engineering;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** In-memory source archive only; never reads the filesystem or executes application code. */
final class EngineeringZip {
    static final int MAX_ZIP_BYTES = 9 * 1024 * 1024;
    private EngineeringZip() {}

    static byte[] write(EngineeringBundle bundle) throws IOException {
        TemplatePolicy.validateBundle(bundle.files());
        // ASCII paths, STORED data, no comments/extra fields: exact classic ZIP size, no ZIP64.
        long size = 22;
        for (var file : bundle.files()) size += 76L + 2L * file.path().length() + file.size();
        if (size > MAX_ZIP_BYTES) throw EngineeringBundle.rejected();
        var output = new ByteArrayOutputStream((int) size);
        try (var zip = new ZipOutputStream(output)) {
            for (var file : bundle.files().stream().sorted(Comparator.comparing(EngineeringBundle.File::path)).toList()) {
                byte[] content = file.content();
                var crc = new CRC32();
                crc.update(content);
                var entry = new ZipEntry(file.path());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(content.length);
                entry.setCompressedSize(content.length);
                entry.setCrc(crc.getValue());
                // A local DOS timestamp avoids timezone-dependent bytes and extended time fields.
                // https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/zip/ZipEntry.html#setTimeLocal(java.time.LocalDateTime)
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 2, 0, 0));
                zip.putNextEntry(entry);
                zip.write(content);
                zip.closeEntry();
            }
        }
        if (output.size() != size) throw new IOException("Unexpected ZIP metadata size");
        return output.toByteArray();
    }
}
