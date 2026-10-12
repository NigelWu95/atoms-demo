package dev.atoms.demo.workspace;

import dev.atoms.demo.common.ApiException;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** One lifecycle owner for the OS lease. All potentially slow preflight work is outside DB transactions. */
@Component
public class BuildExecutionEnvironment implements AutoCloseable {
    private final BuildEnvironment configuration;
    private final Consumer<Path> databaseVerifier;
    private BuildEnvironment.Settings settings;
    private BuildRootLease lease;
    private boolean closed;
    @Autowired public BuildExecutionEnvironment(BuildEnvironment configuration) { this(configuration, BuildTestDatabase::verify); }
    BuildExecutionEnvironment(BuildEnvironment configuration, Consumer<Path> databaseVerifier) {
        this.configuration = configuration; this.databaseVerifier = databaseVerifier;
    }
    public synchronized BuildEnvironment.Settings settings() {
        if (closed) throw BuildEnvironment.unavailable();
        if (settings == null) {
            var candidate = configuration.settings();
            databaseVerifier.accept(candidate.testConfig());
            try { lease = new BuildRootLease(candidate.paths()); }
            catch (java.io.IOException error) { throw BuildEnvironment.unavailable(); }
            settings = candidate;
        }
        return settings;
    }
    /** Captured rejection allows a committed idempotency response to win over later environment failure. */
    public synchronized ApiException admissionFailure() {
        try {
            settings();
            lease.checkCapacity();
            return null;
        } catch (ApiException error) { return error; }
        catch (java.io.IOException error) { return new ApiException(503, "BUILD_CAPACITY_EXCEEDED", "本机构建存储达到上限或不可用"); }
    }
    public synchronized void verifyUsage() throws java.io.IOException {
        settings();
        lease.checkUsage();
    }
    @PreDestroy @Override public synchronized void close() throws java.io.IOException {
        closed = true;
        if (lease != null) lease.close();
    }
}
