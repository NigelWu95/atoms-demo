package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Untrusted tool reports become numeric evidence, never persisted messages, titles or absolute paths. */
public final class ExecutionDiagnostics {
    private ExecutionDiagnostics() {}
    static final int REPORT_MAX_BYTES = 1 << 20;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(65536).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record Frontend(int files, int suites, int tests) {}
    private static final String UNAVAILABLE = "EXECUTION_DIAGNOSTIC_UNAVAILABLE";
    private static final Set<String> STATUSES = Set.of("QUEUED", "PREPARING", "INSTALLING", "CHECKING", "PACKAGING", "SUCCEEDED",
            "RESOLVING", "ACCEPTING", "SOURCE_SAVED", "NO_CHANGES", "STARTING", "READY", "STOPPING", "STOPPED",
            "FAILED", "CANCELLED", "TIMED_OUT", "NEEDS_RECONCILIATION");
    // Exact platform codes only. An arbitrary EXECUTION_/SOURCE_/RUN_ prefix is not a safe diagnostic.
    private static final Set<String> CODES = Set.of(("""
            EXECUTION_CANCELLED EXECUTION_CHECK_FAILED EXECUTION_CHILD_PROCESS_REMAINS EXECUTION_CONFIGURATION_INVALID
            EXECUTION_COORDINATOR_UNAVAILABLE EXECUTION_DB_BINDING_CONFLICT EXECUTION_DB_BINDING_NOT_FOUND
            EXECUTION_DB_CONFIGURATION_INVALID EXECUTION_DB_TARGET_REJECTED EXECUTION_DB_UNAVAILABLE
            EXECUTION_DEPENDENCY_POLICY_INVALID EXECUTION_DISABLED EXECUTION_ENVIRONMENT_UNAVAILABLE EXECUTION_FORBIDDEN
            EXECUTION_INTERRUPTED EXECUTION_IO_FAILED EXECUTION_LOCK_CHANGES_REJECTED EXECUTION_MAVEN_APPROVAL_REQUIRED
            EXECUTION_MAVEN_POLICY_REJECTED EXECUTION_NPM_APPROVAL_REQUIRED EXECUTION_NPM_LOCK_REQUIRED EXECUTION_NPM_POLICY_REJECTED
            EXECUTION_OUTPUT_LIMIT EXECUTION_OUTPUT_NOT_CLEAN EXECUTION_PACKAGING_INVALID EXECUTION_PREFLIGHT_FAILED
            EXECUTION_PREPARATION_EDIT_UNAVAILABLE EXECUTION_PREPARATION_OWNER_CHANGED EXECUTION_PREPARATION_UNAVAILABLE
            EXECUTION_PROCESS_FAILED EXECUTION_PROCESS_UNSETTLED EXECUTION_PROJECT_ARCHIVED EXECUTION_BUILD_OWNER_CHANGED
            EXECUTION_QUEUE_TIMEOUT EXECUTION_RECOVERY_UNAVAILABLE EXECUTION_REPORT_INVALID EXECUTION_ROOT_REFUSED
            EXECUTION_SOURCE_CHANGED EXECUTION_SOURCE_INVALID EXECUTION_SOURCE_UNSETTLED EXECUTION_START_FAILED
            EXECUTION_STATE_UNAVAILABLE EXECUTION_STORAGE_CAPACITY EXECUTION_STORAGE_UNAVAILABLE EXECUTION_TIMEOUT
            EXECUTION_TOOL_CHANGED EXECUTION_TOOL_UNSUPPORTED EXECUTION_ARTIFACT_INVALID
            SOURCE_BASE_CONFLICT SOURCE_COORDINATOR_UNAVAILABLE SOURCE_EDIT_CANCELLED SOURCE_NEEDS_RECONCILIATION
            SOURCE_OPERATION_TIMEOUT SOURCE_STORAGE_UNAVAILABLE
            RUN_AUTHORIZATION_CHANGED RUN_CANCELLED RUN_CHILD_PROCESS_REMAINS RUN_CONFIGURATION_CHANGED
            RUN_COORDINATOR_UNAVAILABLE RUN_DB_STATE_UNAVAILABLE RUN_EXECUTION_FAILED RUN_EXITED RUN_HEALTH_INVALID
            RUN_INTERRUPTED RUN_MIGRATION_CHECK_FAILED RUN_MIGRATION_DB_UNAVAILABLE RUN_MIGRATION_HISTORY_INVALID
            RUN_MIGRATION_INCOMPATIBLE RUN_MIGRATION_INPUT_CHANGED RUN_MIGRATION_REVIEW_REQUIRED RUN_MIGRATION_REVIEW_STALE
            RUN_MIGRATION_TARGET_INVALID RUN_PORT_IN_USE RUN_PREFLIGHT_FAILED RUN_PROCESS_EXITED RUN_PROCESS_FAILED
            RUN_PROCESS_UNSETTLED RUN_PROJECT_ARCHIVED RUN_RECOVERY_UNAVAILABLE RUN_RECOVERY_UNCERTAIN RUN_START_FAILED
            RUN_START_TIMEOUT RUN_STATE_UNAVAILABLE RUN_STOPPED RUN_STOP_PENDING RUN_WORKSPACE_NOT_CLEAN
            """).strip().split("\\s+"));
    private static final Map<String, Long> COUNTS = Map.of("files", 20000L, "tests", 10000L, "suites", 10000L,
            "bytes", 1L << 30, "stdoutBytes", (long) ExecutionProcess.STREAM_MAX_BYTES, "stderrBytes", (long) ExecutionProcess.STREAM_MAX_BYTES);

    /** Parsed display data, never a raw tool report. Unknown/malformed records remain visible as unavailable. */
    public record Detail(String type, String status, String operation, String probe, String phase, UUID executionId,
            String code, Boolean cancelRequested, Boolean stopRequested, Boolean executionPending, Map<String, Long> counts) {
        public Detail { counts = Map.copyOf(counts); }
    }
    static String status(String value) { return STATUSES.contains(Objects.toString(value, "")) ? value : "UNKNOWN"; }
    static String errorCode(String value) { return value == null ? null : CODES.contains(value) ? value : UNAVAILABLE; }
    static Detail event(String raw) {
        try {
            if (raw == null || raw.length() > 1024 || raw.getBytes(StandardCharsets.UTF_8).length > 1024) throw invalid();
            JsonNode node = JSON.readTree(raw);
            if (node == null || !node.isObject()) throw invalid();
            String stage = choice(node, "status", STATUSES);
            String operation = choice(node, "operation", Set.of("NPM_CI", "NPM_LOCK", "TYPECHECK", "LINT", "UNIT_TEST", "FRONTEND_BUILD", "MAVEN_PACKAGE"));
            String probe = choice(node, "probe", Set.of("NODE", "NPM", "JAVA", "MAVEN"));
            if ((stage == null ? 0 : 1) + (operation == null ? 0 : 1) + (probe == null ? 0 : 1) != 1) throw invalid();
            String phase = choice(node, "phase", Set.of("started", "completed", "failed", "unsettled"));
            String execution = text(node, "executionId"); UUID id = execution == null ? null : UUID.fromString(execution);
            if (id != null && !id.toString().equals(execution) || stage == null && (phase == null || id == null)) throw invalid();
            var counts = new LinkedHashMap<String, Long>();
            for (var field : COUNTS.entrySet()) {
                var value = node.get(field.getKey()); if (value == null) continue;
                if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0 || value.longValue() > field.getValue()) throw invalid();
                counts.put(field.getKey(), value.longValue());
            }
            return new Detail(stage != null ? "STAGE" : operation != null ? "OPERATION" : "PROBE", stage, operation, probe, phase, id,
                    errorCode(text(node, "code")), bool(node, "cancelRequested"), bool(node, "stopRequested"), bool(node, "executionPending"), counts);
        } catch (IOException | RuntimeException error) {
            return new Detail("UNAVAILABLE", null, null, null, null, null, UNAVAILABLE, null, null, null, Map.of());
        }
    }
    private static String text(JsonNode node, String key) throws IOException {
        var value = node.get(key); if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid(); return value.textValue();
    }
    private static String choice(JsonNode node, String key, Set<String> choices) throws IOException {
        String value = text(node, key); if (value != null && !choices.contains(value)) throw invalid(); return value;
    }
    private static Boolean bool(JsonNode node, String key) throws IOException {
        var value = node.get(key); if (value == null) return null;
        if (!value.isBoolean()) throw invalid(); return value.booleanValue();
    }

    // Version-specific format: https://github.com/vitest-dev/vitest/blob/v5.0.3/packages/vitest/src/node/reporters/json.ts
    // success alone permits skip/todo and passWithNoTests. Suites include nested describes, not just files.
    static Frontend frontend(byte[] bytes) throws IOException {
        try {
            if (bytes == null || bytes.length == 0 || bytes.length > REPORT_MAX_BYTES) throw invalid();
            JsonNode report = JSON.readTree(bytes);
            if (report == null || !report.isObject() || !report.path("success").isBoolean()
                    || !report.path("success").booleanValue()) throw invalid();
            int total = count(report, "numTotalTests"), suites = count(report, "numTotalTestSuites");
            if (total == 0 || suites == 0 || count(report, "numPassedTests") != total
                    || count(report, "numPassedTestSuites") != suites) throw invalid();
            for (String key : new String[]{"numFailedTests", "numPendingTests", "numTodoTests",
                    "numFailedTestSuites", "numPendingTestSuites"})
                if (count(report, key) != 0) throw invalid();
            JsonNode files = report.path("testResults");
            if (!files.isArray() || files.isEmpty() || files.size() > 512 || suites < files.size()) throw invalid();
            int observed = 0;
            for (JsonNode file : files) {
                if (!file.isObject() || !file.path("status").asText().equals("passed")
                        || !file.path("message").isTextual() || !file.path("message").textValue().isEmpty()) throw invalid();
                JsonNode assertions = file.path("assertionResults");
                if (!assertions.isArray() || assertions.isEmpty()) throw invalid();
                for (JsonNode assertion : assertions) {
                    if (!assertion.isObject() || !assertion.path("status").asText().equals("passed")
                            || !assertion.path("failureMessages").isArray()
                            || !assertion.path("failureMessages").isEmpty() || ++observed > 10000) throw invalid();
                }
            }
            if (observed != total) throw invalid();
            return new Frontend(files.size(), suites, total);
        } catch (IOException | RuntimeException error) { throw invalid(); }
    }

    private static int count(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 || value.intValue() > 10000)
            throw invalid();
        return value.intValue();
    }
    private static IOException invalid() { return new IOException("EXECUTION_REPORT_INVALID"); }
}
