package dev.atoms.demo.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Test-only Anthropic wire fixture. Never forwards traffic or reads a real credential. */
final class AgentProtocolFixture implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final Path cli, project, config, temporary;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, ToolResult> toolResults = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile List<Step> steps = List.of();
    private volatile boolean configurationCanarySeen, outsideContentSeen;
    private AgentStreamDecoder.Summary summary;
    private static final String CONFIG_CANARY = "atoms-fixture-customization-must-not-load";
    private static final String OUTSIDE_CANARY = "atoms-fixture-outside-content-must-not-leak";
    private volatile boolean readReturnedOriginalContent, editReturnedSuccess;
    private volatile String failure;

    AgentProtocolFixture(Path root, Path cli) throws IOException {
        this.cli = cli;
        this.project = directory(root.resolve("project"));
        this.config = directory(root.resolve("config"));
        this.temporary = directory(root.resolve("tmp"));
        Files.writeString(project.resolve("hello.txt"), "before\n");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    Path project() { return project; }
    List<Request> requests() { return List.copyOf(requests); }
    boolean readReturnedOriginalContent() { return readReturnedOriginalContent; }
    boolean editReturnedSuccess() { return editReturnedSuccess; }
    Map<String, ToolResult> toolResults() { return Map.copyOf(toolResults); }
    boolean configurationCanarySeen() { return configurationCanarySeen; }
    boolean outsideContentSeen() { return outsideContentSeen; }
    AgentStreamDecoder.Summary summary() { return summary; }
    record ToolResult(boolean error, String content) {}
    private record Step(String id, String name, Map<String, Object> input) {}
    record Request(String apiKey, String model, int maxTokens, List<String> tools, boolean stream,
                   Map<String, List<String>> toolFields) {}
    record Result(int exitCode, boolean success, int permissionDenials, List<String> initialTools) {}

    Result edit(boolean separateToolArguments) throws Exception {
        steps = editSteps();
        return run(separateToolArguments);
    }

    Result exerciseProjectTools() throws Exception {
        Files.writeString(project.resolve("CLAUDE.md"), CONFIG_CANARY);
        Files.writeString(project.resolve("AGENTS.md"), CONFIG_CANARY);
        Files.writeString(config.resolve("CLAUDE.md"), CONFIG_CANARY);
        Files.writeString(config.resolve("AGENTS.md"), CONFIG_CANARY);
        var script = new ArrayList<Step>();
        script.add(new Step("tool_glob", "Glob", Map.of("pattern", "**/*.txt", "path", project.toString())));
        script.add(new Step("tool_grep", "Grep", Map.of("pattern", "before", "path", project.toString(), "output_mode", "content")));
        script.addAll(editSteps());
        script.add(write("tool_vue", "frontend/src/App.vue", "<template><h1>Tasks</h1></template>\n"));
        script.add(write("tool_java", "backend/src/main/java/demo/TaskController.java", "package demo; public class TaskController {}\n"));
        script.add(write("tool_sql", "backend/src/main/resources/db/migration/V1__tasks.sql", "create table tasks (id bigint primary key);\n"));
        steps = List.copyOf(script);
        return run(false);
    }

    Result exerciseDeniedTools() throws Exception {
        Path outside = project.getParent().resolve("outside-read.txt");
        Files.writeString(outside, OUTSIDE_CANARY);
        Files.createSymbolicLink(project.resolve("linked.txt"), outside);
        steps = List.of(new Step("outside_read", "Read", Map.of("file_path", outside.toString())),
                new Step("outside_write", "Write", Map.of("file_path", project.getParent().resolve("outside-write.txt").toString(), "content", "forbidden")),
                new Step("symlink_read", "Read", Map.of("file_path", project.resolve("linked.txt").toString())));
        return run(false);
    }

    private List<Step> editSteps() {
        return List.of(new Step("tool_read", "Read", Map.of("file_path", project.resolve("hello.txt").toString())),
                new Step("tool_edit", "Edit", Map.of("file_path", project.resolve("hello.txt").toString(), "old_string", "before", "new_string", "after")));
    }

    private Step write(String id, String relative, String content) {
        return new Step(id, "Write", Map.of("file_path", project.resolve(relative).toString(), "content", content));
    }

    private Result run(boolean separateToolArguments) throws Exception {
        var identity = AgentCliProbe.verify(cli, config, temporary);
        var settings = new AgentSettings.Configuration(identity.path(), config.getParent(), Set.of(UUID.randomUUID()),
                false, 0, 128000, 24, Duration.ofSeconds(1800), Duration.ofSeconds(300));
        var builder = AgentInvocation.create(identity.path(), settings, project, config, temporary, "offline-fixture-key");
        if (separateToolArguments) {
            var arguments = new ArrayList<>(builder.command());
            for (String flag : List.of("--tools", "--allowedTools")) {
                int position = arguments.indexOf(flag) + 1;
                arguments.remove(position);
                arguments.addAll(position, List.of("Read", "Glob", "Grep", "Write", "Edit"));
            }
            builder.command(arguments);
        }
        // Loopback injection exists only in test code, not as an operator/user endpoint setting.
        builder.environment().put("ANTHROPIC_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/anthropic");
        identity.verifyUnchanged();
        Process process = builder.start();
        var readers = Executors.newFixedThreadPool(2);
        var stdout = readers.submit(() -> bounded(process.getInputStream(), 1 << 20));
        var stderr = readers.submit(() -> bounded(process.getErrorStream(), 256 << 10));
        try {
            try (var input = process.getOutputStream()) {
                input.write("Read hello.txt and replace its first line with after. Use only the file tools.\n"
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(45, TimeUnit.SECONDS)) throw new AssertionError("Offline CLI timed out; requests=" + requests.size());
            String output = new String(stdout.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            stderr.get(5, TimeUnit.SECONDS);
            if (failure != null) throw new AssertionError(failure);
            JsonNode result = null;
            var initialTools = new ArrayList<String>();
            var eventKinds = new ArrayList<String>();
            for (String line : output.lines().toList()) {
                JsonNode event = json.readTree(line);
                eventKinds.add(event.path("type").asText() + "/" + event.path("subtype").asText());
                if (event.path("type").asText().equals("system") && event.path("subtype").asText().equals("init"))
                    event.path("tools").forEach(tool -> initialTools.add(tool.asText()));
                if (event.path("type").asText().equals("system") && event.path("subtype").asText().equals("init")) {
                    for (String field : List.of("mcp_servers", "plugins", "skills"))
                        eventKinds.add(field + ":" + event.path(field).getNodeType() + ":" + event.path(field).size());
                    for (var plugin : event.path("plugins")) {
                        var fields = new ArrayList<String>();
                        plugin.fieldNames().forEachRemaining(fields::add);
                        String name = plugin.path("name").asText();
                        String pluginPath = plugin.path("path").asText();
                        String location = pluginPath.startsWith(config.toString()) ? "private-config"
                                : pluginPath.startsWith(cli.getParent().getParent().toString()) ? "cli-install"
                                : pluginPath.startsWith("/Users/") ? "user-directory"
                                : pluginPath.startsWith("/Library/") || pluginPath.startsWith("/etc/") ? "system-directory"
                                : pluginPath.equals("builtin") ? "builtin" : pluginPath.isEmpty() ? "empty" : "other";
                        String source = plugin.path("source").asText();
                        eventKinds.add("plugin:" + (name.matches("[a-zA-Z0-9@._-]{1,100}") ? name : "redacted")
                                + ":fields=" + fields + ":location=" + location
                                + ":source=" + (source.matches("[a-zA-Z0-9@._-]{1,100}") ? source : "redacted"));
                    }
                }
                if (event.path("type").asText().equals("result")) {
                    if (result != null) throw new AssertionError("Conflicting CLI results");
                    result = event;
                }
            }
            if (result == null) throw new AssertionError("Missing CLI result; exit=" + process.exitValue());
            if (result.path("permission_denials").isEmpty() && result.path("subtype").asText().equals("success")) {
                try { summary = new AgentStreamDecoder(project).read(new java.io.ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8))); }
                catch (IOException error) { throw new AssertionError("CLI decoder rejected event kinds " + eventKinds, error); }
            }
            return new Result(process.exitValue(), result.path("subtype").asText().equals("success")
                    && !result.path("is_error").asBoolean(true), result.path("permission_denials").size(), List.copyOf(initialTools));
        } finally {
            for (var child : process.descendants().toList()) if (child.isAlive()) child.destroyForcibly();
            if (process.isAlive()) process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("Offline CLI did not exit");
            readers.shutdownNow();
        }
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("POST") || !exchange.getRequestURI().getPath().equals("/anthropic/v1/messages")) {
                failure = "Unexpected local API route";
                exchange.sendResponseHeaders(404, -1); return;
            }
            JsonNode body = json.readTree(bounded(exchange.getRequestBody(), 2 << 20));
            configurationCanarySeen |= body.toString().contains(CONFIG_CANARY);
            outsideContentSeen |= body.toString().contains(OUTSIDE_CANARY);
            var toolNames = new ArrayList<String>();
            var toolFields = new java.util.LinkedHashMap<String, List<String>>();
            body.path("tools").forEach(tool -> {
                String name = tool.path("name").asText();
                toolNames.add(name);
                var fields = new ArrayList<String>();
                tool.path("input_schema").path("properties").fieldNames().forEachRemaining(fields::add);
                toolFields.put(name, List.copyOf(fields));
            });
            requests.add(new Request(exchange.getRequestHeaders().getFirst("x-api-key"), body.path("model").asText(),
                    body.path("max_tokens").asInt(), List.copyOf(toolNames), body.path("stream").asBoolean(), Map.copyOf(toolFields)));
            int round = requests.size();
            if (round > steps.size() + 1) {
                failure = "Unexpected local API retry or auxiliary request";
                exchange.sendResponseHeaders(400, -1); return;
            }
            for (var message : body.path("messages")) for (var block : message.path("content")) {
                if (!block.path("type").asText().equals("tool_result")) continue;
                toolResults.put(block.path("tool_use_id").asText(), new ToolResult(block.path("is_error").asBoolean(), block.path("content").toString()));
                if (block.path("tool_use_id").asText().equals("tool_read"))
                    readReturnedOriginalContent = !block.path("is_error").asBoolean() && block.path("content").toString().contains("before");
                if (block.path("tool_use_id").asText().equals("tool_edit"))
                    editReturnedSuccess = !block.path("is_error").asBoolean();
            }
            byte[] response = stream(round).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } catch (Exception error) { failure = "Invalid local API protocol: " + error.getClass().getSimpleName(); }
    }

    // https://platform.claude.com/docs/en/build-with-claude/streaming#event-types
    private String stream(int round) throws IOException {
        var output = new StringBuilder();
        var message = json.createObjectNode().put("id", "msg_" + round).put("type", "message")
                .put("role", "assistant").put("model", "deepseek-flash").putNull("stop_reason").putNull("stop_sequence");
        message.putArray("content");
        message.set("usage", json.valueToTree(Map.of("input_tokens", 30, "output_tokens", 1)));
        event(output, "message_start", Map.of("message", message));
        if (round <= steps.size()) {
            Step step = steps.get(round - 1);
            event(output, "content_block_start", Map.of("index", 0, "content_block",
                    Map.of("type", "tool_use", "id", step.id(), "name", step.name(), "input", Map.of())));
            event(output, "content_block_delta", Map.of("index", 0, "delta",
                    Map.of("type", "input_json_delta", "partial_json", json.writeValueAsString(step.input()))));
        } else {
            event(output, "content_block_start", Map.of("index", 0, "content_block", Map.of("type", "text", "text", "")));
            event(output, "content_block_delta", Map.of("index", 0, "delta", Map.of("type", "text_delta", "text", "Edited.")));
        }
        event(output, "content_block_stop", Map.of("index", 0));
        var delta = json.createObjectNode().put("stop_reason", round <= steps.size() ? "tool_use" : "end_turn").putNull("stop_sequence");
        event(output, "message_delta", Map.of("delta", delta, "usage", Map.of("output_tokens", 20)));
        event(output, "message_stop", Map.of());
        return output.toString();
    }

    private void event(StringBuilder output, String type, Map<String, ?> fields) throws IOException {
        var value = json.valueToTree(fields);
        ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("type", type);
        output.append("event: ").append(type).append("\ndata: ").append(json.writeValueAsString(value)).append("\n\n");
    }

    private static byte[] bounded(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IOException("Offline fixture stream limit");
        return bytes;
    }

    private static Path directory(Path path) throws IOException {
        return Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    @Override public void close() { server.stop(0); }
}
