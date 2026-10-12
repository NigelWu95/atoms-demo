package dev.atoms.demo.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class NativeBuildProcessTest {
    @TempDir Path temporary;
    private Path node() {
        String configured = System.getenv("WORKSPACE_TEST_NODE");
        if (configured != null) return Path.of(configured);
        for (String directory : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            Path executable = Path.of(directory).resolve("node");
            if (Files.isExecutable(executable)) return executable;
        }
        throw new IllegalStateException("Node 24 is required for native process tests");
    }
    private Path fixture(String script) throws Exception {
        Path path = temporary.toRealPath().resolve(UUID.randomUUID() + ".mjs");
        Files.writeString(path, script);
        return path;
    }
    private NativeBuildProcess.Result execute(Path entry, java.util.function.BooleanSupplier cancel, java.util.function.Consumer<String> stages) throws Exception {
        return execute(entry, cancel, stages, Duration.ofSeconds(3));
    }
    private NativeBuildProcess.Result execute(Path entry, java.util.function.BooleanSupplier cancel, java.util.function.Consumer<String> stages, Duration timeout) throws Exception {
        return new NativeBuildProcess(timeout, Duration.ofSeconds(2)).run(node(), entry,
                temporary.toRealPath(), UUID.randomUUID(), temporary.resolve("test.properties"), Map.of("PATH", "/usr/bin:/bin"), cancel, stages);
    }
    @Test void acceptsOnlyBoundedTypedControlFramesFromOwnedProcess() throws Exception {
        var stages = new ArrayList<String>();
        var result = execute(fixture("const executionId=process.argv[3]; console.log(JSON.stringify({type:'stage',executionId,stage:'PREPARING'}));"
                + "console.log(JSON.stringify({type:'complete',executionId,settled:true,errorCode:null,loadedRuntimeId:'ab'.repeat(32)}));"), () -> false, stages::add);
        assertThat(result.settled()).isTrue();
        assertThat(result.errorCode()).isNull();
        assertThat(result.loadedRuntimeId()).isEqualTo("ab".repeat(32));
        assertThat(stages).containsExactly("PREPARING");
    }
    @Test void rejectsWrongExecutionMissingCompletionOversizeAndNonzeroExit() throws Exception {
        for (String script : java.util.List.of("console.log('fake success')", "console.log('x'.repeat(2049))", "process.exit(0)", "process.kill(process.pid,'SIGKILL')",
                "console.log(JSON.stringify({type:'complete',executionId:'wrong',settled:true,errorCode:null,loadedRuntimeId:'ab'.repeat(32)}))",
                "console.log(JSON.stringify({type:'complete',executionId:process.argv[3],settled:true,errorCode:null,loadedRuntimeId:'ab'.repeat(32)}));process.exit(17)")) {
            var result = execute(fixture(script), () -> false, stage -> {});
            assertThat(result.errorCode()).isEqualTo("BUILD_INTERRUPTED");
            assertThat(result.settled()).isFalse();
        }
    }
    @Test void cancellationAndDeadlineUseControlPipeAndWaitForExit() throws Exception {
        String script = "process.stdin.once('data',()=>{console.log(JSON.stringify({type:'complete',executionId:process.argv[3],"
                + "settled:true,errorCode:'BUILD_CANCELLED',loadedRuntimeId:null}));process.stdin.destroy();});process.stdin.resume();";
        var cancelled = new AtomicBoolean(true);
        var result = execute(fixture(script), cancelled::get, stage -> {});
        assertThat(result.settled()).isTrue();
        assertThat(result.errorCode()).isEqualTo("BUILD_CANCELLED");
        var timeout = execute(fixture(script), () -> false, stage -> {}, Duration.ofMillis(500));
        assertThat(timeout.settled()).isTrue();
        assertThat(timeout.errorCode()).isEqualTo("BUILD_TIMEOUT");
    }
}
