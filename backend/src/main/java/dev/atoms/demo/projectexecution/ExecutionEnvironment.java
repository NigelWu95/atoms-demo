package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.ProjectSourceEnvironment;
import dev.atoms.demo.source.ProjectSourceEditGateway;
import dev.atoms.demo.source.ProjectSourceReadGateway;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Lazy native assembly. Only the coordinator opens it, after acquiring its database lease. */
@Component
final class ExecutionEnvironment implements ExecutionCoordinator.Factory {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ExecutionSettings settings;
    private final Environment configuration;
    private final ProjectSourceReadGateway source;
    private final ProjectSourceEnvironment sourceEnvironment;
    private final ProjectService projects;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final ProjectSourceEditGateway edits;
    private final DependencyPreparationService preparations;

    ExecutionEnvironment(ExecutionSettings settings, Environment configuration, ProjectSourceReadGateway source,
            ProjectSourceEnvironment sourceEnvironment, ProjectService projects, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, ProjectSourceEditGateway edits, DependencyPreparationService preparations) {
        this.settings = settings; this.configuration = configuration; this.source = source;
        this.sourceEnvironment = sourceEnvironment; this.projects = projects; this.jdbc = jdbc; this.transactions = transactions;
        this.edits = edits; this.preparations = preparations;
    }

    @Override public ExecutionCoordinator.WorkerResources open() throws IOException {
        ExecutionPaths paths = null;
        try {
            requireReady();
            var config = settings.load();
            var bindings = bindings("test-databases", false);
            var runtimeBindings = bindings("runtime-databases", true);
            if (!bindings.keySet().containsAll(runtimeBindings.keySet())) throw unavailable();
            var protectedRoots = protectedRoots();
            var database = jdbc.queryForMap("SELECT current_database() AS database_name, current_user AS role_name");
            var privateRoots = new ArrayList<>(protectedRoots); privateRoots.add(config.root());
            var databases = ExecutionTestDatabase.load(bindings, privateRoots,
                    Set.of((String) database.get("database_name")), Set.of((String) database.get("role_name")));
            var runtimeDatabases = runtimeBindings.isEmpty() ? null : ExecutionDatabaseBinding.load(runtimeBindings, privateRoots,
                    Set.of((String) database.get("database_name")), Set.of((String) database.get("role_name")));
            if (runtimeDatabases != null) runtimeDatabases.requireSeparateFrom(databases);
            paths = new ExecutionPaths(config.root(), protectedRoots);
            var resources = new NativeResources(paths, bindings, runtimeBindings, protectedRoots, databases, runtimeDatabases);
            resources.check();
            return resources;
        } catch (IOException | RuntimeException error) {
            // Nothing has been launched by this factory; release only the resource opened by this attempt.
            if (paths != null) try { paths.close(); } catch (IOException ignored) { /* No driver/path detail escapes. */ }
            throw unavailable();
        }
    }

    private final class NativeResources implements ExecutionCoordinator.WorkerResources {
        private final ExecutionPaths paths;
        private final Map<UUID, Path> bindings;
        private final Map<UUID, Path> runtimeBindings;
        private final ExecutionDatabaseBinding runtimeDatabases;
        private final List<Path> protectedRoots;
        private final ExecutionBuildWorker worker;
        private final DependencyPreparationWorker preparationWorker;
        private ExecutionRuntimeResources runtimeResources;

        NativeResources(ExecutionPaths paths, Map<UUID, Path> bindings, Map<UUID, Path> runtimeBindings, List<Path> protectedRoots,
                ExecutionTestDatabase databases, ExecutionDatabaseBinding runtimeDatabases) {
            this.paths = paths; this.bindings = bindings; this.protectedRoots = protectedRoots;
            this.runtimeBindings = runtimeBindings; this.runtimeDatabases = runtimeDatabases;
            worker = new ExecutionBuildWorker(settings, projects, jdbc, transactions, paths, source, databases, protectedRoots);
            preparationWorker = new DependencyPreparationWorker(settings, projects, jdbc, transactions, paths, source, edits, preparations, protectedRoots);
        }
        @Override public void check() throws IOException {
            try {
                requireReady();
                if (!paths.checkedRoot().equals(settings.load().root()) || !bindings.equals(bindings("test-databases", false))
                        || !runtimeBindings.equals(bindings("runtime-databases", true))
                        || !protectedRoots.equals(protectedRoots())) throw unavailable();
                if (runtimeDatabases != null) runtimeDatabases.verifyConfiguration();
            } catch (IOException | RuntimeException error) { throw unavailable(); }
        }
        @Override public ExecutionBuild run(ExecutionBuild build, BooleanSupplier cancelled, OwnedProcess.Preflight coordination) throws IOException {
            check();
            return worker.run(build.ownerId(), build.projectId(), build.id(), cancelled, coordination);
        }
        @Override public DependencyPreparationRepository.Task runPreparation(DependencyPreparationRepository.Task task,
                BooleanSupplier cancelled, OwnedProcess.Preflight coordination) throws IOException {
            check();
            return preparationWorker.run(task.ownerId(), task.projectId(), task.id(), cancelled, coordination);
        }
        @Override public synchronized ExecutionRuntimeResources runtime(OwnedProcess.Preflight coordination) throws IOException {
            check();
            if (runtimeDatabases == null) return null;
            if (runtimeResources != null || coordination == null) throw new IOException("RUN_RESOURCES_ALREADY_ATTACHED");
            runtimeResources = new ExecutionRuntimeResources(settings, jdbc, transactions, source, paths, runtimeDatabases,
                    protectedRoots, () -> { check(); coordination.check(); });
            return runtimeResources;
        }
        @Override public synchronized void close() throws IOException {
            if (runtimeResources != null && runtimeResources.hasHeldProcesses()) throw new IOException("RUN_PROCESS_UNSETTLED");
            paths.close();
        }
    }

    private void requireReady() throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive() || !settings.enabled() || !source.readyForExecution())
            throw unavailable();
    }

    private List<Path> protectedRoots() throws IOException {
        var paths = sourceEnvironment.settings().paths();
        var roots = new ArrayList<>(List.of(paths.root(), paths.workRoot()));
        String agentRoot = configuration.getProperty("atoms.agent.state-root", "");
        if (!agentRoot.isBlank()) roots.add(path(agentRoot));
        else if (configuration.getProperty("atoms.agent.enabled", Boolean.class, false)) throw unavailable();
        return List.copyOf(roots);
    }

    private Map<UUID, Path> bindings(String name, boolean optional) throws IOException {
        String raw = configuration.getProperty("atoms.project-execution." + name, "");
        if (optional && raw.isBlank()) return Map.of();
        if (raw.length() > 16384 || raw.isBlank()) throw unavailable();
        var json = JSON.readTree(raw);
        if (!json.isObject() || json.isEmpty() || json.size() > 4) throw unavailable();
        var bindings = new HashMap<UUID, Path>();
        var fields = json.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            UUID project = UUID.fromString(field.getKey());
            if (!project.toString().equals(field.getKey()) || !field.getValue().isTextual()) throw unavailable();
            bindings.put(project, path(field.getValue().textValue()));
        }
        return Map.copyOf(bindings);
    }

    private static Path path(String raw) throws IOException {
        if (raw.length() > 4096 || raw.codePoints().anyMatch(Character::isISOControl)) throw unavailable();
        Path path = Path.of(raw);
        if (!path.isAbsolute() || path.getNameCount() < 2 || !path.normalize().equals(path)) throw unavailable();
        return path;
    }
    private static IOException unavailable() { return new IOException("EXECUTION_ENVIRONMENT_UNAVAILABLE"); }
}
