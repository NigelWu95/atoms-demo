package dev.atoms.demo.engineering;

import dev.atoms.demo.common.ApiException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Source files only. No build, filesystem materialization, deployment or credentials. */
public record EngineeringBundle(String bundleId, List<File> files) {
    public static final int MAX_FILES = 128;
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024;

    public EngineeringBundle {
        files = List.copyOf(files);
        checkSize(files);
    }

    public record File(String path, byte[] content) {
        public File {
            Objects.requireNonNull(path, "File path is required");
            if (content.length > MAX_FILE_BYTES) throw rejected();
            content = content.clone();
        }
        @Override public byte[] content() { return content.clone(); }
        public int size() { return content.length; }
        // Defensive array copies must still preserve the record's value/copy contract.
        // https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Record.html
        @Override public boolean equals(Object other) {
            return other instanceof File file && path.equals(file.path) && Arrays.equals(content, file.content);
        }
        @Override public int hashCode() { return 31 * path.hashCode() + Arrays.hashCode(content); }
    }

    static void checkSize(List<File> files) {
        if (files.size() > MAX_FILES || files.stream().mapToLong(File::size).sum() > MAX_TOTAL_BYTES)
            throw rejected();
    }
    static ApiException rejected() {
        return new ApiException(422, "INVALID_ENGINEERING_BUNDLE", "工程文件不符合固定模板、路径或体积限制。");
    }
}
