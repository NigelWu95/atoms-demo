package dev.atoms.demo.source;

import java.io.IOException;
import java.time.Duration;
import java.util.function.LongSupplier;

/** One monotonic budget shared by every step, rather than resetting the timeout per file. */
final class SourceDeadline {
    private final long started, budget;
    private final LongSupplier clock;
    SourceDeadline(Duration budget) { this(budget, System::nanoTime); }
    SourceDeadline(Duration budget, LongSupplier clock) {
        if (budget.isNegative() || budget.isZero()) throw new IllegalArgumentException("Invalid operation timeout");
        this.budget = budget.toNanos(); this.clock = clock; this.started = clock.getAsLong();
    }
    Duration remaining(Duration perStep) throws Expired {
        long left = budget - (clock.getAsLong() - started);
        if (left <= 0) throw new Expired();
        return Duration.ofNanos(Math.min(left, perStep.toNanos()));
    }
    static final class Expired extends IOException {
        Expired() { super("SOURCE_OPERATION_TIMEOUT"); }
    }
}
