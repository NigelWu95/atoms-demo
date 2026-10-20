package dev.atoms.demo.projectexecution;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExecutionJavaReportTest {
    private static final String PASS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xsi:noNamespaceSchemaLocation="https://example.invalid/not-fetched.xsd"
              version="3.0.2" name="PRIVATE_SUITE" tests="1" failures="0" errors="0" skipped="0" time="0.02">
              <properties><property name="PRIVATE_SETTING" value="PRIVATE_CANARY"/></properties>
              <testcase name="PRIVATE_TEST" classname="PRIVATE_CLASS" time="0.02">
                <system-out><![CDATA[PRIVATE_OUTPUT]]></system-out>
                <system-err>PRIVATE_ERROR</system-err>
              </testcase>
            </testsuite>
            """;

    @Test void returnsOnlyObservedTestAndFileCounts() throws Exception {
        var result = ExecutionJavaReport.parse(List.of(bytes(PASS), bytes(PASS)));
        assertThat(result.files()).isEqualTo(2);
        assertThat(result.tests()).isEqualTo(2);
        assertThat(result.toString()).doesNotContain("PRIVATE", "http", "classpath");
        assertThat(ExecutionJavaReport.parse(List.of(bytes(PASS.replace("<properties><property name=\"PRIVATE_SETTING\" value=\"PRIVATE_CANARY\"/></properties>", "")))))
                .isEqualTo(new ExecutionJavaReport.Summary(1, 1));
    }

    @Test void rejectsMissingEmptySkippedFailedRerunAndInconsistentEvidence() {
        for (String report : List.of("", "<testsuite/>", PASS.replace("tests=\"1\"", "tests=\"0\""),
                PASS.replace("tests=\"1\"", "tests=\"2\""), PASS.replace("failures=\"0\"", "failures=\"1\""),
                PASS.replace("errors=\"0\"", "errors=\"1\""), PASS.replace("skipped=\"0\"", "skipped=\"1\""),
                PASS.replace(" tests=\"1\"", ""), PASS.replace("tests=\"1\"", "tests=\"4294967297\""),
                PASS.replace("tests=\"1\"", "tests=\"-1\""), PASS.replace("tests=\"1\"", "tests=\"1.0\""),
                PASS.replace("</testcase>", "<skipped/></testcase>"),
                PASS.replace("</testcase>", "<failure>PRIVATE_FAILURE</failure></testcase>"),
                PASS.replace("</testcase>", "<error/></testcase>"),
                PASS.replace("</testcase>", "<flakyFailure/></testcase>"),
                PASS.replace("</testcase>", "<rerunError/></testcase>"),
                PASS.replace("<testcase ", "<unknown ").replace("</testcase>", "</unknown>"),
                PASS.replace("testsuite ", "testsuite xmlns=\"urn:unknown\" "),
                PASS.replace("</testsuite>", "<testcase name=\"extra\"/></testsuite>"),
                PASS.replace("<properties>", "<properties><testcase name=\"fake\"/>"),
                PASS + "<testsuite/>", PASS.substring(0, PASS.length() - 10))) {
            invalid(List.of(bytes(report)));
        }
        invalid(List.of()); invalid(null);
        invalid(Arrays.asList((byte[]) null));
        invalid(List.of(new byte[ExecutionJavaReport.FILE_MAX_BYTES + 1]));
        invalid(Collections.nCopies(513, bytes(PASS)));
    }

    @Test void rejectsDtdEntitiesAndOversizedAggregateWithoutLeakingParserDetails() {
        for (String header : List.of(
                "<!DOCTYPE testsuite [<!ENTITY xxe SYSTEM \"file:///PRIVATE_CANARY\">]>",
                "<!DOCTYPE testsuite SYSTEM \"http://127.0.0.1:1/PRIVATE_CANARY\">",
                "<!DOCTYPE testsuite [<!ENTITY xxe \"PRIVATE_CANARY\">]>")) {
            invalid(List.of(bytes(PASS.replace("<testsuite ", header + "<testsuite ")
                    .replace("PRIVATE_OUTPUT", "&xxe;"))));
        }
        invalid(List.of(bytes(PASS.replace("PRIVATE_ERROR", "&unknown;"))));
        invalid(List.of(bytes(PASS.replace("<properties>", "<properties>".repeat(100)))));
        var large = bytes(PASS.replace("PRIVATE_OUTPUT", "x".repeat(900_000)));
        invalid(Collections.nCopies(20, large));
    }

    private static void invalid(List<byte[]> reports) {
        assertThatThrownBy(() -> ExecutionJavaReport.parse(reports))
                .isInstanceOf(java.io.IOException.class).hasMessage("EXECUTION_REPORT_INVALID").hasNoCause()
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
