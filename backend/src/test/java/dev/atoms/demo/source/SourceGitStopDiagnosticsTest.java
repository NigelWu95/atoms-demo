package dev.atoms.demo.source;

import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceGitStopDiagnosticsTest {
    @ParameterizedTest
    @ValueSource(strings = {"ENUMERATE", "CHILD_STOP", "PARENT_STOP", "PARENT_WAIT", "CHILD_WAIT"})
    void identifiesEachStopBranchWithoutChangingTheFailureCodeOrExposingRawErrors(String stage) throws Exception {
        var process = mock(Process.class); var child = mock(ProcessHandle.class);
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(!stage.equals("PARENT_WAIT"));
        when(process.isAlive()).thenReturn(true);
        when(child.onExit()).thenReturn(CompletableFuture.completedFuture(child));
        if (stage.equals("PARENT_STOP")) when(process.destroyForcibly()).thenThrow(new SecurityException("PRIVATE_PARENT_CANARY"));
        if (stage.equals("CHILD_STOP")) when(child.isAlive()).thenThrow(new SecurityException("PRIVATE_CHILD_CANARY"));
        if (stage.equals("CHILD_WAIT")) when(child.onExit()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("PRIVATE_FUTURE_CANARY")));
        var error = catchThrowableOfType(() -> SourceGitProcess.stop(process, ignored -> {
            if (stage.equals("ENUMERATE")) throw new SecurityException("PRIVATE_ENUMERATION_CANARY");
            return List.of(child);
        }), SourceGitProcess.GitFailure.class);
        assertThat(error).hasMessage("GIT_PROCESS_UNSETTLED").hasNoCause().hasNoSuppressedExceptions();
        assertThat(error.stopDetail().stage().name()).isEqualTo(stage);
        assertThat(error.stopDetail().kind().name()).isEqualTo(switch (stage) {
            case "PARENT_WAIT" -> "TIMEOUT";
            case "CHILD_WAIT" -> "WAIT_FAILED";
            default -> "ACCESS_DENIED";
        });
        assertThat(error.stopDetail().interrupted()).isFalse();
        assertThat(error.stopDetail().toString()).doesNotContain("CANARY");
    }

    @Test void preservesTheFirstUncertaintyEvenWhenParentWaitAlsoTimesOut() throws Exception {
        var process = mock(Process.class);
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(false);
        var error = catchThrowableOfType(() -> SourceGitProcess.stop(process, ignored -> {
            throw new UnsupportedOperationException("PRIVATE_FIRST_CANARY");
        }), SourceGitProcess.GitFailure.class);
        assertThat(error.stopDetail().stage().name()).isEqualTo("ENUMERATE");
        assertThat(error.stopDetail().kind().name()).isEqualTo("UNSUPPORTED");
    }

    @Test void interruptedWaitIsDistinguishedAndTheFlagIsRestored() throws Exception {
        var process = mock(Process.class);
        when(process.waitFor(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException("PRIVATE_INTERRUPT_CANARY"));
        try {
            var error = catchThrowableOfType(() -> SourceGitProcess.stop(process, ignored -> List.of()), SourceGitProcess.GitFailure.class);
            assertThat(error.stopDetail().stage().name()).isEqualTo("PARENT_WAIT");
            assertThat(error.stopDetail().kind().name()).isEqualTo("INTERRUPTED");
            assertThat(error.stopDetail().interrupted()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void recognizesOnlyTheFixedNativeProcessEnumerationFrameNotArbitraryClassNames() throws Exception {
        for (boolean nativeFrame : List.of(true, false)) {
            var process = mock(Process.class);
            when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
            var nativeError = new RuntimeException("PRIVATE_NATIVE_CANARY");
            nativeError.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                    nativeFrame ? "java.lang.ProcessHandleImpl" : "PRIVATE_CLASS_CANARY", "getProcessPids0", null, -2)});
            var error = catchThrowableOfType(() -> SourceGitProcess.stop(process, ignored -> { throw nativeError; }), SourceGitProcess.GitFailure.class);
            assertThat(error.stopDetail().kind().name()).isEqualTo(nativeFrame ? "NATIVE_ENUMERATION" : "RUNTIME_FAILURE");
            assertThat(error.stopDetail().toString()).doesNotContain("CANARY");
        }
    }
}
