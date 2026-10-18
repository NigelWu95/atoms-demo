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
}
