package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Complete maintainer-owned business registry. No HTTP parameters, application writes or migrations. */
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

    /** Internal command capability, not user authorization or permission to bypass migration review. */
    Launch authorizeLaunch(UUID project, UUID run, UUID execution, ExecutionRunCommand command, OwnedProcess.Budget budget) throws IOException {
        outsideTransaction();
        if (run == null || execution == null || command == null) throw launchRejected();
        var target = verify(project, budget);
        if (!command.matches(run, execution, target)) throw launchRejected();
        command.verify(budget);
        return new Launch(this, target, command);
    }

    /** One use, even if validation fails. Only produces the originally bound, fixed command. */
    static final class Launch {
        private final ExecutionDatabaseBinding issuer;
        private final ExecutionBindingRepository.Target target;
        private final ExecutionRunCommand command;
        private boolean used;

        private Launch(ExecutionDatabaseBinding issuer, ExecutionBindingRepository.Target target, ExecutionRunCommand command) {
            this.issuer = issuer; this.target = target; this.command = command;
        }

        synchronized ProcessBuilder builder(OwnedProcess.Budget budget) throws IOException {
            if (used) throw launchRejected();
            used = true;
            verify(budget);
            var builder = command.builder();
            issuer.configurations.get(target.project()).applyRuntimeEnvironment(builder);
            return builder;
        }

        void verify(OwnedProcess.Budget budget) throws IOException {
            outsideTransaction();
            if (!target.equals(issuer.verify(target.project(), budget))) throw new IOException("RUN_DB_BINDING_CONFLICT");
            command.verify(budget);
        }
    }

    private static void outsideTransaction() throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
    }
    private static IOException launchRejected() { return new IOException("RUN_DB_LAUNCH_REJECTED"); }
}
