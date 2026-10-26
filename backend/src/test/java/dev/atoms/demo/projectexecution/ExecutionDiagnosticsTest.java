package dev.atoms.demo.projectexecution;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExecutionDiagnosticsTest {
    @Test void extractsOnlyCountsAndAllowsNestedSuiteCountToDifferFromFileCount() throws Exception {
        var summary = ExecutionDiagnostics.frontend(bytes(report()));
        assertThat(summary.files()).isEqualTo(1);
        assertThat(summary.tests()).isEqualTo(1);
        assertThat(summary.suites()).isEqualTo(2);
        assertThat(summary.toString()).doesNotContain("CANARY", "/private", "title", "message");
    }

    @Test void rejectsMissingMalformedDuplicateTrailingAndOversizedReports() {
        for (String input : List.of("", "null", "[]", "{}", report() + "{}", report().replace("\"success\":true",
                "\"success\":false,\"success\":true"), report().replace("\"numPassedTests\":1", "\"numPassedTests\":4294967297"),
                report().replace("\"numPassedTests\":1", "\"numPassedTests\":1.0"),
                report().replace("\"numPassedTests\":1,", ""), report().replace("\"testResults\":[", "\"testResults\":[".repeat(70))))
            rejected(bytes(input));
        rejected(null);
        rejected(new byte[(1 << 20) + 1]);
    }

    @Test void noTestsSkippedPendingTodoFailedOrInconsistentCountsCannotPass() {
        for (String replacement : List.of("skipped", "pending", "todo", "disabled", "failed", "CANARY"))
            rejected(bytes(report().replace("\"status\":\"passed\",\"title\"", "\"status\":\"" + replacement + "\",\"title\"")));
        for (String field : List.of("numFailedTests", "numFailedTestSuites", "numPendingTests",
                "numPendingTestSuites", "numTodoTests"))
            rejected(bytes(report().replace("\"" + field + "\":0", "\"" + field + "\":1")));
        rejected(bytes(report().replace("\"success\":true", "\"success\":false")));
        rejected(bytes(report().replace("\"numTotalTests\":1", "\"numTotalTests\":0")));
        rejected(bytes(report().replace("\"numTotalTests\":1", "\"numTotalTests\":2")));
        rejected(bytes(report().replace("\"numTotalTests\":1", "\"numTotalTests\":2")
                .replace("\"numPassedTests\":1", "\"numPassedTests\":2")));
        rejected(bytes(report().replace("\"numPassedTestSuites\":2", "\"numPassedTestSuites\":1")));
        rejected(bytes(report().replace("\"status\":\"passed\",\"message\"", "\"status\":\"failed\",\"message\"")));
        rejected(bytes(report().replace("\"failureMessages\":[]", "\"failureMessages\":[\"CANARY\"]")));
        rejected(bytes(report().replace("\"message\":\"\"", "\"message\":\"CANARY\"")));
        rejected(bytes(report().replace("\"testResults\":[", "\"testResults\":[{\"status\":\"passed\",\"assertionResults\":[]},").replace(
                "\"numTotalTestSuites\":2", "\"numTotalTestSuites\":3").replace("\"numPassedTestSuites\":2", "\"numPassedTestSuites\":3")));
    }

    private void rejected(byte[] input) {
        assertThatThrownBy(() -> ExecutionDiagnostics.frontend(input))
                .hasMessage("EXECUTION_REPORT_INVALID").hasNoCause()
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
    }
    @Test void eventDtoRetainsOnlyKnownFieldsAndNeverCopiesToolText() {
        var id = java.util.UUID.randomUUID();
        var detail = ExecutionDiagnostics.event("{\"operation\":\"UNIT_TEST\",\"phase\":\"completed\",\"executionId\":\"" + id
                + "\",\"code\":null,\"files\":1,\"tests\":2,\"suites\":1,\"stdoutBytes\":5,\"stderrBytes\":0,"
                + "\"stderr\":\"PRIVATE_CANARY\",\"command\":\"/private/CANARY\",\"source\":\"CANARY\"}");
        assertThat(detail.type()).isEqualTo("OPERATION"); assertThat(detail.executionId()).isEqualTo(id);
        assertThat(detail.counts()).containsEntry("tests", 2L).containsEntry("stderrBytes", 0L);
        assertThat(detail.toString()).doesNotContain("CANARY", "/private", "command", "source");
        assertThatThrownBy(() -> detail.counts().put("secret", 1L)).isInstanceOf(UnsupportedOperationException.class);
        var stage = ExecutionDiagnostics.event("{\"status\":\"STOPPING\",\"stopRequested\":true,\"executionPending\":true,\"code\":\"RUN_STOP_PENDING\"}");
        assertThat(stage.type()).isEqualTo("STAGE"); assertThat(stage.stopRequested()).isTrue();
        assertThat(stage.code()).isEqualTo("RUN_STOP_PENDING");
        assertThat(ExecutionDiagnostics.event("{\"probe\":\"JAVA\",\"phase\":\"completed\",\"executionId\":\"" + id
                + "\",\"version\":\"CANARY\",\"sha256\":\"CANARY\",\"files\":2,\"bytes\":99}").type()).isEqualTo("PROBE");
    }
    @Test void invalidEventValuesAndForgedErrorPrefixesAreReplacedWithFixedDiagnostics() {
        for (String input : List.of("", "{}", "null", "[]", "{\"status\":\"CANARY\"}",
                "{\"status\":\"READY\",\"status\":\"FAILED\"}", "{\"status\":\"READY\"} {}",
                "{\"operation\":\"npm run CANARY\",\"phase\":\"started\"}",
                "{\"status\":\"READY\",\"stopRequested\":\"CANARY\"}",
                "{\"status\":\"READY\",\"tests\":-1}", "{\"status\":\"READY\",\"tests\":1.5}",
                "{\"status\":\"READY\",\"files\":999999999999999999999}",
                "{\"status\":\"READY\",\"executionId\":\"1-1-1-1-1\"}", "x".repeat(1025))) {
            var detail = ExecutionDiagnostics.event(input);
            assertThat(detail.type()).isEqualTo("UNAVAILABLE");
            assertThat(detail.code()).isEqualTo("EXECUTION_DIAGNOSTIC_UNAVAILABLE");
            assertThat(detail.toString()).doesNotContain("CANARY");
        }
        assertThat(ExecutionDiagnostics.event(null).type()).isEqualTo("UNAVAILABLE");
        assertThat(ExecutionDiagnostics.errorCode("EXECUTION_PRIVATE_CANARY")).isEqualTo("EXECUTION_DIAGNOSTIC_UNAVAILABLE");
        assertThat(ExecutionDiagnostics.errorCode("RUN_RECOVERY_UNCERTAIN")).isEqualTo("RUN_RECOVERY_UNCERTAIN");
        assertThat(ExecutionDiagnostics.errorCode(null)).isNull();
    }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static String report() {
        return """
                {"success":true,"numTotalTests":1,"numPassedTests":1,"numFailedTests":0,
                 "numPendingTests":0,"numTodoTests":0,"numTotalTestSuites":2,"numPassedTestSuites":2,
                 "numFailedTestSuites":0,"numPendingTestSuites":0,"startTime":1,
                 "snapshot":{"unmatched":0},"testResults":[{"name":"/private/CANARY",
                 "status":"passed","message":"","assertionResults":[{"status":"passed","title":"CANARY",
                 "fullName":"CANARY","failureMessages":[],"meta":{"private":"CANARY"}}]}]}
                """;
    }
}
