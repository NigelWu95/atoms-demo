package dev.atoms.demo.projectexecution;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExecutionJavaReportsTest {
    @TempDir Path temporary;
    static String xml() {
        return "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">"
                + "<testcase name=\"works\"/><system-out>PRIVATE_CANARY</system-out></testsuite>";
    }

    @Test void acceptsFreshSurefireXmlAndTextButReturnsOnlyNumbers() throws Exception {
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("root"), List.of())) {
            var task = paths.createBuild(UUID.randomUUID());
            Path backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
            var reports = ExecutionJavaReports.prepare(paths, task, () -> Duration.ofSeconds(10));
            Path directory = backend.resolve("target/surefire-reports");
            Files.writeString(directory.resolve("TEST-Example.xml"), xml());
            Files.writeString(directory.resolve("Example.txt"), "PRIVATE_CANARY");
            assertThat(reports.read(() -> Duration.ofSeconds(10)))
                    .isEqualTo(new ExecutionJavaReport.Summary(1, 1));
            assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test void refusesOldTargetWithoutDeletingEvidence() throws Exception {
        try (var fixture = new Harness()) {
            Path target = Files.createDirectory(fixture.backend.resolve("target"));
            Files.writeString(target.resolve("keep"), "evidence");
            assertThatThrownBy(fixture::prepare).hasMessage("EXECUTION_OUTPUT_NOT_CLEAN");
            assertThat(Files.readString(target.resolve("keep"))).isEqualTo("evidence");
        }
    }

    @Test void rejectsEmptySkippedLinkedWritableOversizedAndUnexpectedReports() throws Exception {
        for (String mode : List.of("absent", "empty", "skipped", "hardlink", "symlink", "directory",
                "writable", "oversized", "unknown", "replaced-directory", "replaced-target")) {
            try (var fixture = new Harness()) {
                var reports = fixture.prepare();
                Path directory = fixture.backend.resolve("target/surefire-reports");
                Path file = directory.resolve("TEST-Example.xml");
                if (!mode.equals("absent")) Files.writeString(file, xml());
                switch (mode) {
                    case "empty" -> Files.writeString(file, "");
                    case "skipped" -> Files.writeString(file, xml().replace("<testcase name=\"works\"/>",
                            "<testcase name=\"works\"><skipped/></testcase>"));
                    case "hardlink" -> Files.createLink(directory.resolve("Example.txt"), file);
                    case "symlink" -> Files.createSymbolicLink(directory.resolve("TEST-Link.xml"), file);
                    case "directory" -> Files.createDirectory(directory.resolve("TEST-Directory.xml"));
                    case "writable" -> Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));
                    case "oversized" -> Files.writeString(file, "x".repeat(ExecutionJavaReport.FILE_MAX_BYTES + 1));
                    case "unknown" -> Files.writeString(directory.resolve("crash.dumpstream"), "PRIVATE_CANARY");
                    case "replaced-directory", "replaced-target" -> {
                        Path replace = mode.equals("replaced-directory") ? directory : directory.getParent();
                        Files.move(replace, replace.resolveSibling("original"));
                        ExecutionRootLease.directory(replace);
                        if (mode.equals("replaced-target")) ExecutionRootLease.directory(directory);
                        Files.writeString(file, xml());
                    }
                    default -> { }
                }
                assertThatThrownBy(() -> reports.read(() -> Duration.ofSeconds(10))).as(mode)
                        .hasMessage("EXECUTION_REPORT_INVALID").hasNoCause();
            }
        }
    }

    @Test void enforcesFileCountAndAggregateBytesBeforeParsing() throws Exception {
        for (boolean tooMany : List.of(true, false)) {
            try (var fixture = new Harness()) {
                var reports = fixture.prepare();
                Path directory = fixture.backend.resolve("target/surefire-reports");
                int count = tooMany ? ExecutionJavaReport.MAX_FILES + 1 : 17;
                String data = tooMany ? xml() : "x".repeat(ExecutionJavaReport.FILE_MAX_BYTES);
                for (int i = 0; i < count; i++) Files.writeString(directory.resolve("TEST-Example" + i + ".xml"), data);
                assertThatThrownBy(() -> reports.read(() -> Duration.ofSeconds(10)))
                        .hasMessage("EXECUTION_REPORT_INVALID");
            }
        }
    }

    @Test void observesBudgetAndRechecksDirectoryMembershipAfterReading() throws Exception {
        try (var fixture = new Harness()) {
            var reports = fixture.prepare();
            Path directory = fixture.backend.resolve("target/surefire-reports");
            Files.writeString(directory.resolve("TEST-Example.xml"), xml());
            assertThatThrownBy(() -> reports.read(() -> Duration.ZERO)).hasMessage("EXECUTION_TIMEOUT");
            var steps = new java.util.concurrent.atomic.AtomicInteger();
            assertThatThrownBy(() -> reports.read(() -> {
                if (steps.incrementAndGet() == 4) Files.writeString(directory.resolve("TEST-New.xml"), xml());
                return Duration.ofSeconds(10);
            })).hasMessage("EXECUTION_REPORT_INVALID");
        }
    }

    final class Harness implements AutoCloseable {
        final ExecutionPaths paths;
        final ExecutionPaths.Task task;
        final Path backend;
        Harness() throws Exception {
            paths = new ExecutionPaths(temporary.toRealPath().resolve(UUID.randomUUID().toString()), List.of());
            task = paths.createBuild(UUID.randomUUID());
            backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
        }
        ExecutionJavaReports prepare() throws Exception {
            return ExecutionJavaReports.prepare(paths, task, () -> Duration.ofSeconds(10));
        }
        @Override public void close() throws Exception { paths.close(); }
    }
}
