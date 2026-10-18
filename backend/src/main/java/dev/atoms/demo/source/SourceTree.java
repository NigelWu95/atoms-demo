package dev.atoms.demo.source;

import java.util.List;
import java.util.Objects;

/** Immutable text source snapshot; generated artifacts and runtime configuration are not source. */
public record SourceTree(List<File> files, String sha256, long totalBytes) {
    public SourceTree { files = List.copyOf(files); Objects.requireNonNull(sha256); }
    public record File(String path, byte[] content, int mode) {
        public File { Objects.requireNonNull(path); content = Objects.requireNonNull(content).clone(); }
        @Override public byte[] content() { return content.clone(); }
        public int size() { return content.length; }
    }
}
