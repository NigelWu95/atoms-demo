package dev.atoms.generated;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Test-only bootstrap. Business tests must use @Transactional and roll back their DDL/data. */
public final class PrivateTestDatabase implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final Set<String> KEYS = Set.of("purpose", "database", "user", "password", "schema", "bindingId", "jdbcUrl");
    private static final Set<String> RESERVED = Set.of("postgres", "template0", "template1", "atoms_demo", "atoms_demo_test", "atoms_test");

    @Override public void initialize(ConfigurableApplicationContext context) {
        try {
            Path backend = Path.of(System.getProperty("user.dir")).toRealPath();
            if (!backend.getFileName().toString().equals("backend")) throw rejected();
            var env = context.getEnvironment();
            String explicit = env.getProperty("full-project.test-config");
            String managed = env.getProperty("APP_TEST_CONFIG");
            if (explicit != null && managed != null && !explicit.equals(managed)) throw rejected();
            var config = read(backend.getParent(), explicit == null ? managed : explicit);
            config.verifyDatabase();
            config.verifyFile();
            var values = new HashMap<String, Object>();
            values.put("APP_PORT", "0"); values.put("spring.flyway.enabled", "false");
            values.put("spring.sql.init.mode", "never");
            values.put("spring.datasource.url", config.values.getProperty("jdbcUrl"));
            values.put("spring.datasource.username", config.values.getProperty("user"));
            values.put("spring.datasource.password", config.values.getProperty("password"));
            env.getPropertySources().addFirst(new MapPropertySource("verified-private-test-database", values));
        } catch (Exception error) { throw rejected(); }
    }

    static Configuration read(Path project, String raw) {
        try {
            if (raw == null || !raw.endsWith(".properties") || raw.codePoints().anyMatch(Character::isISOControl)) throw rejected();
            Path file = Path.of(raw), root = project.toRealPath();
            if (!file.isAbsolute() || !file.normalize().equals(file) || file.startsWith(root)) throw rejected();
            var before = attributes(file);
            byte[] bytes = bytes(file);
            var values = new Properties() {
                @Override public synchronized Object put(Object key, Object value) {
                    if (containsKey(key)) throw rejected();
                    return super.put(key, value);
                }
            };
            try (var input = new ByteArrayInputStream(bytes)) { values.load(input); }
            if (!values.stringPropertyNames().equals(KEYS)) throw rejected();
            for (String key : KEYS) {
                String value = values.getProperty(key);
                if (value.isBlank() || value.length() > 512 || value.contains("${")
                        || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff)) throw rejected();
            }
            for (String key : List.of("database", "user")) {
                String name = values.getProperty(key);
                if (!name.matches("[a-z][a-z0-9_]{0,62}") || name.startsWith("pg_") || RESERVED.contains(name)) throw rejected();
            }
            if (!values.getProperty("purpose").equals("test") || !values.getProperty("schema").equals("generated_app")) throw rejected();
            UUID id = UUID.fromString(values.getProperty("bindingId"));
            if (!id.toString().equals(values.getProperty("bindingId")) || id.version() != 4 || id.variant() != 2) throw rejected();
            var url = java.util.regex.Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/"
                    + values.getProperty("database") + "\\?currentSchema=generated_app").matcher(values.getProperty("jdbcUrl"));
            if (!url.matches() || Integer.parseInt(url.group(1)) > 65535) throw rejected();
            var config = new Configuration(file, before.fileKey(), MessageDigest.getInstance("SHA-256").digest(bytes), values);
            config.verifyFile();
            return config;
        } catch (Exception error) { throw rejected(); }
    }

    static final class Configuration {
        private final Path file;
        private final Object key;
        private final byte[] digest;
        private final Properties values;
        private Configuration(Path file, Object key, byte[] digest, Properties values) {
            this.file = file; this.key = key; this.digest = digest; this.values = values;
        }
        void verifyFile() {
            try {
                if (!key.equals(attributes(file).fileKey())
                        || !MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(bytes(file)))
                        || !key.equals(attributes(file).fileKey())) throw rejected();
            } catch (Exception error) { throw rejected(); }
        }
        private void verifyDatabase() throws Exception {
            verifyFile();
            var properties = new Properties();
            properties.setProperty("user", values.getProperty("user"));
            properties.setProperty("password", values.getProperty("password"));
            properties.setProperty("currentSchema", "generated_app");
            properties.setProperty("connectTimeout", "3"); properties.setProperty("socketTimeout", "5");
            properties.setProperty("readOnly", "true"); properties.setProperty("readOnlyMode", "transaction");
            properties.setProperty("sslmode", "disable"); properties.setProperty("gssEncMode", "disable");
            properties.setProperty("jaasLogin", "false");
            String marker = "atoms-project-execution:test:" + values.getProperty("bindingId");
            try (var connection = DriverManager.getConnection(values.getProperty("jdbcUrl").split("\\?", 2)[0], properties)) {
                connection.setReadOnly(true); connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("""
                        SELECT current_database() AS database_name, current_user AS role_name, session_user AS session_name,
                          current_schema() AS schema_name, shobj_description(d.oid,'pg_database') AS database_marker,
                          shobj_description(r.oid,'pg_authid') AS role_marker,
                          current_setting('transaction_read_only')='on' AS readonly,
                          NOT d.datistemplate AND d.datallowconn AND NOT pg_is_in_recovery() AS writable_target,
                          r.rolcanlogin AND NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls)
                            AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid) AS limited_role,
                          n.nspowner=r.oid AND has_schema_privilege(n.oid,'USAGE') AND has_schema_privilege(n.oid,'CREATE') AS owns_schema,
                          NOT EXISTS(SELECT FROM pg_class WHERE relnamespace=n.oid)
                            AND NOT EXISTS(SELECT FROM pg_proc WHERE pronamespace=n.oid)
                            AND NOT EXISTS(SELECT FROM pg_type WHERE typnamespace=n.oid) AS empty_schema
                        FROM pg_database d CROSS JOIN pg_roles r CROSS JOIN pg_namespace n
                        WHERE d.datname=current_database() AND r.rolname=current_user AND n.nspname='generated_app'
                        """)) {
                    statement.setQueryTimeout(5);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next() || !values.getProperty("database").equals(rows.getString("database_name"))
                                || !values.getProperty("user").equals(rows.getString("role_name"))
                                || !values.getProperty("user").equals(rows.getString("session_name"))
                                || !"generated_app".equals(rows.getString("schema_name"))
                                || !marker.equals(rows.getString("database_marker")) || !marker.equals(rows.getString("role_marker"))
                                || !rows.getBoolean("readonly") || !rows.getBoolean("writable_target")
                                || !rows.getBoolean("limited_role") || !rows.getBoolean("owns_schema") || !rows.getBoolean("empty_schema")) throw rejected();
                    }
                } finally { connection.rollback(); }
            }
        }
    }

    private static BasicFileAttributes attributes(Path file) throws IOException {
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        int mode = ((Number) Files.getAttribute(file, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
        long uid = ((Number) Files.getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
        if (!attributes.isRegularFile() || attributes.fileKey() == null || attributes.size() == 0 || attributes.size() > 16384
                || (mode & 0077) != 0 || (mode & 0400) == 0 || uid != new com.sun.security.auth.module.UnixSystem().getUid()
                || ((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() != 1
                || !file.toRealPath().equals(file)) throw rejected();
        return attributes;
    }
    private static byte[] bytes(Path file) throws IOException {
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(16385);
            if (bytes.length == 0 || bytes.length > 16384) throw rejected();
            return bytes;
        }
    }
    private static IllegalStateException rejected() {
        return new IllegalStateException("Explicit private test database configuration required");
    }
}
