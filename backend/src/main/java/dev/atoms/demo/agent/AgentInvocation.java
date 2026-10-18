package dev.atoms.demo.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Internal construction only; ownership and executable identity are checked by the process owner. */
final class AgentInvocation {
    static final String MODEL = "deepseek-flash[1m]";
    static final String TOOLS = "Read,Glob,Grep,Write,Edit";

    private AgentInvocation() {}

    static ProcessBuilder create(Path executable, AgentSettings.Configuration settings, Path work,
                                 Path config, Path temporary, String apiKey) {
        for (Path path : List.of(executable, work, config, temporary)) requireAbsolute(path);
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 4096 || apiKey.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid Agent credential");
        var command = new ArrayList<String>();
        command.add(executable.toString());
        // https://code.claude.com/docs/en/cli-reference: available tools != auto-approved tools.
        command.addAll(List.of("--bare", "--restricted", "--print", "--input-format", "text",
                "--output-format", "stream-json", "--verbose", "--no-session-persistence",
                "--disable-slash-commands", "--no-chrome", "--setting-sources", "", "--strict-mcp-config",
                "--mcp-config", "{\"mcpServers\":{}}", "--tools", TOOLS, "--allowedTools", TOOLS,
                "--permission-mode", "dontAsk", "--permission-prompts", "none", "--max-turns",
                Integer.toString(settings.maxTurns()), "--model", MODEL, "--effort", "high"));
        var builder = new ProcessBuilder(command).directory(work.toFile());
        builder.environment().clear();
        builder.environment().putAll(probeEnvironment(config, temporary));
        var environment = builder.environment();
        // Bare mode never uses personal OAuth; only this subprocess receives the supplied key.
        // https://code.claude.com/docs/en/headless#start-faster-with-bare-mode
        environment.put("ANTHROPIC_API_KEY", apiKey);
        environment.put("ANTHROPIC_BASE_URL", "https://api.deepseek.com/anthropic");
        for (String role : List.of("ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL",
                "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL")) environment.put(role, MODEL);
        environment.put("CLAUDE_CODE_MAX_OUTPUT_TOKENS", Integer.toString(settings.maxOutputTokens()));
        environment.put("CLAUDE_CODE_MAX_RETRIES", "0");
        environment.put("API_TIMEOUT_MS", "300000");
        return builder;
    }

    static Map<String, String> probeEnvironment(Path config, Path temporary) {
        requireAbsolute(config); requireAbsolute(temporary);
        var environment = new HashMap<String, String>();
        environment.putAll(Map.of("PATH", "/usr/bin:/bin", "LANG", "C.UTF-8", "LC_ALL", "C.UTF-8", "TZ", "UTC",
                "CLAUDE_CONFIG_DIR", config.toString(), "DISABLE_AUTOUPDATER", "1", "DISABLE_UPDATES", "1",
                "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"));
        for (String key : List.of("TMPDIR", "TMP", "TEMP")) environment.put(key, temporary.toString());
        return Map.copyOf(environment);
    }

    private static void requireAbsolute(Path path) {
        if (!path.isAbsolute() || !path.normalize().equals(path)) throw new IllegalArgumentException("Invalid Agent path");
    }
}
