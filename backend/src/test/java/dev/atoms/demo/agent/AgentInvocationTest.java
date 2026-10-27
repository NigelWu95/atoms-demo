package dev.atoms.demo.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentInvocationTest {
    private final Path root = Path.of("/private/task-state/one");
    private final AgentSettings.Configuration settings = new AgentSettings.Configuration(Path.of("/opt/cli/claude"),
            root.getParent(), Set.of(UUID.randomUUID()), false, 0, 128000, 24,
            Duration.ofSeconds(1800), Duration.ofSeconds(300), 2);

    @Test void fixedArgvDoesNotIncludePromptsShellOrFallbacks() {
        var builder = invocation();
        assertThat(builder.command()).containsExactly("/opt/cli/claude", "--safe-mode", "--restricted", "--print",
                "--input-format", "text", "--output-format", "stream-json", "--verbose", "--no-session-persistence",
                "--disable-slash-commands", "--no-chrome", "--setting-sources", "", "--strict-mcp-config",
                "--mcp-config", "{\"mcpServers\":{}}", "--tools", "Read,Glob,Grep,Write,Edit",
                "--allowedTools", "Read,Glob,Grep,Write,Edit", "--permission-mode", "dontAsk",
                "--permission-prompts", "none", "--max-turns", "24", "--model", "deepseek-flash[1m]", "--effort", "high");
        assertThat(builder.directory().toPath()).isEqualTo(Path.of("/private/work/one/project"));
    }

    @Test void environmentIsAnExactAllowlistNotAnOverlayOnThePlatform() {
        var env = invocation().environment();
        assertThat(env).containsEntry("ANTHROPIC_API_KEY", "offline-fixture-key")
                .containsEntry("ANTHROPIC_BASE_URL", "https://api.deepseek.com/anthropic")
                .containsEntry("CLAUDE_CONFIG_DIR", root.resolve("config").toString())
                .containsEntry("CLAUDE_CODE_MAX_OUTPUT_TOKENS", "128000")
                .containsEntry("ENABLE_TOOL_SEARCH", "false")
                .containsEntry("CLAUDE_CODE_MAX_RETRIES", "0").containsEntry("API_TIMEOUT_MS", "300000");
        assertThat(env.keySet()).containsExactlyInAnyOrder("PATH", "LANG", "LC_ALL", "TZ", "TMPDIR", "TMP", "TEMP",
                "CLAUDE_CONFIG_DIR", "DISABLE_AUTOUPDATER", "DISABLE_UPDATES", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC",
                "ANTHROPIC_BASE_URL", "ANTHROPIC_API_KEY", "ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL",
                "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL", "CLAUDE_CODE_MAX_OUTPUT_TOKENS",
                "CLAUDE_CODE_MAX_RETRIES", "API_TIMEOUT_MS", "ENABLE_TOOL_SEARCH");
        assertThat(env).doesNotContainKeys("HOME", "APP_DB_PASSWORD", "SMTP_PASSWORD", "NODE_OPTIONS",
                "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN", "HTTP_PROXY", "HTTPS_PROXY", "DEEPSEEK_API_KEY");
    }

    @Test void credentialIsNeitherACommandArgumentNorAnObjectDescription() {
        assertThat(invocation().command().toString()).doesNotContain("offline-fixture-key");
        assertThat(invocation().toString()).doesNotContain("offline-fixture-key");
        assertThatThrownBy(() -> AgentInvocation.create(settings.cli(), settings, Path.of("relative"),
                root.resolve("config"), root.resolve("tmp"), "key-canary"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("key-canary");
    }

    private ProcessBuilder invocation() {
        return AgentInvocation.create(settings.cli(), settings, Path.of("/private/work/one/project"),
                root.resolve("config"), root.resolve("tmp"), "offline-fixture-key");
    }
}
