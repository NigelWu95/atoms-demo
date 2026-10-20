package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;

/** Untrusted tool reports become numeric evidence, never persisted messages, titles or absolute paths. */
final class ExecutionDiagnostics {
    static final int REPORT_MAX_BYTES = 1 << 20;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(65536).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record Frontend(int files, int suites, int tests) {}

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
