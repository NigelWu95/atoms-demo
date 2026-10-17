package dev.atoms.demo.identity;

import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
final class PasswordPolicy {
    private final PasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    private final Semaphore slots = new Semaphore(2);
    private final String dummy = encoder.encode(java.util.UUID.randomUUID().toString());
    static String validate(String value) {
        if (value == null || value.codePointCount(0,value.length()) < 15 || value.codePointCount(0,value.length()) > 64
                || value.getBytes(StandardCharsets.UTF_8).length > 72 || value.indexOf('\0') >= 0
                || value.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF))
            throw new ApiException(422,"INVALID_INPUT","密码需 15–64 个字符，UTF-8 不超过 72 字节；支持空格，不会自动去除首尾空格。");
        return value;
    }
    String encode(String value) { return encoder.encode(validate(value)); }
    boolean matches(String value, String hash) {
        try { return encoder.matches(value, hash == null ? dummy : hash); }
        catch (IllegalArgumentException invalid) { encoder.matches(value, dummy); return false; }
    }
    <T> T bounded(Supplier<T> action) {
        if (!slots.tryAcquire()) throw new AuthRateLimited(1);
        try { return action.get(); } finally { slots.release(); }
    }
}
