package dev.atoms.demo.projectexecution;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

/** Maintainer-owned test credentials, never a record/DTO or source-tree configuration. */
final class ExecutionPrivateConfig {
    private static final Set<String> KEYS = Set.of("database", "user", "password", "purpose", "bindingId", "jdbcUrl", "schema");
    private static final Set<String> RESERVED = Set.of("postgres", "template0", "template1", "atoms_demo", "atoms_demo_test", "atoms_test");
    private final Path file;
    private final List<Path> protectedRoots;
    private final Properties values;
    private final UUID bindingId;
    private final Object parentKey, fileKey;
    private final byte[] digest;

    private ExecutionPrivateConfig(Path file, List<Path> protectedRoots, Properties values, byte[] bytes) throws IOException {
        this.file = file; this.protectedRoots = List.copyOf(protectedRoots); this.values = values;
        this.bindingId = UUID.fromString(values.getProperty("bindingId"));
        this.parentKey = identity(file.getParent(), true); this.fileKey = identity(file, false);
        this.digest = digest(bytes);
    }

    static ExecutionPrivateConfig read(Path file, List<Path> protectedRoots) throws IOException {
        try {
            validateLocation(file, protectedRoots);
            Object parent = identity(file.getParent(), true), before = identity(file, false);
            byte[] bytes = bytes(file);
            var values = new Properties() {
                @Override public synchronized Object put(Object key, Object value) {
                    if (containsKey(key)) throw new IllegalArgumentException();
                    return super.put(key, value);
                }
            };
            // Same ISO-8859-1 / escaped Unicode format used by Properties.store; no env interpolation.
            try (var input = new ByteArrayInputStream(bytes)) { values.load(input); }
            if (!values.stringPropertyNames().equals(KEYS)) throw invalid();
            for (String key : KEYS) {
                String value = values.getProperty(key);
                if (value.isBlank() || value.length() > 512 || value.contains("${")
                        || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff))
                    throw invalid();
            }
            for (String key : List.of("database", "user")) {
                String name = values.getProperty(key);
                if (!name.matches("[a-z][a-z0-9_]{0,62}") || name.startsWith("pg_") || RESERVED.contains(name)) throw invalid();
            }
            if (!values.getProperty("purpose").equals("test") || !values.getProperty("schema").equals("generated_app")) throw invalid();
            UUID binding = UUID.fromString(values.getProperty("bindingId"));
            if (!binding.toString().equals(values.getProperty("bindingId")) || binding.version() != 4 || binding.variant() != 2) throw invalid();
            var url = java.util.regex.Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/"
                    + values.getProperty("database") + "\\?currentSchema=generated_app").matcher(values.getProperty("jdbcUrl"));
            if (!url.matches() || Integer.parseInt(url.group(1)) > 65535) throw invalid();
            var result = new ExecutionPrivateConfig(file, protectedRoots, values, bytes);
            if (!parent.equals(result.parentKey) || !before.equals(result.fileKey)) throw invalid();
            result.verify();
            return result;
        } catch (IOException | RuntimeException error) { throw invalid(); }
    }

    void verify() throws IOException {
        try {
            validateLocation(file, protectedRoots);
            if (!parentKey.equals(identity(file.getParent(), true)) || !fileKey.equals(identity(file, false))
                    || !MessageDigest.isEqual(digest, digest(bytes(file)))
                    || !fileKey.equals(identity(file, false)) || !parentKey.equals(identity(file.getParent(), true))) throw invalid();
        } catch (IOException | RuntimeException error) { throw invalid(); }
    }

    String database() { return values.getProperty("database"); }
    String role() { return values.getProperty("user"); }
    UUID bindingId() { return bindingId; }
    String marker() { return "atoms-project-execution:test:" + bindingId; }
    String jdbcUrl() { return values.getProperty("jdbcUrl").split("\\?", 2)[0]; }

    /** Only the bound launch adapter calls this; never return a credential map or add JVM -D options. */
    void applyTestEnvironment(ProcessBuilder builder) throws IOException {
        verify();
        // Spring Boot 3.5 placeholders resolve environment values; Surefire inherits the Maven environment.
        // https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html
        // https://maven.apache.org/surefire-archives/surefire-3.5.6/maven-surefire-plugin/test-mojo.html#excludedEnvironmentVariables
        builder.environment().put("APP_DB_URL", values.getProperty("jdbcUrl"));
        builder.environment().put("APP_DB_USERNAME", role());
        builder.environment().put("APP_DB_PASSWORD", values.getProperty("password"));
        builder.environment().put("APP_PORT", "0");
    }

    /** Fresh explicit properties only; the URL cannot override these with operator-supplied parameters. */
    Properties inspectionProperties(int seconds) throws IOException {
        verify();
        if (seconds < 1 || seconds > 5) throw invalid();
        var properties = new Properties();
        properties.setProperty("user", role()); properties.setProperty("password", values.getProperty("password"));
        properties.setProperty("connectTimeout", Integer.toString(Math.min(3, seconds)));
        properties.setProperty("socketTimeout", Integer.toString(seconds));
        properties.setProperty("cancelSignalTimeout", "1");
        properties.setProperty("readOnly", "true"); properties.setProperty("readOnlyMode", "transaction");
        properties.setProperty("currentSchema", "pg_catalog");
        // This mode only supports explicit loopback TCP, not remote TLS/Kerberos or personal credentials.
        properties.setProperty("sslmode", "disable"); properties.setProperty("gssEncMode", "disable");
        properties.setProperty("jaasLogin", "false");
        properties.setProperty("ApplicationName", "atoms-execution-db-preflight");
        return properties;
    }

    private static void validateLocation(Path file, List<Path> roots) throws IOException {
        if (file == null || !file.isAbsolute() || !file.normalize().equals(file) || file.getNameCount() < 3) throw invalid();
        ExecutionRootLease.noLinks(file);
        for (Path root : roots) {
            if (root == null || !root.isAbsolute() || !root.normalize().equals(root)) throw invalid();
            ExecutionRootLease.noLinks(root);
            if (file.startsWith(root)) throw invalid();
        }
    }

    private static Object identity(Path path, boolean directory) throws IOException {
        var attributes = ExecutionRootLease.attributes(path);
        long uid = new com.sun.security.auth.module.UnixSystem().getUid();
        if (uid <= 0 || attributes.fileKey() == null || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())
                || ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() != uid
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(
                        PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))
                || !directory && (((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1
                        || attributes.size() < 1 || attributes.size() > 8192)) throw invalid();
        return attributes.fileKey();
    }

    private static byte[] bytes(Path file) throws IOException {
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(8193);
            if (bytes.length < 1 || bytes.length > 8192) throw invalid();
            return bytes;
        }
    }

    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static IOException invalid() { return new IOException("EXECUTION_DB_CONFIGURATION_INVALID"); }
}
