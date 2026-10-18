package dev.atoms.demo.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Validated metadata, not a filesystem path or a client-supplied Git reference. */
record SourceRequest(String kind, UUID expectedVersionId, long expectedRevision, UUID targetVersionId,
        UUID legacyVersionId, String templateId, String templateDigest, String treeSha256) {
    SourceRequest {
        if (kind == null || !Set.of("INITIALIZE", "IMPORT", "ACCEPT", "RESTORE").contains(kind)
                || expectedRevision < 0 || (expectedVersionId == null) != (expectedRevision == 0)
                || treeSha256 == null || !treeSha256.matches("[a-f0-9]{64}")
                || (templateId == null) != (templateDigest == null)
                || templateId != null && (!templateId.matches("[a-z0-9][a-z0-9-]{0,63}") || !templateDigest.matches("[a-f0-9]{64}"))
                || Set.of("INITIALIZE", "IMPORT").contains(kind) && expectedRevision != 0
                || Set.of("ACCEPT", "RESTORE").contains(kind) && expectedRevision == 0
                || kind.equals("RESTORE") != (targetVersionId != null)
                || kind.equals("IMPORT") != (legacyVersionId != null)
                || kind.equals("IMPORT") && templateId == null) throw new IllegalArgumentException("Invalid source operation metadata");
    }
    String digest() {
        return hash(String.join("|", "source-request-v1", kind, String.valueOf(expectedVersionId), Long.toString(expectedRevision),
                String.valueOf(targetVersionId), String.valueOf(legacyVersionId), String.valueOf(templateId), String.valueOf(templateDigest), treeSha256));
    }
    static String key(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || key.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Source idempotency key must contain 1–128 characters");
        return hash(key);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("Source request digest unavailable", error); }
    }
}
