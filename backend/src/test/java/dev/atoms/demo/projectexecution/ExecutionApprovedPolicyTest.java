package dev.atoms.demo.projectexecution;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExecutionApprovedPolicyTest {
    @TempDir Path temporary;

    @Test void capturesBothPoliciesAndRejectsChangedBytesOrReplacedIdentity() throws Exception {
        Path file = policy();
        var approved = ExecutionApprovedPolicy.capture(file, List.of(), budget());
        assertThat(approved.npm()).isNotNull(); assertThat(approved.maven()).isNotNull();
        approved.verify(budget());
        byte[] original = Files.readAllBytes(file);
        Files.writeString(file, " ".repeat(original.length));
        assertThatThrownBy(() -> approved.verify(budget())).hasMessage("EXECUTION_DEPENDENCY_POLICY_INVALID").hasNoCause();
        Files.write(file, original);
        approved.verify(budget());
        Files.move(file, file.resolveSibling("previous.json"));
        Files.write(file, original);
        assertThatThrownBy(() -> approved.verify(budget())).hasMessage("EXECUTION_DEPENDENCY_POLICY_INVALID");
    }

    @Test void refusesSourceLocatedApprovalOversizedFilesLinksAndWritablePolicy() throws Exception {
        Path file = policy();
        assertThatThrownBy(() -> ExecutionApprovedPolicy.capture(file, List.of(temporary.toRealPath()), budget()))
                .hasMessage("EXECUTION_DEPENDENCY_POLICY_INVALID");
        Path alias = file.resolveSibling("alias.json"); Files.createSymbolicLink(alias, file);
        assertThatThrownBy(() -> ExecutionApprovedPolicy.capture(alias, List.of(), budget()))
                .hasMessage("EXECUTION_DEPENDENCY_POLICY_INVALID");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));
        assertThatThrownBy(() -> ExecutionApprovedPolicy.capture(file, List.of(), budget()))
                .hasMessage("EXECUTION_DEPENDENCY_POLICY_INVALID");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.write(file, new byte[65537]);
        assertThatThrownBy(() -> ExecutionApprovedPolicy.capture(file, List.of(), budget()))
                .hasMessage("EXECUTION_DEPENDENCY_POLICY_INVALID");
    }

    @Test void keepsSharedDeadlineOnCaptureAndRevalidation() throws Exception {
        Path file = policy(); var approved = ExecutionApprovedPolicy.capture(file, List.of(), budget());
        assertThatThrownBy(() -> ExecutionApprovedPolicy.capture(file, List.of(), () -> Duration.ZERO))
                .hasMessage("EXECUTION_TIMEOUT");
        assertThatThrownBy(() -> approved.verify(() -> Duration.ZERO)).hasMessage("EXECUTION_TIMEOUT");
    }

    private Path policy() throws Exception {
        Path file = temporary.toRealPath().resolve("approval.json");
        Files.copy(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json"), file);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(20)); }
}
