package dev.atoms.demo.execution;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class OwnedProcessLifetimeTest {
    @TempDir Path temporary;
    @Test void activeIdentityBelongsToTheActualOwnedProcessAndExpiresOnStop() throws Exception {
        UUID task=UUID.randomUUID(), execution=UUID.randomUUID();
        var launched=new CountDownLatch(1); var cancelled=new AtomicBoolean(); var identity=new AtomicReference<OwnedProcess.Running>();
        var worker=Executors.newSingleThreadExecutor();
        Future<OwnedProcess.Failure> result=worker.submit(() -> {
            try {
                new OwnedProcess().runObservedCommand(command(),()->Duration.ofSeconds(10),cancelled::get,task,execution,
                        stream->stream.readAllBytes().length,stream->{stream.readAllBytes();return null;},()->{},running->{identity.set(running);launched.countDown();});
                throw new AssertionError("Hanging fixture exited");
            } catch(OwnedProcess.Failure error) { return error; }
        });
        try {
            assertThat(launched.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(identity.get().matches(task,execution,temporary)).isTrue();
            assertThat(identity.get().matches(task,UUID.randomUUID(),temporary)).isFalse();
            assertThat(identity.get().isAlive()).isTrue(); assertThat(result.isDone()).isFalse();
        } finally { cancelled.set(true); worker.shutdown(); assertThat(worker.awaitTermination(12,TimeUnit.SECONDS)).isTrue(); }
        var failure=result.get(1,TimeUnit.SECONDS);
        assertThat(failure.getMessage()).isEqualTo("EXECUTION_CANCELLED");
        assertThat(failure.stopped().matches(task,execution,temporary)).isTrue();
        assertThat(identity.get().isAlive()).isFalse();
    }
    @Test void preflightRefusalDoesNotIssueRunningIdentityAndObserverFailureStillSettles() {
        var calls=new AtomicInteger();
        assertThatThrownBy(()->new OwnedProcess().runObservedCommand(command(),()->Duration.ofSeconds(5),()->false,
                UUID.randomUUID(),UUID.randomUUID(),stream->null,stream->null,()->{throw new java.io.IOException("PRIVATE");},running->calls.incrementAndGet()))
                .isInstanceOfSatisfying(OwnedProcess.Failure.class,error->{assertThat(error.getMessage()).isEqualTo("EXECUTION_PREFLIGHT_FAILED");assertThat(error.stopped()).isNotNull();});
        assertThat(calls).hasValue(0);
        assertThatThrownBy(()->new OwnedProcess().runObservedCommand(command(),()->Duration.ofSeconds(5),()->false,
                UUID.randomUUID(),UUID.randomUUID(),stream->stream.readAllBytes(),stream->{stream.readAllBytes();return null;},()->{},running->{throw new IllegalStateException("PRIVATE");}))
                .isInstanceOfSatisfying(OwnedProcess.Failure.class,error->{assertThat(error.getMessage()).isEqualTo("EXECUTION_IO_FAILED");assertThat(error.stopped()).isNotNull();});
    }
    private ProcessBuilder command() {
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",
                Path.of("target/test-classes").toAbsolutePath().toString(),Fixture.class.getName()).directory(temporary.toFile());
        builder.environment().clear(); return builder;
    }
    public static class Fixture { public static void main(String[] args) throws Exception { System.in.readAllBytes(); Thread.sleep(60000); } }
}
