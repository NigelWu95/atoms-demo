package dev.atoms.demo.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.source.ProjectSourcePolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Decodes one NDJSON run. Raw model text, source, reasoning and stderr are never returned. */
final class AgentStreamDecoder {
    enum Rejection {
        REUSED_DECODER, MISSING_FINAL_RESULT, TRAILING_OR_EMPTY_EVENT, INVALID_JSON, INVALID_EVENT_SHAPE,
        MISSING_INITIALIZATION, UNKNOWN_SYSTEM_EVENT, UNKNOWN_EVENT, INVALID_CAPABILITY_SHAPE,
        SYSTEM_API_ERROR, SYSTEM_API_RETRY, SYSTEM_INFORMATIONAL, SYSTEM_MODEL_FALLBACK,
        INVALID_MESSAGE_SHAPE, TOOL_RESULT_SEQUENCE, INVALID_TOOL_CALL, INVALID_TOOL_ARGUMENTS,
        UNRESOLVED_TOOLS, INVALID_RESULT_SHAPE, INVALID_USAGE
    }
    static final class ProtocolFailure extends IOException {
        private final Rejection rejection;
        private final int eventNumber;
        private String systemSubtypeSha256 = "";
        ProtocolFailure(Rejection rejection, int eventNumber) {
            super("AGENT_PROTOCOL_INVALID"); this.rejection = rejection; this.eventNumber = eventNumber;
        }
        Rejection rejection() { return rejection; }
        int eventNumber() { return eventNumber; }
        String systemSubtypeSha256() { return systemSubtypeSha256; }
    }
    enum LimitReason { LINE_BYTES, TOTAL_BYTES, PROTOCOL_EVENTS, PROGRESS_EVENTS, PATHS }
    static final class OutputLimit extends IOException {
        private final LimitReason reason;
        private final int eventNumber;
        OutputLimit(LimitReason reason, int eventNumber) { super("AGENT_OUTPUT_LIMIT"); this.reason = reason; this.eventNumber = eventNumber; }
        LimitReason reason() { return reason; }
        int eventNumber() { return eventNumber; }
    }
    enum TerminalReason { TURN_LIMIT, BUDGET_LIMIT, EXECUTION_ERROR, OTHER }
    static final class Incomplete extends IOException {
        private final TerminalReason reason;
        private final long turns;
        Incomplete(TerminalReason reason, long turns) {
            super(reason == TerminalReason.TURN_LIMIT ? "AGENT_TURN_LIMIT" : "AGENT_MODEL_INCOMPLETE");
            this.reason = reason; this.turns = turns;
        }
        TerminalReason reason() { return reason; }
        long turns() { return turns; }
    }
    private static final Set<String> TOOLS = Set.of("Read", "Glob", "Grep", "Write", "Edit");
    // Pinned CLI releases report these modules even in safe mode; no filesystem plugin is accepted.
    private static final Set<String> BUILTIN_MODULES = Set.of("cc-plugin-agents-md", "cc-plugin-plugin-authoring");
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path workspace;
    private final Limits limits;
    private final Map<String, Integer> toolCounts = new HashMap<>();
    private final Set<String> paths = new TreeSet<>(), toolIds = new HashSet<>(), pendingTools = new HashSet<>();
    private boolean initialized, completed, consumed;
    private int events, protocolEvents, progressEvents;
    private long inputTokens, outputTokens;

    record Limits(int lineBytes, long totalBytes, int events) {
        Limits {
            if (lineBytes < 1 || lineBytes > 4 << 20 || totalBytes < 1 || totalBytes > 64L << 20 || events < 1 || events > 4096)
                throw new IllegalArgumentException("Invalid Agent stream limits");
        }
    }
    record Summary(int events, Map<String, Integer> toolCounts, List<String> paths, long inputTokens, long outputTokens) {
        Summary { toolCounts = Map.copyOf(toolCounts); paths = List.copyOf(paths); }
    }

    AgentStreamDecoder(Path workspace) { this(workspace, new Limits(4 << 20, 64L << 20, 4096)); }
    AgentStreamDecoder(Path workspace, Limits limits) {
        if (!workspace.isAbsolute() || !workspace.normalize().equals(workspace)) throw new IllegalArgumentException("Invalid Agent workspace");
        this.workspace = workspace; this.limits = limits;
    }

    Summary read(InputStream input) throws IOException {
        if (consumed) throw invalid(Rejection.REUSED_DECODER);
        consumed = true;
        var line = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limits.totalBytes()) throw limit(LimitReason.TOTAL_BYTES);
            for (int i = 0; i < count; i++) {
                if (buffer[i] == '\n') { event(line.toByteArray()); line.reset(); }
                else {
                    if (line.size() >= limits.lineBytes()) throw limit(LimitReason.LINE_BYTES);
                    line.write(buffer[i]);
                }
            }
        }
        if (line.size() > 0) event(line.toByteArray());
        if (!initialized || !completed) throw invalid(Rejection.MISSING_FINAL_RESULT);
        return new Summary(events, toolCounts, List.copyOf(paths), inputTokens, outputTokens);
    }

    private void event(byte[] bytes) throws IOException {
        events++;
        if (completed || bytes.length == 0) throw invalid(Rejection.TRAILING_OR_EMPTY_EVENT);
        final JsonNode event;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            event = json.readTree(text);
        } catch (IOException | RuntimeException error) { throw invalid(Rejection.INVALID_JSON); }
        if (event == null || !event.isObject() || !event.path("type").isTextual()
                || !event.path("parent_tool_use_id").isNull() && !event.path("parent_tool_use_id").isMissingNode()) throw invalid(Rejection.INVALID_EVENT_SHAPE);
        String type = event.path("type").asText();
        // Thinking estimates can arrive for every streamed token. They do not consume the tool protocol allowance,
        // but still obey a separate count, the same per-line/total byte bounds and the owning process deadline.
        if (type.equals("system") && event.path("subtype").asText().equals("thinking_tokens")) {
            if (++progressEvents > 262144) throw limit(LimitReason.PROGRESS_EVENTS);
        } else if (++protocolEvents > limits.events()) throw limit(LimitReason.PROTOCOL_EVENTS);
        // Observed before init in CLI 2.1.302; this notification grants no capability or success.
        if (type.equals("system") && event.path("subtype").asText().equals("ui_invalidate")) return;
        if (type.equals("system") && event.path("subtype").asText().equals("init")) {
            initialize(event); return;
        }
        if (!initialized) throw invalid(Rejection.MISSING_INITIALIZATION);
        switch (type) {
            case "system" -> {
                if (event.path("subtype").asText().equals("permission_denied")) throw new IOException("AGENT_TOOL_FAILED");
                String subtype = event.path("subtype").asText();
                // CLI 2.1.308 emits these while consuming thinking deltas, even without partial-message output.
                // They are progress estimates, not final usage or evidence of a successful generation.
                if (subtype.equals("thinking_tokens")) {
                    var total = event.path("estimated_tokens"); var delta = event.path("estimated_tokens_delta");
                    if (!total.isIntegralNumber() || !delta.isIntegralNumber() || count(delta) > count(total))
                        throw invalid(Rejection.INVALID_USAGE);
                    return;
                }
                if (!Set.of("status", "compact_boundary").contains(subtype)) {
                    var rejection = invalid(switch (subtype) {
                    case "api_error" -> Rejection.SYSTEM_API_ERROR;
                    case "api_retry" -> Rejection.SYSTEM_API_RETRY;
                    case "informational" -> Rejection.SYSTEM_INFORMATIONAL;
                    case "model_fallback" -> Rejection.SYSTEM_MODEL_FALLBACK;
                    default -> Rejection.UNKNOWN_SYSTEM_EVENT;
                    });
                    // Match this fingerprint to CLI protocol names offline; never log the untrusted name itself.
                    rejection.systemSubtypeSha256 = sha256(subtype);
                    throw rejection;
                }
            }
            case "assistant", "user" -> message(event);
            case "result" -> result(event);
            case "rate_limit_event" -> { /* No provider payload enters the summary. */ }
            default -> throw invalid(Rejection.UNKNOWN_EVENT);
        }
    }

    private void initialize(JsonNode event) throws IOException {
        if (initialized || !event.path("tools").isArray()) throw invalid(Rejection.INVALID_CAPABILITY_SHAPE);
        var tools = new HashSet<String>();
        for (var tool : event.path("tools")) if (!tool.isTextual() || !tools.add(tool.asText())) throw invalid(Rejection.INVALID_CAPABILITY_SHAPE);
        if (!tools.equals(TOOLS)) throw new IOException("AGENT_CLI_UNSUPPORTED");
        for (String field : List.of("mcp_servers", "skills")) {
            var value = event.path(field);
            if (!value.isMissingNode() && (!value.isArray() || !value.isEmpty())) throw new IOException("AGENT_CLI_UNSUPPORTED");
        }
        var plugins = event.path("plugins");
        if (!plugins.isMissingNode()) {
            if (!plugins.isArray()) throw new IOException("AGENT_CLI_UNSUPPORTED");
            var seen = new HashSet<String>();
            for (var plugin : plugins) {
                String name = plugin.path("name").asText();
                if (!AgentCliProbe.SUPPORTED_VERSIONS.contains(event.path("claude_code_version").asText())
                        || !plugin.isObject() || plugin.size() != 3 || !BUILTIN_MODULES.contains(name) || !seen.add(name)
                        || !plugin.path("path").asText().equals("builtin")
                        || !plugin.path("source").asText().equals(name + "@builtin"))
                    throw new IOException("AGENT_CLI_UNSUPPORTED");
            }
        }
        initialized = true;
    }

    private void message(JsonNode event) throws IOException {
        var content = event.path("message").path("content");
        if (!content.isArray()) throw invalid(Rejection.INVALID_MESSAGE_SHAPE);
        // Pinned CLI emits assistant.error before its terminal event on provider failures.
        // Never copy the provider message, error text or response body into diagnostics.
        var provider = event.path("error");
        if (event.path("type").asText().equals("assistant") && !provider.isMissingNode() && !provider.isNull()) {
            if (!provider.isTextual()) throw invalid(Rejection.INVALID_MESSAGE_SHAPE);
            throw new IOException(switch (provider.textValue()) {
                case "authentication_failed" -> "AGENT_PROVIDER_AUTH_FAILED";
                case "rate_limit" -> "AGENT_PROVIDER_RATE_LIMITED";
                case "server_error" -> "AGENT_PROVIDER_UNAVAILABLE";
                default -> "AGENT_PROVIDER_ERROR";
            });
        }
        for (var block : content) {
            if (block.path("type").asText().equals("tool_result")) {
                if (block.path("is_error").asBoolean()) throw new IOException("AGENT_TOOL_FAILED");
                if (!event.path("type").asText().equals("user")
                        || !pendingTools.remove(block.path("tool_use_id").asText())
                        || !block.path("is_error").isMissingNode() && !block.path("is_error").isBoolean()) throw invalid(Rejection.TOOL_RESULT_SEQUENCE);
            }
            if (!block.path("type").asText().equals("tool_use")) continue;
            String name = block.path("name").asText(), id = block.path("id").asText();
            if (!event.path("type").asText().equals("assistant") || !TOOLS.contains(name)
                    || id.isEmpty() || id.length() > 256 || !toolIds.add(id)) throw invalid(Rejection.INVALID_TOOL_CALL);
            pendingTools.add(id);
            toolCounts.merge(name, 1, Integer::sum);
            var input = block.path("input");
            if (!input.isObject()) throw invalid(Rejection.INVALID_TOOL_ARGUMENTS);
            var path = input.path(name.equals("Glob") || name.equals("Grep") ? "path" : "file_path");
            if (path.isMissingNode() && (name.equals("Glob") || name.equals("Grep"))) continue;
            if (!path.isTextual()) throw invalid(Rejection.INVALID_TOOL_ARGUMENTS);
            recordPath(path.asText());
        }
    }

    private void recordPath(String value) throws IOException {
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) path = workspace.resolve(path);
            if (!path.normalize().equals(path) || !path.startsWith(workspace)) throw new IllegalArgumentException();
            if (path.equals(workspace)) return;
            String relative = workspace.relativize(path).toString();
            new ProjectSourcePolicy().validatePath(relative);
            paths.add(relative);
            if (paths.size() > 512) throw limit(LimitReason.PATHS);
        } catch (IllegalArgumentException error) { throw new IOException("AGENT_TOOL_PATH_REJECTED"); }
    }

    private void result(JsonNode event) throws IOException {
        if (!pendingTools.isEmpty()) throw invalid(Rejection.UNRESOLVED_TOOLS);
        if (!event.path("is_error").isBoolean() || !event.path("permission_denials").isArray()) throw invalid(Rejection.INVALID_RESULT_SHAPE);
        if (!event.path("permission_denials").isEmpty()) throw new IOException("AGENT_TOOL_FAILED");
        if (!event.path("subtype").asText().equals("success") || event.path("is_error").booleanValue())
            throw new Incomplete(switch (event.path("subtype").asText()) {
                case "error_max_turns" -> TerminalReason.TURN_LIMIT;
                case "error_max_budget_usd" -> TerminalReason.BUDGET_LIMIT;
                case "error_during_execution" -> TerminalReason.EXECUTION_ERROR;
                default -> TerminalReason.OTHER;
            }, count(event.path("num_turns")));
        inputTokens = count(event.path("usage").path("input_tokens"));
        outputTokens = count(event.path("usage").path("output_tokens"));
        completed = true;
    }

    private long count(JsonNode value) throws IOException {
        if (value.isMissingNode()) return 0;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalid(Rejection.INVALID_USAGE);
        return value.longValue();
    }
    private ProtocolFailure invalid(Rejection rejection) { return new ProtocolFailure(rejection, events); }
    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private OutputLimit limit(LimitReason reason) { return new OutputLimit(reason, events); }
}
