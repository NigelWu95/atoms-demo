package dev.atoms.demo.source;

import java.io.IOException;
import java.time.Duration;
import java.util.function.LongSupplier;

/** One monotonic budget shared by every step, rather than resetting the timeout per file. */
final class SourceDeadline {
    private final long started, budget;
    private final LongSupplier clock;
    private final SourceExecutionBudget upstream;
    SourceDeadline(Duration budget) { this(budget, System::nanoTime); }
    SourceDeadline(Duration budget, LongSupplier clock) {
        this(budget, clock, null);
    }
    SourceDeadline(Duration budget, LongSupplier clock, SourceExecutionBudget upstream) {
        if (budget.isNegative() || budget.isZero()) throw new IllegalArgumentException("Invalid operation timeout");
        this.budget = budget.toNanos(); this.clock = clock; this.started = clock.getAsLong();
        this.upstream = upstream;
    }
    Duration remaining(Duration perStep) throws Expired {
        long elapsed = clock.getAsLong() - started, left = budget - elapsed;
        if (elapsed < 0 || left <= 0 || perStep.isNegative() || perStep.isZero()) throw new Expired();
        Duration remaining = Duration.ofNanos(Math.min(left, perStep.toNanos()));
        if (upstream != null) {
            final Duration caller;
            try { caller = upstream.remaining(); }
            catch (IOException | RuntimeException error) { throw new Expired(); }
            if (caller == null || caller.isNegative() || caller.isZero()) throw new Expired();
            if (caller.compareTo(remaining) < 0) remaining = caller;
        }
        return remaining;
    }
    static final class Expired extends IOException {
        Expired() { super("SOURCE_OPERATION_TIMEOUT"); }
    }
}
