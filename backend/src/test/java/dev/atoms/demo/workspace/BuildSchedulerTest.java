package dev.atoms.demo.workspace;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuildSchedulerTest {
    @TempDir Path temporary;
    @Test void startupRecoveryPrecedesAdmissionAndStopIsMonotonic() throws Exception {
        var execution=mock(BuildExecutionEnvironment.class);
        var lifecycle=mock(BuildLifecycle.class);
        var worker=mock(BuildWorker.class);
        var lease=mock(BuildCoordinatorLease.class);
        when(lease.healthy()).thenReturn(true);
        when(execution.settings()).thenReturn(new BuildEnvironment.Settings(new WorkspacePaths(temporary.toRealPath()),temporary,temporary,temporary,Map.of()));
        var scheduler=new BuildScheduler(execution,lifecycle,worker,()->lease);
        scheduler.start();
        var order=inOrder(lifecycle,execution);
        order.verify(execution).settings(); order.verify(lifecycle).interruptPrevious(); order.verify(execution).schedulerReady(true);
        assertThat(scheduler.isRunning()).isTrue();
        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();
        verify(lease).close(); verify(execution).schedulerReady(false);
        scheduler.start();
        verify(lifecycle,times(1)).interruptPrevious();
    }
    @Test void failedCoordinatorNeverRecoversAnotherSchedulersWork() {
        var execution=mock(BuildExecutionEnvironment.class);
        var lifecycle=mock(BuildLifecycle.class);
        var worker=mock(BuildWorker.class);
        var scheduler=new BuildScheduler(execution,lifecycle,worker,()->{throw new java.sql.SQLException();});
        scheduler.start(); scheduler.stop();
        verifyNoInteractions(lifecycle,worker);
        verify(execution,never()).schedulerReady(true);
    }
}
