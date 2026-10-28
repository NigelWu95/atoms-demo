package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable maintainer registry, constructed once by the future opt-in coordinator, not per request. */
final class ExecutionTestDatabase {
    private final Map<UUID, ExecutionPrivateConfig> bindings;
    private java.util.function.Function<UUID, UUID> resolver = java.util.function.Function.identity();

    private ExecutionTestDatabase(Map<UUID, ExecutionPrivateConfig> bindings) { this.bindings = Map.copyOf(bindings); }
    ExecutionTestDatabase resolving(java.util.function.Function<UUID, UUID> resolver) { this.resolver = resolver; return this; }
    private ExecutionPrivateConfig configuration(UUID project) {
        if (project == null) return null;
        var direct = bindings.get(project);
        return direct != null ? direct : bindings.get(resolver.apply(project));
    }

    /** Registry identity comparison only; never shares credentials or opens a connection. */
    boolean overlapsRuntime(ExecutionPrivateConfig runtime) {
        return bindings.values().stream().anyMatch(test -> test.database().equals(runtime.database())
                || test.role().equals(runtime.role()) || test.bindingId().equals(runtime.bindingId())
                || test.databasePort() == runtime.applicationPort());
    }

    static ExecutionTestDatabase load(Map<UUID, Path> files, List<Path> protectedRoots,
                                      Set<String> forbiddenDatabases, Set<String> forbiddenRoles) throws IOException {
        if (files == null || files.isEmpty() || files.size() > 4 || files.keySet().stream().anyMatch(Objects::isNull)
                || forbiddenDatabases == null || forbiddenDatabases.isEmpty() || forbiddenRoles == null || forbiddenRoles.isEmpty())
            throw ExecutionPrivateConfig.invalid();
        var bindings = new LinkedHashMap<UUID, ExecutionPrivateConfig>();
        var databases = new HashSet<String>(); var roles = new HashSet<String>(); var ids = new HashSet<UUID>();
        for (var entry : files.entrySet()) {
            var config = ExecutionPrivateConfig.read(entry.getValue(), protectedRoots);
            if (forbiddenDatabases.contains(config.database()) || forbiddenRoles.contains(config.role())) throw ExecutionPrivateConfig.invalid();
            // Names are deliberately globally exclusive, including across configured loopback ports.
            if (!databases.add(config.database()) || !roles.add(config.role()) || !ids.add(config.bindingId()))
                throw new IOException("EXECUTION_DB_BINDING_CONFLICT");
            bindings.put(entry.getKey(), config);
        }
        return new ExecutionTestDatabase(bindings);
    }

    /** Not an HTTP grant: caller must also check build authorization, source, policy and tools. */
    Launch authorizeLaunch(UUID project, UUID build, UUID execution, Path backend, OwnedProcess.Budget budget) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
        if (build == null || execution == null || backend == null || !backend.isAbsolute()
                || !backend.normalize().equals(backend) || !backend.endsWith("backend")) throw launchRejected();
        return new Launch(this, verify(project, budget), build, execution, backend);
    }

    /** Issuer-created, one execution only. No credentials or reusable connection properties are exposed. */
    static final class Launch {
        private final ExecutionTestDatabase issuer;
        private final Verified verified;
        private final UUID build, execution;
        private final Path backend;
        private boolean used;

        private Launch(ExecutionTestDatabase issuer, Verified verified, UUID build, UUID execution, Path backend) {
            this.issuer = issuer; this.verified = verified; this.build = build; this.execution = execution; this.backend = backend;
        }

        synchronized void attach(ProcessBuilder builder, UUID build, UUID execution) throws IOException {
            if (used) throw launchRejected();
            used = true;
            if (!this.build.equals(build) || !this.execution.equals(execution) || builder.directory() == null
                    || !backend.equals(builder.directory().toPath())) throw launchRejected();
            issuer.configuration(verified.projectId()).applyTestEnvironment(builder);
        }

        void verify(OwnedProcess.Budget budget) throws IOException {
            if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
            var current = issuer.verify(verified.projectId(), budget);
            if (!verified.bindingId().equals(current.bindingId()) || verified.databaseOid() != current.databaseOid()
                    || verified.roleOid() != current.roleOid()) throw new IOException("EXECUTION_DB_TARGET_REJECTED");
        }
    }

    private static IOException launchRejected() { return new IOException("EXECUTION_DB_LAUNCH_REJECTED"); }

    /** Opens a fresh bounded read-only connection; this registry accepts test configuration only. */
    Verified verify(UUID project, OwnedProcess.Budget budget) throws IOException {
        var config = configuration(project);
        if (config == null) throw new IOException("EXECUTION_DB_BINDING_NOT_FOUND");
        var identity = ExecutionDatabaseInspection.verify(config, budget);
        return new Verified(project, config.bindingId(), identity.databaseOid(), identity.roleOid(), identity.relationCount());
    }

    /** Evidence of this check only; not a reusable permission to migrate, clean, connect or execute. */
    record Verified(UUID projectId, UUID bindingId, long databaseOid, long roleOid, long relationCount) {}
}
