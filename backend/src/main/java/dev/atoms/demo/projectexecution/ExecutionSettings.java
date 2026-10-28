package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Lazy configuration only. Files, tool identity and database bindings are checked at execution admission. */
@Component
final class ExecutionSettings {
    static final long ROOT_MAX_BYTES = 8L << 30, MIN_FREE_BYTES = 1L << 30, JAR_MAX_BYTES = 256L << 20;
    static final int DIAGNOSTIC_MAX_BYTES = 64 << 10, EVENT_LIMIT = 1000, PAGE_LIMIT = 100;
    private final Environment configuration;

    ExecutionSettings(Environment configuration) { this.configuration = configuration; }

    boolean enabled() {
        String value = value("enabled", "false");
        if (!value.equals("true") && !value.equals("false")) throw unavailable();
        return value.equals("true");
    }

    Configuration load() {
        if (!enabled()) throw new ApiException(503, "EXECUTION_DISABLED", "完整工程执行尚未启用。");
        try {
            return new Configuration(path("root"), path("node"), path("npm"), path("java"), path("maven"),
                    path("policy"), maintainers(), number("max-concurrent-builds", 1, 4),
                    number("queue-capacity", 8, 8), number("max-running-projects", 4, 4),
                    Duration.ofSeconds(number("task-timeout-seconds", 1800, 1800)),
                    Duration.ofSeconds(number("queue-timeout-seconds", 300, 300)),
                    Duration.ofSeconds(number("start-timeout-seconds", 120, 120)), ownerAccess());
        } catch (IllegalArgumentException error) { throw unavailable(); }
    }

    record Configuration(Path root, Path node, Path npm, Path java, Path maven, Path policy,
                         Set<UUID> maintainerIds, int maxConcurrentBuilds, int queueCapacity,
                         int maxRunningProjects, Duration taskTimeout, Duration queueTimeout, Duration startTimeout, boolean ownerAccess) {
        Configuration { maintainerIds = Set.copyOf(maintainerIds); }
        Configuration(Path root, Path node, Path npm, Path java, Path maven, Path policy, Set<UUID> maintainers,
                      int builds, int queue, int running, Duration taskTimeout, Duration queueTimeout, Duration startTimeout) {
            this(root, node, npm, java, maven, policy, maintainers, builds, queue, running, taskTimeout, queueTimeout, startTimeout, false);
        }
        boolean allows(UUID owner) { return owner != null && (ownerAccess || maintainerIds.contains(owner)); }
    }

    private boolean ownerAccess() {
        String value = configuration.getProperty("atoms.project-workbench.owner-access-enabled", "false");
        if (!value.equals("true") && !value.equals("false")) throw unavailable();
        return value.equals("true");
    }

    private Path path(String key) {
        String raw = value(key, "");
        if (raw.codePoints().anyMatch(Character::isISOControl)) throw unavailable();
        Path path = Path.of(raw);
        if (!path.isAbsolute() || path.getNameCount() < 2 || !path.normalize().equals(path)) throw unavailable();
        return path;
    }

    private Set<UUID> maintainers() {
        var result = new HashSet<UUID>();
        for (String entry : value("maintainer-ids", "").split(",", -1)) {
            String canonical = entry.trim();
            UUID id = UUID.fromString(canonical);
            if (!id.toString().equals(canonical) || !result.add(id)) throw unavailable();
        }
        return Set.copyOf(result);
    }

    private int number(String key, int fallback, int ceiling) {
        String raw = value(key, Integer.toString(fallback));
        if (!raw.matches("[0-9]{1,10}")) throw unavailable();
        int number = Integer.parseInt(raw);
        if (number < 1 || number > ceiling) throw unavailable();
        return number;
    }

    private String value(String key, String fallback) {
        // Preserve Spring's property precedence while avoiding eager resolution when disabled.
        // https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html
        try {
            String value = configuration.getProperty("atoms.project-execution." + key);
            return value == null ? fallback : value;
        } catch (IllegalArgumentException error) { throw unavailable(); }
    }

    static ApiException unavailable() {
        return new ApiException(503, "EXECUTION_CONFIGURATION_INVALID", "完整工程执行配置无效，请检查维护者配置。");
    }
}
