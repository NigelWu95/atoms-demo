package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Complete maintainer-owned business registry. No HTTP parameters, application writes, migrations or credential grants. */
final class ExecutionDatabaseBinding {
    private final Map<UUID, ExecutionPrivateConfig> configurations;
    private ExecutionDatabaseBinding(Map<UUID, ExecutionPrivateConfig> configurations) { this.configurations = Map.copyOf(configurations); }

    static ExecutionDatabaseBinding load(Map<UUID, Path> files, List<Path> protectedRoots,
            Set<String> forbiddenDatabases, Set<String> forbiddenRoles) throws IOException {
        if (files == null || files.isEmpty() || files.size() > 4 || files.keySet().stream().anyMatch(Objects::isNull)
                || forbiddenDatabases == null || forbiddenDatabases.isEmpty() || forbiddenRoles == null || forbiddenRoles.isEmpty())
            throw ExecutionPrivateConfig.invalid();
        var configurations = new HashMap<UUID, ExecutionPrivateConfig>();
        var databases = new HashSet<String>(); var roles = new HashSet<String>(); var ids = new HashSet<UUID>();
        var ports = new HashSet<Integer>(); var databasePorts = new HashSet<Integer>();
        for (var entry : files.entrySet()) {
            var config = ExecutionPrivateConfig.readBusiness(entry.getValue(), protectedRoots);
            if (forbiddenDatabases.contains(config.database()) || forbiddenRoles.contains(config.role())) throw ExecutionPrivateConfig.invalid();
            if (!databases.add(config.database()) || !roles.add(config.role()) || !ids.add(config.bindingId()) || !ports.add(config.applicationPort()))
                throw new IOException("RUN_DB_BINDING_CONFLICT");
            databasePorts.add(config.databasePort()); configurations.put(entry.getKey(), config);
        }
        if (!Collections.disjoint(ports, databasePorts)) throw new IOException("RUN_DB_BINDING_CONFLICT");
        return new ExecutionDatabaseBinding(configurations);
    }

    /** Fresh read-only physical identity. Persisting this snapshot alone never grants access to business credentials. */
    ExecutionBindingRepository.Target verify(UUID project, OwnedProcess.Budget budget) throws IOException {
        var config = project == null ? null : configurations.get(project);
        if (config == null) throw new IOException("RUN_DB_BINDING_NOT_FOUND");
        var identity = ExecutionDatabaseInspection.verify(config, budget);
        return new ExecutionBindingRepository.Target(project, config.bindingId(), config.databasePort(), config.database(), config.role(),
                config.applicationPort(), identity.databaseOid(), identity.roleOid());
    }

    ExecutionMigrationHistory.Snapshot history(UUID project, OwnedProcess.Budget budget) throws IOException {
        var config = project == null ? null : configurations.get(project);
        if (config == null) throw new IOException("RUN_DB_BINDING_NOT_FOUND");
        return ExecutionMigrationHistory.inspect(project, config, budget);
    }
}
