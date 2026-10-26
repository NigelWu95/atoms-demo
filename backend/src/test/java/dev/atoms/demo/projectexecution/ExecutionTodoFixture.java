package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Maintainer-reviewed sample snapshots; never installed as the default new-user template. */
final class ExecutionTodoFixture implements AutoCloseable {
    private static final String JAVA = "backend/src/main/java/dev/atoms/generated/TodoController.java";
    private static final String TEST = "backend/src/test/java/dev/atoms/generated/TodoControllerTest.java";
    private static final String SQL = "backend/src/main/resources/db/migration/";
    private final JdbcTemplate jdbc;
    private final DataSource database;
    private final Path root;
    private final List<Path> protectedRoots;
    private final UUID owner = UUID.randomUUID();
    private final SourceAgentFixture source;
    private final ExecutionTestDatabase testDatabase;
    private final ExecutionTestDatabase.Verified originalDatabase;
    private final AnnotationConfigApplicationContext runtime;
    private SourceAgentFixture.Base base;

    ExecutionTodoFixture(JdbcTemplate jdbc, ProjectService projects, PlatformTransactionManager transactions, DataSource database) throws Exception {
        this.jdbc = jdbc; this.database = database;
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class)).isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE status IN ('PREPARING','CANDIDATE_READY','NEEDS_RECONCILIATION')", Integer.class))
                .as("Do not reconcile unrelated source evidence in a new fixture root").isZero();
        var config = new MockEnvironment();
        for (String kind : List.of("node", "npm", "java", "maven")) config.withProperty("atoms.project-execution." + kind, required("execution.contract-" + kind).toString());
        Path privateConfig = required("execution.test-config-directory").resolve("atoms_exec_a_test_20261020.properties");
        Path parent = required("execution.contract-directory").toRealPath();
        assertThat(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)).isTrue();
        root = Files.createTempDirectory(parent, "todo-build-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
        protectedRoots = List.of(root.resolve("source"), root.resolve("work"));
        source = new SourceAgentFixture(jdbc, projects, transactions, root);
        try {
            source.start(database);
            jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
            base = source.create(owner);
            testDatabase = ExecutionTestDatabase.load(Map.of(base.projectId(), privateConfig), protectedRoots,
                    Set.of("atoms_demo", "atoms_demo_test"), Set.of("postgres", "atoms", "atoms_test"));
            originalDatabase = testDatabase.verify(base.projectId(), new ExecutionProcess.Deadline(Duration.ofSeconds(15)));
            Path approval = root.resolve("approval.json");
            Files.copy(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json"), approval);
            Files.setPosixFilePermissions(approval, PosixFilePermissions.fromString("rw-------"));
            config.withProperty("atoms.project-execution.enabled", "true").withProperty("atoms.project-execution.maintainer-ids", owner.toString())
                    .withProperty("atoms.project-execution.root", root.resolve("execution").toString())
                    .withProperty("atoms.project-execution.policy", approval.toString())
                    .withProperty("atoms.project-execution.test-databases", new ObjectMapper().writeValueAsString(Map.of(base.projectId().toString(), privateConfig.toString())));
            runtime = runtime(config, projects, transactions);
        } catch (Exception | Error failure) { source.close(); throw failure; }
    }

    ExecutionBuild build(SourceTree tree) throws Exception {
        base = source.accept(owner, base, tree);
        var service = runtime.getBean(ProjectExecutionService.class);
        var coordinator = runtime.getBean(ExecutionCoordinator.class);
        var accepted = service.submit(owner, base.projectId(), base.versionId(), UUID.randomUUID().toString());
        assertThat(accepted.treeSha256()).isEqualTo(tree.sha256());
        System.out.printf("execution_todo_started build=%s source=%s%n", accepted.id(), base.versionId());
        long start = System.nanoTime();
        var repository = new ExecutionBuildRepository(jdbc);
        var task = repository.get(base.projectId(), accepted.id());
        String previousStatus = task.status();
        while (task.finishedAt() == null && coordinator.isRunning() && System.nanoTime() - start < Duration.ofSeconds(1830).toNanos()) {
            Thread.sleep(250); task = repository.get(base.projectId(), task.id());
            if (!previousStatus.equals(task.status())) {
                System.out.printf("execution_todo_stage build=%s status=%s%n", task.id(), task.status()); previousStatus = task.status();
            }
        }
        assertThat(task.finishedAt()).as("Real dispatcher must settle todo build: %s", task.status()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT execution_pending FROM execution_builds WHERE id=?", Boolean.class, task.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE task_id=?", Integer.class, task.id())).isZero();
        assertThat(source.snapshot(owner, base.projectId(), base.versionId()).sha256()).isEqualTo(tree.sha256());
        System.out.printf("execution_todo_finished build=%s status=%s code=%s%n", task.id(), task.status(), task.errorCode());
        return task;
    }
    Path artifact(ExecutionBuild build) { return root.resolve("execution/artifacts/" + build.id()); }
    Path businessReport(ExecutionBuild build) {
        return root.resolve("execution/builds/" + build.id() + "/project/backend/target/surefire-reports/TEST-dev.atoms.generated.TodoControllerTest.xml");
    }
    Path frontendDistribution(ExecutionBuild build) { return root.resolve("execution/builds/" + build.id() + "/project/frontend/dist"); }
    void assertTestDatabaseUnchanged() throws Exception {
        assertThat(testDatabase.verify(base.projectId(), new ExecutionProcess.Deadline(Duration.ofSeconds(15)))).isEqualTo(originalDatabase);
    }

    private AnnotationConfigApplicationContext runtime(MockEnvironment config, ProjectService projects, PlatformTransactionManager transactions) {
        var context = new AnnotationConfigApplicationContext();
        try {
            context.setEnvironment(config);
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(PlatformTransactionManager.class, () -> transactions);
            context.registerBean(ProjectService.class, () -> projects);
            context.registerBean(DataSource.class, () -> database, definition -> definition.setDestroyMethodName(""));
            context.registerBean(ProjectSourceReadGateway.class, source::readGateway);
            context.registerBean(ProjectSourceEditGateway.class, source::gateway);
            context.registerBean(DependencyPreparationService.class, () -> new DependencyPreparationService(
                    context.getBean(ExecutionSettings.class), context.getBean(DependencyPreparationRepository.class), projects,
                    source.readGateway(), source.gateway(), source.environment(), config, transactions));
            context.registerBean(ExecutionEnvironment.class, () -> new ExecutionEnvironment(context.getBean(ExecutionSettings.class),
                    config, source.readGateway(), source.environment(), projects, jdbc, transactions, source.gateway(), context.getBean(DependencyPreparationService.class)));
            context.register(ExecutionSettings.class, ExecutionBuildRepository.class, ProjectExecutionService.class,
                    DependencyPreparationRepository.class, ExecutionCoordinator.class);
            context.refresh();
            assertThat(context.getBean(ExecutionCoordinator.class).isRunning()).isTrue();
            return context;
        } catch (RuntimeException | Error failure) { context.close(); throw failure; }
    }

    @Override public void close() throws Exception {
        // Preserve failed outputs and audit; only the original owners may release running resources.
        runtime.close(); source.close();
        try (var paths = new ExecutionPaths(root.resolve("execution"), protectedRoots); var lease = new ExecutionCoordinatorLease(database)) {
            assertThat(paths.checkedRoot()).isEqualTo(root.resolve("execution")); assertThat(lease.healthy()).isTrue();
        }
    }

    static SourceTree backend(int version) throws Exception {
        if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported todo sample version");
        var files = new ArrayList<SourceTree.File>();
        for (String name : Files.readAllLines(Path.of("../templates/full-project-v1.files")))
            files.add(new SourceTree.File(name, Files.readAllBytes(Path.of("../templates/full-project-v1").resolve(name)), name.equals("backend/mvnw") ? 0100755 : 0100644));
        String controller = resource("TodoController.java"), tests = resource("TodoControllerTest.java");
        if (version == 2) {
            controller = replace(controller, "long revision) {}", "long revision, int priority) {}");
            controller = replace(controller, "Create(String title)", "Create(String title, Integer priority)");
            controller = replace(controller, "Long revision)", "Long revision, Integer priority)");
            controller = replace(controller, "\"id,title,completed,revision\"", "\"id,title,completed,revision,priority\"");
            controller = replace(controller, "rs.getLong(\"revision\"));", "rs.getLong(\"revision\"), rs.getInt(\"priority\"));");
            controller = replace(controller, "todos(title) VALUES (?)", "todos(title,priority) VALUES (?,coalesce(?,0))");
            controller = replace(controller, "ROW, title(input.title()));", "ROW, title(input.title()), priority(input.priority()));");
            controller = replace(controller, "completed=?,revision=revision+1", "completed=?,priority=coalesce(?,priority),revision=revision+1");
            controller = replace(controller, "ROW, title, input.completed(), id", "ROW, title, input.completed(), priority(input.priority()), id");
            controller = replace(controller, "// EX25_V2_RULES", "private static Integer priority(Integer value) { if (value != null && (value < 0 || value > 2)) throw invalid(); return value; }");
            tests = replace(tests, "@Sql(\"classpath:db/migration/V002__todos.sql\")",
                    "@Sql({\"classpath:db/migration/V002__todos.sql\", \"classpath:db/migration/V003__todo_priority.sql\"})");
            tests = replace(tests, "// EX25_V2_TESTS", """
                    @Test void priorityIsPersistedAndOlderClientsKeepItsValue() throws Exception {
                        long id = create("urgent").path("id").asLong();
                        assertThat(jdbc.queryForObject("SELECT priority FROM generated_app.todos WHERE id=?", Integer.class, id)).isZero();
                        mvc.perform(put("/api/todos/{id}", id).contentType("application/json").content(
                                json.writeValueAsBytes(Map.of("title", "urgent", "completed", false, "revision", 1, "priority", 2))))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.priority").value(2));
                        mvc.perform(put("/api/todos/{id}", id).contentType("application/json").content(
                                json.writeValueAsBytes(Map.of("title", "done", "completed", true, "revision", 2))))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.priority").value(2));
                        assertThat(new TodoController(jdbc).get(id).priority()).isEqualTo(2);
                        for (int priority : new int[]{-1, 3}) mvc.perform(post("/api/todos").contentType("application/json").content(
                                json.writeValueAsBytes(Map.of("title", "bad", "priority", priority)))).andExpect(status().isBadRequest());
                    }
                    """);
            files.add(file(SQL + "V003__todo_priority.sql", "ALTER TABLE generated_app.todos ADD COLUMN priority integer NOT NULL DEFAULT 0 CHECK (priority BETWEEN 0 AND 2);\n"));
        }
        files.add(file(JAVA, controller)); files.add(file(TEST, tests)); files.add(file(SQL + "V002__todos.sql", resource("V002__todos.sql")));
        return new ProjectSourcePolicy().validate(files);
    }
    static SourceTree application(int version) throws Exception {
        var files = new ArrayList<>(backend(version).files());
        int originalSize = files.size();
        files.removeIf(file -> Set.of("frontend/src/App.vue", "frontend/src/App.spec.ts").contains(file.path()));
        assertThat(originalSize - files.size()).isEqualTo(2);
        for (String name : List.of("App.vue", "App.spec.ts", "useTodos.ts")) {
            String content = resource(name);
            if (version == 2 && !name.equals("useTodos.ts")) content = replace(content, "const withPriority = false", "const withPriority = true");
            files.add(file("frontend/src/" + name, content));
        }
        return new ProjectSourcePolicy().validate(files);
    }

    static SourceTree staleWriteMutation() throws Exception {
        var files = new ArrayList<SourceTree.File>();
        for (var file : backend(1).files()) files.add(file.path().equals(JAVA)
                ? file(JAVA, replace(new String(file.content(), StandardCharsets.UTF_8), "AND revision=? RETURNING", "AND ?>0 RETURNING")) : file);
        return new ProjectSourcePolicy().validate(files);
    }
    private static String resource(String name) throws Exception { return Files.readString(Path.of("src/test/resources/projectexecution/todo").resolve(name)); }
    private static SourceTree.File file(String path, String text) { return new SourceTree.File(path, text.getBytes(StandardCharsets.UTF_8), 0100644); }
    private static String replace(String text, String before, String after) {
        assertThat(text.indexOf(before)).isGreaterThanOrEqualTo(0).isEqualTo(text.lastIndexOf(before));
        return text.replace(before, after);
    }
    private static Path required(String key) {
        String value = System.getProperty(key); assertThat(value).as("Explicit %s required", key).isNotBlank();
        Path path = Path.of(value); assertThat(path.isAbsolute() && path.normalize().equals(path)).as("Absolute normalized %s required", key).isTrue();
        return path;
    }
}
