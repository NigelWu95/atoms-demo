package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.source.ProjectSourceEnvironment;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Explicit, lazy state preparation. Authentication and task budget remain the caller's responsibility. */
@Component
final class AgentEnvironment implements AutoCloseable {
    private final AgentSettings settings;
    private final ProjectSourceEnvironment source;
    private final LongSupplier uid;
    private AgentStateDirectory state;
    private boolean closed;

    @Autowired AgentEnvironment(AgentSettings settings, ProjectSourceEnvironment source) {
        // Native UID, not an overridable user.name property. Supported on the target macOS/Linux hosts.
        // https://docs.oracle.com/en/java/javase/17/docs/api/jdk.security.auth/com/sun/security/auth/module/UnixSystem.html
        this(settings, source, () -> new com.sun.security.auth.module.UnixSystem().getUid());
    }

    AgentEnvironment(AgentSettings settings, ProjectSourceEnvironment source, LongSupplier uid) {
        this.settings = settings; this.source = source; this.uid = uid;
    }

    synchronized AgentStateDirectory.Task prepare(UUID task) throws IOException {
        if (closed) throw AgentSettings.unavailable();
        var configuration = settings.load();
        if (configuration.liveEnabled() && uid.getAsLong() == 0)
            throw new ApiException(503, "AGENT_ROOT_REFUSED", "不允许使用 root 启动真实工程 Agent。");
        var paths = source.settings().paths();
        if (state == null) state = new AgentStateDirectory(configuration.stateRoot(), List.of(paths.root(), paths.workRoot()));
        return state.create(task);
    }

    synchronized void verify(AgentStateDirectory.Task task) throws IOException {
        if (closed || state == null) throw new IOException("AGENT_STATE_UNAVAILABLE");
        state.verify(task);
        state.checkCapacity();
    }

    @PreDestroy @Override public synchronized void close() throws IOException {
        closed = true;
        if (state != null) state.close();
    }
}
