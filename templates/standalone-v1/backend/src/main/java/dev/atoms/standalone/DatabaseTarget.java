package dev.atoms.standalone;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Catalog-only checks shared by runtime migration and the stricter test bootstrap. */
record DatabaseTarget(String database, String role, String databaseMarker, String roleMarker,
                      boolean safeRole, boolean safeDatabase, boolean safeSchema) {
    // Fully qualified catalog access cannot be shadowed by an application's search_path.
    // https://www.postgresql.org/docs/17/functions-info.html
    private static final String SQL = """
            SELECT pg_catalog.current_database(), current_user,
              pg_catalog.shobj_description(d.oid, 'pg_database'),
              pg_catalog.shobj_description(r.oid, 'pg_authid'),
              (current_user = session_user AND r.rolcanlogin
                AND NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls)
                AND NOT EXISTS (SELECT FROM pg_catalog.pg_auth_members WHERE member = r.oid OR roleid = r.oid)),
              (d.datdba <> r.oid AND pg_catalog.has_database_privilege(r.oid, d.oid, 'CONNECT')
                AND NOT pg_catalog.has_database_privilege(r.oid, d.oid, 'CREATE')
                AND NOT pg_catalog.has_database_privilege(r.oid, d.oid, 'TEMP')),
              (s.nspowner = r.oid AND pg_catalog.has_schema_privilege(r.oid, s.oid, 'USAGE')
                AND pg_catalog.has_schema_privilege(r.oid, s.oid, 'CREATE')
                AND NOT EXISTS (SELECT FROM pg_catalog.pg_namespace WHERE nspname = 'atoms')
                AND NOT EXISTS (SELECT FROM pg_catalog.pg_namespace n
                  WHERE n.nspname <> 'standalone_app' AND n.nspname <> 'information_schema'
                    AND n.nspname !~ '^pg_'
                    AND (pg_catalog.has_schema_privilege(r.oid, n.oid, 'USAGE')
                      OR pg_catalog.has_schema_privilege(r.oid, n.oid, 'CREATE'))))
            FROM pg_catalog.pg_database d JOIN pg_catalog.pg_roles r ON r.rolname = current_user
            LEFT JOIN pg_catalog.pg_namespace s ON s.nspname = 'standalone_app'
            WHERE d.datname = pg_catalog.current_database()
            """;

    static DatabaseTarget inspect(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(SQL)) {
            statement.setQueryTimeout(3);
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw unsafe();
                return new DatabaseTarget(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                        row.getBoolean(5), row.getBoolean(6), row.getBoolean(7));
            }
        }
    }

    void require(String expectedDatabase, String expectedRole, boolean test) {
        boolean unmarkedRuntime = !test && databaseMarker == null && roleMarker == null;
        String purpose = test ? "test" : "runtime";
        boolean markedPurpose = databaseMarker != null && Objects.equals(databaseMarker, roleMarker)
                && databaseMarker.matches("atoms-standalone:" + purpose
                    + ":[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}");
        if (!Objects.equals(database, expectedDatabase) || !Objects.equals(role, expectedRole)
                || !safeRole || !safeDatabase || !safeSchema || !(unmarkedRuntime || markedPurpose)) throw unsafe();
    }

    private static IllegalStateException unsafe() {
        return new IllegalStateException("DATABASE_UNSAFE: actual database, role, privileges or schema do not match the dedicated installation.");
    }
}
