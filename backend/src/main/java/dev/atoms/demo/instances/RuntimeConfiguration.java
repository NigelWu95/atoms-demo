package dev.atoms.demo.instances;

import java.net.URI;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Operator input only. Intentionally has no credential-bearing toString/accessor. */
final class RuntimeConfiguration {
    private static final Set<String> KEYS = Set.of("STANDALONE_DB_URL", "STANDALONE_DB_USER",
            "STANDALONE_DB_PASSWORD", "APP_INSTANCE_ID", "server.address", "server.port",
            "standalone.origin", "standalone.test-database");
    private final Properties values;
    private final UUID id;
    private final int port;
    private RuntimeConfiguration(Properties values, UUID id, int port) { this.values=values; this.id=id; this.port=port; }
    UUID id() { return id; }
    int port() { return port; }
    String databaseKey() { return values.getProperty("STANDALONE_DB_URL"); }
    String role() { return values.getProperty("STANDALONE_DB_USER"); }
    Properties forExecution(UUID execution) {
        var copy = new Properties(); copy.putAll(values);
        copy.setProperty("RUNTIME_EXECUTION_ID", execution.toString());
        return copy;
    }
    static RuntimeConfiguration parse(Properties source) {
        try {
            if (!KEYS.containsAll(source.stringPropertyNames()) || !source.stringPropertyNames().containsAll(KEYS)) throw invalid();
            for (String key : KEYS) {
                String value=source.getProperty(key);
                if (value.isBlank() || value.contains("${") || value.chars().anyMatch(Character::isISOControl)) throw invalid();
            }
            UUID id=UUID.fromString(source.getProperty("APP_INSTANCE_ID"));
            if (id.version()!=4 || id.variant()!=2 || !id.toString().equals(source.getProperty("APP_INSTANCE_ID"))) throw invalid();
            int port=Integer.parseInt(source.getProperty("server.port"));
            if (port<1024 || port>65535 || Set.of(5432,5173,5174,8080,8081,8082,8443).contains(port)
                    || !"127.0.0.1".equals(source.getProperty("server.address"))
                    || !"false".equals(source.getProperty("standalone.test-database"))
                    || !("http://127.0.0.1:"+port).equals(source.getProperty("standalone.origin"))) throw invalid();
            String url=source.getProperty("STANDALONE_DB_URL");
            if (!url.startsWith("jdbc:postgresql://127.0.0.1:")) throw invalid();
            URI uri=URI.create(url.substring(5));
            if (!"127.0.0.1".equals(uri.getHost()) || uri.getPort()<1 || uri.getPort()>65535
                    || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || !uri.getRawPath().matches("/[a-z_][a-z0-9_]{0,62}")) throw invalid();
            String database=uri.getPath().substring(1), role=source.getProperty("STANDALONE_DB_USER");
            if (database.startsWith("atoms_demo") || Set.of("postgres","template0","template1").contains(database)
                    || !role.matches("[a-z_][a-z0-9_]{0,62}") || Set.of("postgres","atoms_app","atoms_test").contains(role)
                    || source.getProperty("STANDALONE_DB_PASSWORD").length()<16) throw invalid();
            var copy=new Properties(); copy.putAll(source);
            return new RuntimeConfiguration(copy,id,port);
        } catch (RuntimeException error) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid dedicated runtime configuration"); }
}
