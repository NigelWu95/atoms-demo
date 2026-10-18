package dev.atoms.demo.agent;

import java.io.IOException;
import java.time.Duration;
import java.util.function.LongSupplier;

/** One monotonic execution budget, shared across preparation, I/O and publication. */
final class AgentDeadline {
    private final LongSupplier clock;
    private final long start, budget;

    AgentDeadline(Duration timeout) { this(timeout, System::nanoTime); }
    AgentDeadline(Duration timeout, LongSupplier clock) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(30)) > 0)
            throw new IllegalArgumentException("Invalid Agent deadline");
        this.clock = java.util.Objects.requireNonNull(clock);
        this.start = clock.getAsLong(); this.budget = timeout.toNanos();
    }

    Duration remaining() throws IOException {
        long elapsed = clock.getAsLong() - start;
        if (elapsed < 0 || elapsed >= budget) throw new IOException("AGENT_TIMEOUT");
        return Duration.ofNanos(budget - elapsed);
    }
}
