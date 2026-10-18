package dev.atoms.demo.source;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceDeadlineTest {
    @Test void everyStepConsumesTheSameOperationBudget() throws Exception {
        var now = new AtomicLong(100);
        var deadline = new SourceDeadline(Duration.ofSeconds(300), now::get);
        assertThat(deadline.remaining(Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(60));
        now.addAndGet(Duration.ofSeconds(290).toNanos());
        assertThat(deadline.remaining(Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(10));
        now.addAndGet(Duration.ofSeconds(10).toNanos());
        assertThatThrownBy(() -> deadline.remaining(Duration.ofSeconds(60))).isInstanceOf(SourceDeadline.Expired.class);
    }

    @Test void upstreamTaskBudgetCannotBeResetByStartingAnotherSourceStep() throws Exception {
        var now = new AtomicLong();
        var remaining = new AtomicLong(Duration.ofSeconds(8).toNanos());
        SourceExecutionBudget task = () -> Duration.ofNanos(remaining.get());
        var prepare = new SourceDeadline(Duration.ofSeconds(300), now::get, task);
        assertThat(prepare.remaining(Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(8));
        remaining.set(Duration.ofSeconds(2).toNanos());
        var seal = new SourceDeadline(Duration.ofSeconds(300), now::get, task);
        assertThat(prepare.remaining(Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(2));
        assertThat(seal.remaining(Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(2));
        remaining.set(0);
        assertThatThrownBy(() -> seal.remaining(Duration.ofSeconds(60))).isInstanceOf(SourceDeadline.Expired.class);
    }

    @Test void sourceLimitStillWinsAndFailedBudgetDoesNotLeakCallerDetails() throws Exception {
        var now = new AtomicLong();
        var deadline = new SourceDeadline(Duration.ofSeconds(300), now::get, () -> Duration.ofMinutes(30));
        now.addAndGet(Duration.ofSeconds(299).toNanos());
        assertThat(deadline.remaining(Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(1));
        var broken = new SourceDeadline(Duration.ofSeconds(300), now::get, () -> { throw new java.io.IOException("PRIVATE_BUDGET_ERROR"); });
        assertThatThrownBy(() -> broken.remaining(Duration.ofSeconds(60)))
                .isInstanceOf(SourceDeadline.Expired.class).hasMessage("SOURCE_OPERATION_TIMEOUT").hasNoCause();
    }
}
