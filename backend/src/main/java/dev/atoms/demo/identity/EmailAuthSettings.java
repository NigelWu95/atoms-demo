package dev.atoms.demo.identity;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Secrets deliberately have no generated toString or configuration-binding error echo. */
@Component
public final class EmailAuthSettings {
    private final boolean enabled;
    private final String host, username, password, key;
    private final int port;
    final int emailSends, sessionSends, ipSends, hourlySends, dailySends;
    public EmailAuthSettings(Environment env) {
        enabled = Boolean.parseBoolean(env.getProperty("EMAIL_AUTH_ENABLED", "false"));
        host = env.getProperty("SMTP_HOST", "smtp.qq.com");
        port = integer(env, "SMTP_PORT", 465, 465, 465);
        username = env.getProperty("SMTP_USERNAME", "");
        password = env.getProperty("SMTP_PASSWORD", "");
        key = env.getProperty("EMAIL_CODE_HMAC_KEY", "");
        emailSends = integer(env, "EMAIL_MAX_SENDS_PER_EMAIL", 5, 1, 20);
        sessionSends = integer(env, "EMAIL_MAX_SENDS_PER_SESSION", 10, 1, 30);
        ipSends = integer(env, "EMAIL_MAX_SENDS_PER_IP", 20, 1, 100);
        hourlySends = integer(env, "EMAIL_MAX_SENDS_PER_HOUR", 20, 1, 1000);
        dailySends = integer(env, "EMAIL_MAX_SENDS_PER_DAY", 100, 1, 10000);
        if (enabled) {
            if (!key.matches("[0-9a-fA-F]{64}")) throw bad("EMAIL_CODE_HMAC_KEY");
            try { EmailCode.email(username); } catch (RuntimeException invalid) { throw bad("SMTP_USERNAME"); }
            if (!username.equals(EmailCode.email(username))) throw bad("SMTP_USERNAME");
            if (password.isBlank() || password.contains("\r") || password.contains("\n")) throw bad("SMTP_PASSWORD");
            if (!host.matches("[a-zA-Z0-9.-]{1,253}") || host.startsWith(".") || host.endsWith(".")) throw bad("SMTP_HOST");
        }
    }
    private static int integer(Environment env, String name, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(env.getProperty(name, Integer.toString(fallback)));
            if (value < min || value > max) throw bad(name);
            return value;
        } catch (NumberFormatException invalid) { throw bad(name); }
    }
    private static IllegalStateException bad(String name) { return new IllegalStateException("Invalid email configuration: " + name); }
    public boolean enabled() { return enabled; }
    String host() { return host; }
    int port() { return port; }
    String username() { return username; }
    String password() { return password; }
    String key() { return key; }
}
