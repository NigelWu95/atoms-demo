package dev.atoms.demo.identity;

import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class EmailCode {
    private static final SecureRandom RANDOM = new SecureRandom();
    private EmailCode() {}
    static String email(String value) {
        if (value == null || value.length() > 260 || value.chars().anyMatch(c -> c < 32 || c > 126)) throw invalid();
        String email = value.trim().toLowerCase(Locale.ROOT);
        int at = email.indexOf('@');
        if (email.length() > 254 || at < 1 || at > 64 || at != email.lastIndexOf('@')) throw invalid();
        String local = email.substring(0, at), domain = email.substring(at + 1);
        if (!local.matches("[a-z0-9!#$%&'*+/=?^_`{|}~.-]+") || local.startsWith(".") || local.endsWith(".")
                || local.contains("..") || !domain.contains(".")) throw invalid();
        for (String label : domain.split("\\.", -1)) {
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw invalid();
        }
        return email;
    }
    private static ApiException invalid() { return new ApiException(422, "INVALID_INPUT", "请输入有效邮箱地址。"); }
    static String generate() { return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000)); }
    static String digest(String hexKey, String... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(hexKey), "HmacSHA256"));
            mac.update("atoms-email-v1".getBytes(StandardCharsets.UTF_8));
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                mac.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                mac.update(bytes);
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("HMAC unavailable"); }
    }
    static boolean matches(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
}
