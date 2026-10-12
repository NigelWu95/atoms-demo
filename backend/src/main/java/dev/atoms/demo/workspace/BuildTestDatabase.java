package dev.atoms.demo.workspace;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Read-only preflight, before the template's own independently guarded test bootstrap. */
final class BuildTestDatabase {
    private BuildTestDatabase() {}
    private static final Set<String> KEYS = Set.of("STANDALONE_DB_URL", "STANDALONE_DB_USER", "STANDALONE_DB_PASSWORD",
            "APP_INSTANCE_ID", "server.address", "server.port", "standalone.origin", "standalone.test-database");
    // Mirrors the standalone installation contract. Qualified catalogs cannot be shadowed.
    private static final String SQL = """
            SELECT pg_catalog.current_database(), current_user,
              pg_catalog.shobj_description(d.oid, 'pg_database'), pg_catalog.shobj_description(r.oid, 'pg_authid'),
              current_user = session_user AND r.rolcanlogin
                AND NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls)
                AND NOT EXISTS (SELECT FROM pg_catalog.pg_auth_members WHERE member = r.oid OR roleid = r.oid),
              d.datdba <> r.oid AND pg_catalog.has_database_privilege(r.oid, d.oid, 'CONNECT')
                AND NOT pg_catalog.has_database_privilege(r.oid, d.oid, 'CREATE')
                AND NOT pg_catalog.has_database_privilege(r.oid, d.oid, 'TEMP'),
              s.nspowner = r.oid AND pg_catalog.has_schema_privilege(r.oid, s.oid, 'USAGE')
                AND pg_catalog.has_schema_privilege(r.oid, s.oid, 'CREATE')
                AND NOT EXISTS (SELECT FROM pg_catalog.pg_namespace WHERE nspname = 'atoms')
                AND NOT EXISTS (SELECT FROM pg_catalog.pg_namespace n WHERE n.nspname <> 'standalone_app'
                  AND n.nspname <> 'information_schema' AND n.nspname !~ '^pg_'
                  AND (pg_catalog.has_schema_privilege(r.oid, n.oid, 'USAGE') OR pg_catalog.has_schema_privilege(r.oid, n.oid, 'CREATE'))),
              pg_catalog.current_setting('transaction_read_only') = 'on'
            FROM pg_catalog.pg_database d JOIN pg_catalog.pg_roles r ON r.rolname = current_user
            LEFT JOIN pg_catalog.pg_namespace s ON s.nspname = 'standalone_app'
            WHERE d.datname = pg_catalog.current_database()
            """;

    static void verify(Path config) {
        try {
            if (!config.isAbsolute() || !config.toString().endsWith(".properties")) throw WorkspacePaths.rejected();
            var values = new Properties();
            values.load(new ByteArrayInputStream(BuildArtifacts.read(config, 16384)));
            if (!KEYS.containsAll(values.stringPropertyNames()) || !"true".equals(values.getProperty("standalone.test-database")))
                throw WorkspacePaths.rejected();
            String url = required(values, "STANDALONE_DB_URL"), role = required(values, "STANDALONE_DB_USER");
            if (!url.startsWith("jdbc:postgresql://")) throw WorkspacePaths.rejected();
            URI uri = URI.create(url.substring(5));
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535)
                    || !uri.getRawPath().matches("/[A-Za-z_][A-Za-z_0-9]{0,62}")
                    || !role.matches("[A-Za-z_][A-Za-z_0-9]{0,62}")) throw WorkspacePaths.rejected();
            String id = required(values, "APP_INSTANCE_ID");
            UUID instance = UUID.fromString(id);
            if (instance.version() != 4 || instance.variant() != 2 || !instance.toString().equals(id)) throw WorkspacePaths.rejected();
            String marker = "atoms-standalone:test:" + id;
            var connectionProperties = new Properties();
            connectionProperties.setProperty("user", role);
            connectionProperties.setProperty("password", required(values, "STANDALONE_DB_PASSWORD"));
            connectionProperties.setProperty("connectTimeout", "3");
            connectionProperties.setProperty("socketTimeout", "5");
            connectionProperties.setProperty("currentSchema", "pg_catalog");
            // https://jdbc.postgresql.org/documentation/use/#connection-parameters
            connectionProperties.setProperty("readOnly", "true");
            connectionProperties.setProperty("readOnlyMode", "always");
            connectionProperties.setProperty("logServerErrorDetail", "false");
            try (var connection = DriverManager.getConnection(url, connectionProperties)) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement(SQL)) {
                    statement.setQueryTimeout(3);
                    try (var row = statement.executeQuery()) {
                        if (!row.next() || !uri.getPath().substring(1).equals(row.getString(1)) || !role.equals(row.getString(2))
                                || !marker.equals(row.getString(3)) || !marker.equals(row.getString(4))
                                || !row.getBoolean(5) || !row.getBoolean(6) || !row.getBoolean(7) || !row.getBoolean(8))
                            throw WorkspacePaths.rejected();
                    }
                    installation(connection, instance);
                } finally { connection.rollback(); }
            }
        } catch (Exception error) { throw BuildEnvironment.unavailable(); }
    }

    private static void installation(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT c.relkind = 'r' AND c.relowner = r.oid AND NOT c.relrowsecurity
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_catalog.pg_roles r ON r.rolname = current_user
                WHERE n.nspname = 'standalone_app' AND c.relname = 'installation'
                """)) {
            statement.setQueryTimeout(3);
            try (var row = statement.executeQuery()) {
                if (!row.next()) {
                    try (var empty = connection.prepareStatement("""
                            SELECT NOT EXISTS (SELECT FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='standalone_app')
                              AND NOT EXISTS (SELECT FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='standalone_app')
                              AND NOT EXISTS (SELECT FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='standalone_app')
                            """)) {
                        empty.setQueryTimeout(3);
                        try (var result = empty.executeQuery()) { if (!result.next() || !result.getBoolean(1)) throw WorkspacePaths.rejected(); }
                    }
                    return;
                }
                if (!row.getBoolean(1)) throw WorkspacePaths.rejected();
            }
        }
        try (var statement = connection.prepareStatement("SELECT singleton, instance_id, format_version FROM standalone_app.installation LIMIT 2")) {
            statement.setQueryTimeout(3);
            try (var row = statement.executeQuery()) {
                if (!row.next() || !row.getBoolean(1) || !id.equals(row.getObject(2, UUID.class)) || row.getInt(3) != 1 || row.next())
                    throw WorkspacePaths.rejected();
            }
        }
    }
    private static String required(Properties values, String key) throws Exception {
        String value = values.getProperty(key);
        if (value == null || value.isBlank() || value.contains("${") || value.chars().anyMatch(Character::isISOControl)) throw WorkspacePaths.rejected();
        return value;
    }
}
