package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.common.ApiException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Lazy delivery-only settings; no execution resources or URLs derived from request headers. */
@Component
final class DeliverySettings {
    static final Duration GRANT_TTL = Duration.ofMinutes(10), REQUEST_TIMEOUT = Duration.ofSeconds(30);
    static final int GRANTS = 32, SESSION_GRANTS = 8, EXCHANGES = 16, GRANT_EXCHANGES = 4;
    static final int DOWNLOADS = 2, SESSION_DOWNLOADS = 1;
    private final Environment environment;

    DeliverySettings(Environment environment) { this.environment = environment; }

    boolean enabled() {
        String value = value("enabled", "false");
        if (!value.equals("true") && !value.equals("false")) throw invalid();
        return value.equals("true");
    }

    Configuration load() {
        if (!enabled()) throw new ApiException(503, "DELIVERY_DISABLED", "完整工程交付尚未启用。");
        try {
            String raw = value("origin", "");
            if (raw.isBlank() || raw.length() > 512 || !raw.equals(raw.trim())
                    || raw.codePoints().anyMatch(Character::isISOControl)) throw invalid();
            var origin = new URI(raw);
            if (!("http".equals(origin.getScheme()) || "https".equals(origin.getScheme())) || origin.getHost() == null
                    || origin.getUserInfo() != null || !origin.getRawPath().isEmpty() || origin.getRawQuery() != null
                    || origin.getRawFragment() != null || origin.getPort() == 0 || origin.getPort() > 65535) throw invalid();
            int port = origin.getPort();
            if (origin.getScheme().equals("http") && port == 80 || origin.getScheme().equals("https") && port == 443) port = -1;
            return new Configuration(new URI(origin.getScheme(), null, origin.getHost().toLowerCase(Locale.ROOT),
                    port, null, null, null).toASCIIString());
        } catch (Exception error) { throw invalid(); }
    }

    record Configuration(String origin) {}

    private String value(String key, String fallback) {
        try {
            String value = environment.getProperty("atoms.project-delivery." + key);
            return value == null ? fallback : value;
        } catch (IllegalArgumentException error) { throw invalid(); }
    }
    private static ApiException invalid() {
        return new ApiException(503, "DELIVERY_CONFIGURATION_INVALID", "完整工程交付配置无效，请检查平台访问地址。");
    }
}
