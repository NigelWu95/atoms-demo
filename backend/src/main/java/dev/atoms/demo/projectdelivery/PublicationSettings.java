package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.common.ApiException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Explicit operator configuration, never a host or destination supplied by a browser. */
@Component
final class PublicationSettings {
    private final Environment env;
    PublicationSettings(Environment env) { this.env = env; }
    Configuration load() {
        try {
            String enabled = value("enabled", "false");
            if (enabled.equals("false")) return null;
            if (!enabled.equals("true")) throw invalid();
            String host = value("host", ""), bind = value("bind-address", "127.0.0.1");
            if (!ipv4(host) || !(bind.equals("127.0.0.1") || bind.equals("0.0.0.0"))) throw invalid();
            String raw = value("port-offset", "10000");
            if (!raw.matches("[1-9][0-9]{0,4}")) throw invalid();
            int offset = Integer.parseInt(raw);
            if (offset > 40000) throw invalid();
            return new Configuration(host, bind, offset);
        } catch (RuntimeException failure) { throw invalid(); }
    }
    record Configuration(String host, String bindAddress, int offset) {
        int port(int internal) {
            if (internal < 1024 || internal > 65535 - offset) throw invalid();
            return internal + offset;
        }
        String origin(int internal) { return "http://" + host + ":" + port(internal); }
    }
    static boolean ipv4(String host) {
        if (host == null || !host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        String[] parts = host.split("\\.");
        for (String part : parts) if (Integer.parseInt(part) > 255) return false;
        int first = Integer.parseInt(parts[0]);
        return first > 0 && first < 224 && !host.equals("169.254.169.254");
    }
    private String value(String key, String fallback) { return env.getProperty("atoms.project-publication." + key, fallback); }
    static ApiException invalid() { return new ApiException(503, "PUBLICATION_CONFIGURATION_INVALID", "应用端口发布配置无效，请检查服务器配置。"); }
}
