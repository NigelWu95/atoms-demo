package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Pre-provisioned private database pairs, assigned once to an owned project. No admin credentials or DDL. */
@Component
final class ExecutionDatabasePool {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final TransactionTemplate transaction;
    ExecutionDatabasePool(Environment environment, JdbcTemplate jdbc, ProjectService projects, PlatformTransactionManager transactions) {
        this.environment = environment; this.jdbc = jdbc; this.projects = projects;
        this.transaction = new TransactionTemplate(transactions); this.transaction.setTimeout(5);
    }
    record Pair(Path test, Path runtime) {}

    static Map<UUID, Pair> read(Environment environment) throws IOException {
        String raw = environment.getProperty("atoms.project-execution.database-pool", "");
        if (raw.isBlank()) return Map.of();
        if (raw.length() > 32768) throw invalid();
        var json = JSON.readTree(raw);
        if (!json.isObject() || json.isEmpty() || json.size() > 4) throw invalid();
        var result = new LinkedHashMap<UUID, Pair>();
        var fields = json.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            UUID id;
            try { id = UUID.fromString(entry.getKey()); } catch (IllegalArgumentException error) { throw invalid(); }
            var value = entry.getValue();
            if (!id.toString().equals(entry.getKey()) || !value.isObject() || value.size() != 2
                    || !value.path("test").isTextual() || !value.path("runtime").isTextual()) throw invalid();
            result.put(id, new Pair(path(value.path("test").textValue()), path(value.path("runtime").textValue())));
        }
        return Collections.unmodifiableMap(result);
    }
    static Map<UUID, Path> merge(Map<UUID, Path> fixed, Map<UUID, Pair> pool, boolean runtime) throws IOException {
        var result = new HashMap<>(fixed);
        for (var entry : pool.entrySet())
            if (result.putIfAbsent(entry.getKey(), runtime ? entry.getValue().runtime() : entry.getValue().test()) != null) throw invalid();
        if (result.size() > 4) throw invalid();
        return Map.copyOf(result);
    }
    static UUID resolve(JdbcTemplate jdbc, UUID project) {
        var binding = new ExecutionBindingRepository(jdbc).get(project);
        return binding == null ? project : binding.target().id();
    }

    /** Called after ownership, archive, idempotency and immutable source validation, before build insertion. */
    void ensure(UUID owner, UUID project) {
        try {
            var pool = read(environment);
            if (pool.isEmpty()) return; // Existing explicit per-project deployments retain their behavior.
            var repository = new ExecutionBindingRepository(jdbc);
            if (repository.get(project) != null) return;
            var roots = new ArrayList<Path>();
            for (String key : List.of("atoms.source.root", "atoms.source.work-root", "atoms.agent.state-root", "atoms.project-execution.root")) {
                String raw = environment.getProperty(key, "");
                if (!raw.isBlank()) roots.add(path(raw));
            }
            var platform = jdbc.queryForMap("SELECT current_database() AS db, current_user AS role");
            Set<String> databases = Set.of((String) platform.get("db")), roles = Set.of((String) platform.get("role"));
            var tests = ExecutionTestDatabase.load(merge(Map.of(), pool, false), roots, databases, roles);
            var runtimes = ExecutionDatabaseBinding.load(merge(Map.of(), pool, true), roots, databases, roles);
            runtimes.requireSeparateFrom(tests);
            long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            OwnedProcess.Budget budget = () -> Duration.ofNanos(end - System.nanoTime());
            for (UUID candidate : pool.keySet()) {
                if (jdbc.queryForObject("SELECT count(*) FROM execution_database_bindings WHERE id=?", Integer.class, candidate) != 0) continue;
                tests.verify(candidate, budget);
                var inspected = runtimes.verify(candidate, budget);
                if (!candidate.equals(inspected.id())) throw invalid();
                var target = new ExecutionBindingRepository.Target(project, inspected.id(), inspected.databasePort(),
                        inspected.database(), inspected.role(), inspected.port(), inspected.databaseOid(), inspected.roleOid());
                try {
                    transaction.executeWithoutResult(tx -> {
                        if (projects.lock(owner, project.toString()).archived()) throw ExecutionBuild.conflict("EXECUTION_PROJECT_ARCHIVED");
                        // Another request for this project may have won while physical inspection ran.
                        if (repository.get(project) == null) repository.reserve(target, 4);
                    });
                    return;
                } catch (ApiException conflict) {
                    if (!"RUN_DB_BINDING_CONFLICT".equals(conflict.code())) throw conflict;
                }
            }
            if (repository.get(project) == null)
                throw new ApiException(429, "RUN_DB_CAPACITY_FULL", "当前可用的项目运行数据库已分配完；源码仍可开发和下载，请联系维护者扩容。");
        } catch (IOException error) {
            throw new ApiException(503, "RUN_DB_POOL_UNAVAILABLE", "项目数据库分配暂不可用，请检查运行环境。");
        }
    }
    private static Path path(String raw) throws IOException {
        if (raw.isBlank() || raw.length() > 4096 || raw.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        try {
            Path value = Path.of(raw);
            if (!value.isAbsolute() || value.getNameCount() < 2 || !value.normalize().equals(value)) throw invalid();
            return value;
        } catch (IllegalArgumentException error) { throw invalid(); }
    }
    private static IOException invalid() { return new IOException("EXECUTION_DB_POOL_INVALID"); }
}
