package dev.atoms.demo.source;

import java.io.IOException;
import java.time.Duration;

/** Caller-owned monotonic remaining task time. Internal Java contract, never a client timeout value. */
@FunctionalInterface
public interface SourceExecutionBudget {
    Duration remaining() throws IOException;
}
