package dev.atoms.standalone;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Validates the explicit runtime file before Spring can initialize any database beans. */
final class StartupConfig {
    private static final String LOCATION = "--spring.config.location=classpath:/application.yml,file:";
    private static final int MAX_CONFIG_BYTES = 16 * 1024;
    private static final Set<String> KEYS = Set.of("STANDALONE_DB_URL", "STANDALONE_DB_USER",
            "STANDALONE_DB_PASSWORD", "APP_INSTANCE_ID", "server.address", "server.port",
            "standalone.origin", "standalone.test-database", "RUNTIME_EXECUTION_ID");
    private static final Set<String> DEFAULT_CREDENTIALS = Set.of(
            "password", "postgres", "root", "admin", "changeme", "change_me", "replace_me");

    private StartupConfig() {}

    static ConfigurableEnvironment load(String... args) {
        return runtimeEnvironment(read(location(args)));
    }

    static ConfigurableEnvironment runtimeEnvironment(Properties config) {
        if (!KEYS.containsAll(config.stringPropertyNames())) throw invalid("supported configuration keys");
        if (!"false".equals(config.getProperty("standalone.test-database", "false"))) {
            throw invalid("runtime configuration must not use a test database file");
        }
        String url = required(config, "STANDALONE_DB_URL");
        validateDatabaseUrl(url);
        String user = required(config, "STANDALONE_DB_USER");
        String password = required(config, "STANDALONE_DB_PASSWORD");
        if (!user.matches("[a-zA-Z_][a-zA-Z0-9_]{0,62}") || isDefault(user) || isDefault(password)) {
            throw invalid("database credentials");
        }
        String id = required(config, "APP_INSTANCE_ID").toLowerCase(Locale.ROOT);
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw invalid("APP_INSTANCE_ID (canonical UUID v4 required)");
        }
        String address = required(config, "server.address");
        if (!Set.of("127.0.0.1", "::1", "0.0.0.0", "::").contains(address)) throw invalid("server.address");
        int port = port(required(config, "server.port"));
        URI origin = origin(required(config, "standalone.origin"));
        boolean secure = origin.getScheme().equals("https");
        if (!secure && !localHttp(address, port, origin)) throw invalid("HTTP origin and loopback listener");

        Map<String, Object> values = new HashMap<>();
        if (config.containsKey("RUNTIME_EXECUTION_ID")) {
            String execution = required(config, "RUNTIME_EXECUTION_ID");
            if (!execution.matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}"))
                throw invalid("RUNTIME_EXECUTION_ID");
            values.put("standalone.execution-id", execution);
        }
        values.put("spring.datasource.url", url);
        values.put("spring.datasource.username", user);
        values.put("spring.datasource.password", password);
        values.put("server.address", address);
        values.put("server.port", port);
        values.put("standalone.instance-id", id);
        values.put("standalone.origin", origin.toString().replaceAll("/$", ""));
        values.put("server.servlet.session.cookie.name", "STANDALONE_" + id);
        values.put("server.servlet.session.cookie.secure", secure);
        // Load the external file once, above. Never let ConfigData re-read it, import
        // another file, or load working-directory application[-profile].properties.
        values.put("spring.config.location", "classpath:/application.yml");
        // A standalone installation deliberately does not inherit platform/Spring env.
        // https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/core/env/StandardEnvironment.html
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("validatedRuntimeConfig", Map.copyOf(values)));
        return environment;
    }

    private static Path location(String[] args) {
        if (args.length == 1 && args[0].startsWith(LOCATION)) {
            String path = args[0].substring(LOCATION.length());
            try {
                Path file = Path.of(path);
                if (file.isAbsolute() && path.endsWith(".properties") && !path.contains(",") && !path.contains(";")) {
                    return file;
                }
            } catch (IllegalArgumentException ignored) {
                // Never include the raw path or parsing exception in output.
            }
        }
        throw new IllegalStateException("CONFIG_USAGE: supply only --spring.config.location=classpath:/application.yml,file:/absolute/path/application.properties");
    }

    static Properties read(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_CONFIG_BYTES + 1);
                if (bytes.length > MAX_CONFIG_BYTES) throw new IOException();
                var config = new Properties();
                // Standard .properties encoding/escapes; parser details may contain secrets.
                // https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Properties.html#load(java.io.InputStream)
                config.load(new ByteArrayInputStream(bytes));
                return config;
            }
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            throw new IllegalStateException("CONFIG_READ_FAILED: supply a readable regular .properties file of at most 16 KiB with valid Java properties syntax.");
        }
    }

    private static String required(Properties config, String key) {
        String value = config.getProperty(key);
        if (value == null || value.isBlank() || value.contains("${") || value.contains("REPLACE_WITH_")
                || value.contains("GENERATE_") || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(key);
        }
        return value;
    }

    private static boolean isDefault(String value) {
        return DEFAULT_CREDENTIALS.contains(value.strip().toLowerCase(Locale.ROOT));
    }

    private static void validateDatabaseUrl(String value) {
        try {
            if (!value.startsWith("jdbc:postgresql://")) throw invalid("STANDALONE_DB_URL");
            URI uri = URI.create(value.substring("jdbc:".length()));
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !validOptionalPort(uri.getPort())
                    || !uri.getRawPath().matches("/[a-zA-Z_][a-zA-Z0-9_]{0,62}")) {
                throw invalid("STANDALONE_DB_URL");
            }
        } catch (IllegalArgumentException ignored) {
            throw invalid("STANDALONE_DB_URL");
        }
    }

    private static int port(String value) {
        try {
            if (!value.matches("[0-9]{1,5}")) throw invalid("server.port");
            int port = Integer.parseInt(value);
            if (port >= 1 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {
            // Values are never returned in error messages.
        }
        throw invalid("server.port");
    }

    private static URI origin(String value) {
        try {
            URI uri = URI.create(value);
            if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && validOptionalPort(uri.getPort())
                    && (uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) return uri;
        } catch (IllegalArgumentException ignored) {
            // URI syntax errors contain the original value, so discard their cause.
        }
        throw invalid("standalone.origin");
    }

    private static boolean validOptionalPort(int port) {
        return port == -1 || (port >= 1 && port <= 65535);
    }

    private static boolean localHttp(String address, int port, URI origin) {
        int originPort = origin.getPort() == -1 ? 80 : origin.getPort();
        return originPort == port && ((address.equals("127.0.0.1") && origin.getHost().equals("127.0.0.1"))
                || (address.equals("::1") && origin.getHost().equals("[::1]")));
    }

    private static IllegalStateException invalid(String field) {
        return new IllegalStateException("CONFIG_INVALID: check " + field + ".");
    }
}
