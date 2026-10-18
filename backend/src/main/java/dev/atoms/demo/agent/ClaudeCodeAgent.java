package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Opt-in, one-shot Claude Code adapter. No inherited personal settings, alternate provider or retry. */
@Component
final class ClaudeCodeAgent implements CodingAgent {
    private final AgentSettings settings;
    private final AgentEnvironment state;
    private final Environment configuration;
    ClaudeCodeAgent(AgentSettings settings, AgentEnvironment state, Environment configuration) {
        this.settings = settings; this.state = state; this.configuration = configuration;
    }

    @Override public Session prepare(AgentTask task, Path work, AgentDeadline deadline) throws IOException {
        var config = settings.load();
        if (!config.maintainerIds().contains(task.ownerId())) throw new ApiException(403, "AGENT_NOT_ALLOWED", "当前账号未获准使用完整工程 Agent。");
        if (!config.liveEnabled()) throw new ApiException(503, "AGENT_LIVE_DISABLED", "真实工程 Agent 调用尚未启用。");
        deadline.remaining();
        if (task.maxTokens() != config.maxOutputTokens() || task.maxTurns() != config.maxTurns()
                || task.taskTimeoutSeconds() != config.taskTimeout().toSeconds()) throw AgentTask.conflict("AGENT_CONFIGURATION_CHANGED");
        var privateState = state.prepare(task.id());
        var identity = AgentCliProbe.verify(config.cli(), privateState.config(), privateState.temporary(), deadline::remaining);
        state.verify(privateState); deadline.remaining();
        String key = configuration.getProperty("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank() || key.length() > 4096 || key.indexOf('\0') >= 0)
            throw new ApiException(503, "AGENT_CREDENTIAL_MISSING", "请先配置 DeepSeek API Key。");
        var builder = AgentInvocation.create(identity.path(), config, work, privateState.config(), privateState.temporary(), key);
        return new Session() {
            private final AtomicBoolean used = new AtomicBoolean(), closed = new AtomicBoolean();
            @Override public boolean paid() { return true; }
            @Override public Result execute(UUID execution, BooleanSupplier cancelled) throws IOException {
                if (closed.get() || !used.compareAndSet(false, true)) throw new IOException("AGENT_SESSION_USED");
                try {
                    state.verify(privateState); identity.verifyUnchanged(deadline::remaining);
                    var result = new AgentProcess().run(builder, instructions(task), deadline, cancelled, task.id(), execution);
                    return new Result(result.summary(), result.stopped());
                } catch (AgentProcess.Failure error) { throw new CodingAgent.Failure(error.getMessage(), error.stopped()); }
                finally { close(); }
            }
            @Override public void close() { if (closed.compareAndSet(false, true)) builder.environment().clear(); }
        };
    }

    private static String instructions(AgentTask task) {
        return """
                Edit this existing full project in the current working directory to implement the user's request.
                Inspect the existing files first. Preserve working behavior and unrelated file bytes.
                The project uses Vue and Spring Boot Java 17 with PostgreSQL; implement required frontend,
                backend business logic, tests, and additive SQL migrations as needed, not just a static mockup.
                Only Read, Glob, Grep, Write and Edit are available. Do not use a shell, MCP, subagents,
                external downloads, personal settings, credentials, or paths outside this directory.
                Declare dependencies in the project manifests. The platform separately validates dependencies,
                installs and builds using controlled commands. Do not claim tests or builds ran here.
                Do not add generated binaries, node_modules, build output, secrets, agent configuration or .git files.
                Keep changes within this project's existing interfaces and conventions unless the request requires changes.

                User request:
                """ + task.prompt();
    }
}
