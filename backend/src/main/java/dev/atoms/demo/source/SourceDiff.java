package dev.atoms.demo.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Bounded display text, not an applicable patch or an input to snapshot integrity checks. */
final class SourceDiff {
    record Change(String path, String kind, String beforeSha256, String afterSha256,
            Integer beforeMode, Integer afterMode, long beforeBytes, long afterBytes) {}
    record Result(List<Change> changes, String preview, boolean truncated) {
        Result { changes = List.copyOf(changes); }
    }

    Result compare(SourceTree before, SourceTree after) {
        var oldFiles = index(before); var newFiles = index(after);
        var paths = new TreeSet<>(oldFiles.keySet()); paths.addAll(newFiles.keySet());
        var changes = new ArrayList<Change>();
        var preview = new Preview();
        for (String path : paths) {
            var old = oldFiles.get(path); var next = newFiles.get(path);
            if (old != null && next != null && old.mode() == next.mode() && Arrays.equals(old.content(), next.content())) continue;
            changes.add(new Change(path, old == null ? "ADDED" : next == null ? "DELETED" : "MODIFIED",
                    hash(old), hash(next), old == null ? null : old.mode(), next == null ? null : next.mode(),
                    old == null ? 0 : old.size(), next == null ? 0 : next.size()));
            render(preview, path, old, next);
        }
        return new Result(changes, preview.text.toString(), preview.truncated);
    }

    private static void render(Preview output, String path, SourceTree.File old, SourceTree.File next) {
        if (output.truncated) return;
        output.append("--- " + (old == null ? "/dev/null" : "a/" + path) + "\n+++ " + (next == null ? "/dev/null" : "b/" + path) + "\n");
        if (old != null && next != null && old.mode() != next.mode())
            output.append("old mode " + Integer.toOctalString(old.mode()) + "\nnew mode " + Integer.toOctalString(next.mode()) + "\n");
        String[] a = lines(old), b = lines(next);
        int prefix = 0, endA = a.length, endB = b.length;
        while (prefix < endA && prefix < endB && a[prefix].equals(b[prefix])) prefix++;
        while (endA > prefix && endB > prefix && a[endA - 1].equals(b[endB - 1])) { endA--; endB--; }
        if (endA == prefix && endB == prefix) return;
        output.append("@@ -" + (prefix + 1) + "," + (endA - prefix) + " +" + (prefix + 1) + "," + (endB - prefix) + " @@\n");
        for (int i = prefix; i < endA && !output.truncated; i++) output.append("-" + a[i] + "\n");
        for (int i = prefix; i < endB && !output.truncated; i++) output.append("+" + b[i] + "\n");
    }

    private static String[] lines(SourceTree.File file) {
        return file == null ? new String[0] : new String(file.content(), StandardCharsets.UTF_8).split("\n", -1);
    }
    private static Map<String, SourceTree.File> index(SourceTree tree) {
        return tree.files().stream().collect(Collectors.toMap(SourceTree.File::path, Function.identity()));
    }
    private static String hash(SourceTree.File file) {
        if (file == null) return null;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.content())); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("Source digest unavailable", error); }
    }

    private static final class Preview {
        final StringBuilder text = new StringBuilder();
        int remaining = 128 << 10;
        boolean truncated;
        void append(String value) {
            if (truncated) return;
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            int length = Math.min(remaining, bytes.length);
            if (length < bytes.length) {
                truncated = true;
                while (length > 0 && (bytes[length] & 0xc0) == 0x80) length--;
            }
            text.append(new String(bytes, 0, length, StandardCharsets.UTF_8));
            remaining -= length;
        }
    }
}
