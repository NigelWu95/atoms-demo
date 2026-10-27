package dev.atoms.demo.agent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentStreamDecoderTest {
    private static final String INIT = "{\"type\":\"system\",\"subtype\":\"init\",\"tools\":[\"Read\",\"Glob\",\"Grep\",\"Write\",\"Edit\"],\"mcp_servers\":[]}\n";
    private static final String SUCCESS = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"permission_denials\":[]}\n";

    @Test void returnsOnlyCountsAndValidatedRelativePathsIncludingSplitUtf8() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/agent/stream-success.ndjson").readAllBytes();
        var input = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] output, int offset, int length) { return super.read(output, offset, Math.min(1, length)); }
        };
        var summary = new AgentStreamDecoder(Path.of("/private/project")).read(input);
        assertThat(summary.events()).isEqualTo(4);
        assertThat(summary.toolCounts()).containsEntry("Edit", 1);
        assertThat(summary.paths()).containsExactly("src/App.vue");
        assertThat(summary.inputTokens()).isEqualTo(20);
        assertThat(summary.outputTokens()).isEqualTo(30);
        assertThat(summary.toString()).doesNotContain("PRIVATE_", "/private/project", "before");
    }

    @Test void missingConflictingOrInvalidFinalResultsCannotSucceed() {
        for (String stream : new String[]{INIT, SUCCESS, INIT + SUCCESS + SUCCESS,
                INIT + SUCCESS.replace("false", "\"false\""), INIT + SUCCESS.replace("success", "unknown"),
                INIT + SUCCESS.replace("[]", "[{}]")}) {
            assertThatThrownBy(() -> read(stream)).isInstanceOf(IOException.class);
        }
    }

    @Test void preInitUiInvalidationDoesNotReplaceMandatoryCapabilityInitialization() throws Exception {
        String notification = "{\"type\":\"system\",\"subtype\":\"ui_invalidate\",\"payload\":\"PRIVATE_NOTIFICATION\"}\n";
        assertThat(read(notification + INIT + SUCCESS).events()).isEqualTo(3);
        assertThatThrownBy(() -> read(notification + SUCCESS)).hasMessage("AGENT_PROTOCOL_INVALID");
        assertThatThrownBy(() -> read(notification.replace("ui_invalidate", "hook_started") + INIT + SUCCESS))
                .hasMessage("AGENT_PROTOCOL_INVALID");
    }

    @Test void unexpectedCapabilitiesAndToolErrorsAreRejectedWithoutLeakingPayloads() throws Exception {
        assertThatThrownBy(() -> read(INIT.replace("\"Read\"", "\"Bash\"") + SUCCESS)).hasMessage("AGENT_CLI_UNSUPPORTED");
        try (var stream = getClass().getResourceAsStream("/agent/stream-invalid.ndjson")) {
            assertThatThrownBy(() -> new AgentStreamDecoder(Path.of("/private/project")).read(stream))
                    .isInstanceOf(IOException.class).hasMessageNotContaining("PRIVATE_");
        }
        assertThatThrownBy(() -> read(INIT + "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"is_error\":true}]}}\n" + SUCCESS))
                .hasMessage("AGENT_TOOL_FAILED");
    }

    @Test void onlyVerifiedCliBuiltinPluginMetadataIsAccepted() throws Exception {
        String plugin = "{\"name\":\"cc-plugin-agents-md\",\"path\":\"builtin\",\"source\":\"cc-plugin-agents-md@builtin\"}";
        String init = INIT.replace("\"mcp_servers\":[]", "\"mcp_servers\":[],\"claude_code_version\":\"2.1.302\",\"plugins\":[" + plugin + "]");
        assertThat(read(init + SUCCESS).events()).isEqualTo(2);
        assertThat(read(init.replace("2.1.302", "2.1.303") + SUCCESS).events()).isEqualTo(2);
        assertThat(read(init.replace("2.1.302", "2.1.308") + SUCCESS).events()).isEqualTo(2);
        for (String invalid : new String[]{init.replace("2.1.302", "2.1.304"),
                init.replace("2.1.302", "2.1.309"),
                init.replace("\"builtin\"", "\"/private/plugin\""),
                init.replace("cc-plugin-agents-md@builtin", "cc-plugin-agents-md@user"),
                init.replace("cc-plugin-agents-md", "unknown-plugin"),
                init.replace(plugin, plugin + "," + plugin)}) {
            assertThatThrownBy(() -> read(invalid + SUCCESS)).hasMessage("AGENT_CLI_UNSUPPORTED");
        }
    }

    @Test void limitsLineTotalAndEventCountIndependently() {
        for (var limits : new AgentStreamDecoder.Limits[]{new AgentStreamDecoder.Limits(32, 4096, 10),
                new AgentStreamDecoder.Limits(1024, 128, 10), new AgentStreamDecoder.Limits(1024, 4096, 1)}) {
            assertThatThrownBy(() -> new AgentStreamDecoder(Path.of("/private/project"), limits).read(input(INIT + SUCCESS)))
                    .hasMessage("AGENT_OUTPUT_LIMIT");
        }
    }

    @Test void malformedUtf8DuplicateJsonAndTruncationAreNotAccepted() {
        assertThatThrownBy(() -> read(INIT + "{\"type\":\"result\",\"type\":\"result\"}\n")).hasMessage("AGENT_PROTOCOL_INVALID");
        assertThatThrownBy(() -> read(INIT + SUCCESS.substring(0, 20))).hasMessage("AGENT_PROTOCOL_INVALID");
        assertThatThrownBy(() -> new AgentStreamDecoder(Path.of("/private/project")).read(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 10})))
                .hasMessage("AGENT_PROTOCOL_INVALID");
    }

    @Test void toolPathsOutsideTheWorkspaceOrInsidePrivateConfigurationAreRefused() {
        for (String path : new String[]{"/outside/secret", "../secret", ".claude/settings.json"}) {
            String tool = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"one\",\"name\":\"Read\",\"input\":{\"file_path\":\"" + path + "\"}}]}}\n";
            assertThatThrownBy(() -> read(INIT + tool + SUCCESS)).hasMessage("AGENT_TOOL_PATH_REJECTED");
        }
    }

    @Test void unadvertisedToolsCannotBecomeValidByReturningASuccessfulResult() {
        for (String tool : new String[] {"Bash", "Agent", "Task", "mcp__fixture__read"}) {
            String call = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"one\",\"name\":\""
                    + tool + "\",\"input\":{\"file_path\":\"/private/project/README.md\"}}]}}\n";
            String result = "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"one\",\"is_error\":false}]}}\n";
            assertThatThrownBy(() -> read(INIT + call + result + SUCCESS)).hasMessage("AGENT_PROTOCOL_INVALID");
        }
    }

    @Test void unresolvedToolUseUnknownToolResultsAndHookEventsCannotSucceed() throws Exception {
        String stream;
        try (var input = getClass().getResourceAsStream("/agent/stream-success.ndjson")) {
            stream = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String missingResult = stream.lines().filter(line -> !line.contains("\"type\":\"user\""))
                .collect(java.util.stream.Collectors.joining("\n"));
        for (String invalid : new String[]{missingResult, stream.replace("\"tool_use_id\":\"edit_one\"", "\"tool_use_id\":\"unknown\""),
                INIT + "{\"type\":\"system\",\"subtype\":\"hook_started\"}\n" + SUCCESS})
            assertThatThrownBy(() -> read(invalid)).isInstanceOf(IOException.class);
    }

    private AgentStreamDecoder.Summary read(String value) throws IOException {
        return new AgentStreamDecoder(Path.of("/private/project")).read(input(value));
    }
    private ByteArrayInputStream input(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
}
