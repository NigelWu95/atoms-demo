package dev.atoms.demo.workspace;

import dev.atoms.demo.common.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Operator-only native execution configuration; never accepts executable paths from an HTTP request. */
@Component
public final class BuildEnvironment {
    private final Environment environment;
    public BuildEnvironment(Environment environment) { this.environment = environment; }

    public Settings settings() {
        if (!environment.getProperty("atoms.build.enabled", Boolean.class, false))
            throw new ApiException(503, "BUILD_DISABLED", "本机构建尚未启用");
        try {
            String[] profiles = environment.getActiveProfiles();
            if (profiles.length == 0) profiles = environment.getDefaultProfiles();
            if (profiles.length == 0 || !Arrays.stream(profiles).allMatch(Set.of("local", "fixtures", "test")::contains)
                    || !Set.of("127.0.0.1", "::1").contains(environment.getProperty("server.address", "")))
                throw WorkspacePaths.rejected();
            var paths = new WorkspacePaths(path("root"));
            Path node = executable("node"), java = executable("java"), config = path("test-config");
            var properties = new Properties();
            properties.load(new ByteArrayInputStream(BuildArtifacts.read(config, 16 * 1024)));
            if (!"true".equals(properties.getProperty("standalone.test-database"))) throw WorkspacePaths.rejected();
            var safeEnvironment = new LinkedHashMap<String, String>();
            for (String key : Set.of("HOME", "TMPDIR", "LANG", "LC_ALL", "TERM")) {
                String value = System.getenv(key);
                if (value != null) safeEnvironment.put(key, value);
            }
            safeEnvironment.put("JAVA_HOME", java.getParent().getParent().toString());
            safeEnvironment.put("PATH", node.getParent() + ":" + java.getParent() + ":/usr/bin:/bin:/usr/sbin:/sbin");
            if (!probe(node, safeEnvironment).matches("v24\\.20\\.\\d+\\s*")) throw WorkspacePaths.rejected();
            if (!probe(java, safeEnvironment).matches("(?s)(?:java|openjdk) 17(?:\\.| ).*")) throw WorkspacePaths.rejected();
            Path npm = node.getParent().resolve("npm");
            if (!Files.isExecutable(npm) || !probe(npm, safeEnvironment).matches("11\\.19\\.\\d+\\s*")) throw WorkspacePaths.rejected();
            return new Settings(paths, node, java, config, Map.copyOf(safeEnvironment));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception error) { throw unavailable(); }
    }

    private Path path(String key) throws Exception {
        Path value = Path.of(environment.getRequiredProperty("atoms.build." + key));
        if (!value.isAbsolute()) throw WorkspacePaths.rejected();
        return value.normalize();
    }
    private Path executable(String key) throws Exception {
        Path value = path(key).toRealPath();
        if (!Files.isRegularFile(value) || !Files.isExecutable(value)) throw WorkspacePaths.rejected();
        return value;
    }
    private static String probe(Path executable, Map<String, String> environment) throws Exception {
        var builder = new ProcessBuilder(executable.toString(), "--version").redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            // Installed, operator-selected version commands produce only a few lines. A full pipe
            // is treated as a failed probe by the same deadline, never an unbounded read.
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) throw WorkspacePaths.rejected();
            byte[] output = process.getInputStream().readNBytes(4097);
            if (output.length > 4096) throw WorkspacePaths.rejected();
            return new String(output, java.nio.charset.StandardCharsets.UTF_8);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    static ApiException unavailable() { return new ApiException(503, "BUILD_ENVIRONMENT_NOT_READY", "本机构建环境未就绪，请检查管理员配置"); }
    public record Settings(WorkspacePaths paths, Path node, Path java, Path testConfig, Map<String, String> environment) {}
}
