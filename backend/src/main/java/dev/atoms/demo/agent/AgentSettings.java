package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Pure, lazy configuration: no filesystem, subprocess or credential access. */
@Component
final class AgentSettings {
    private final Environment configuration;

    AgentSettings(Environment configuration) { this.configuration = configuration; }

    boolean enabled() { return flag("enabled"); }

    Configuration load() {
        if (!enabled()) throw new ApiException(503, "AGENT_DISABLED", "完整工程 Agent 尚未启用。");
        try {
            return new Configuration(path("cli"), path("state-root"), maintainers(), flag("live-enabled"),
                    number("max-live-runs", 0, 0, Long.MAX_VALUE),
                    (int) number("max-output-tokens", 128000, 1, 128000),
                    (int) number("max-turns", 24, 1, 24),
                    Duration.ofSeconds(number("task-timeout-seconds", 1800, 1, 1800)),
                    Duration.ofSeconds(number("queue-timeout-seconds", 300, 1, 300)),
                    (int) number("max-concurrent-tasks", 4, 1, 8));
        } catch (IllegalArgumentException error) { throw unavailable(); }
    }

    record Configuration(Path cli, Path stateRoot, Set<UUID> maintainerIds, boolean liveEnabled,
                         long maxLiveRuns, int maxOutputTokens, int maxTurns,
                         Duration taskTimeout, Duration queueTimeout, int maxConcurrentTasks) {
        Configuration { maintainerIds = Set.copyOf(maintainerIds); }
    }

    private boolean flag(String key) {
        String value = value(key, "false");
        if (!value.equals("true") && !value.equals("false")) throw unavailable();
        return value.equals("true");
    }

    private long number(String key, long fallback, long min, long max) {
        long number = Long.parseLong(value(key, Long.toString(fallback)));
        if (number < min || number > max) throw unavailable();
        return number;
    }

    private Path path(String key) {
        Path path = Path.of(value(key, ""));
        if (!path.isAbsolute() || path.getNameCount() < 2 || !path.normalize().equals(path)) throw unavailable();
        return path;
    }

    private Set<UUID> maintainers() {
        var result = new HashSet<UUID>();
        for (String entry : value("maintainer-ids", "").split(",", -1)) {
            String normalized = entry.trim();
            UUID id = UUID.fromString(normalized);
            if (!id.toString().equals(normalized) || !result.add(id)) throw unavailable();
        }
        return Set.copyOf(result);
    }

    private String value(String key, String fallback) {
        String value = configuration.getProperty("atoms.agent." + key);
        return value == null ? fallback : value;
    }

    static ApiException unavailable() {
        return new ApiException(503, "AGENT_CONFIGURATION_INVALID", "完整工程 Agent 配置无效，请检查维护者配置。");
    }
}
