package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Lazy opt-in: ordinary platform startup does not touch Git or project source roots. */
@Component
public final class ProjectSourceEnvironment implements AutoCloseable {
    private final Environment configuration;
    private Settings settings;
    private boolean closed;

    public ProjectSourceEnvironment(Environment configuration) { this.configuration = configuration; }
    public boolean enabled() { return configuration.getProperty("atoms.source.enabled", Boolean.class, false); }
    public record Settings(ProjectSourcePaths paths, Path git) {}

    synchronized Settings existingSettings() {
        if (closed) throw unavailable();
        if (!enabled()) throw new ApiException(503, "SOURCE_DISABLED", "完整工程源码管理尚未启用。");
        if (settings == null && (!Files.isDirectory(path("root"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(path("work-root"), LinkOption.NOFOLLOW_LINKS))) throw unavailable();
        return settings();
    }

    public synchronized Settings settings() {
        if (closed) throw unavailable();
        if (!enabled()) throw new ApiException(503, "SOURCE_DISABLED", "完整工程源码管理尚未启用。");
        if (settings != null) {
            try { settings.paths().verify(); return settings; }
            catch (IOException error) { throw unavailable(); }
        }
        try {
            Path root = path("root"), work = path("work-root"), git = path("git");
            if (!Files.isRegularFile(git, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(git)) throw unavailable();
            settings = new Settings(new ProjectSourcePaths(root, work), git);
            return settings;
        } catch (IOException | IllegalArgumentException error) { throw unavailable(); }
    }

    private Path path(String name) {
        String value = configuration.getProperty("atoms.source." + name, "");
        if (value.isBlank()) throw unavailable();
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw unavailable();
        return path.normalize();
    }

    static ApiException unavailable() {
        return new ApiException(503, "SOURCE_STORAGE_UNAVAILABLE", "完整工程源码存储不可用，请检查维护者配置。");
    }

    @PreDestroy @Override public synchronized void close() throws IOException {
        closed = true;
        if (settings != null) settings.paths().close();
    }
}
