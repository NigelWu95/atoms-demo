package dev.atoms.demo.projectexecution;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Controlled coordinator JVM; the parent owns the HTTP fixture so its real stop evidence survives halt. */
public final class ExecutionRunCrashProbe {
    enum Window { BEFORE_INTENT, AFTER_INTENT, AFTER_READY }

    public static void main(String[] args) throws Exception {
        var input = new Properties();
        try (var in = Files.newInputStream(Path.of(args[0]))) { input.load(in); }
        Path root = Path.of(input.getProperty("root")), evidence = root.getParent();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.writeString(evidence.resolve("graceful-shutdown"), "unexpected"); }
            catch (Exception ignored) { }
        }));
        try {
            var database = new HikariDataSource();
            database.setJdbcUrl(input.getProperty("url")); database.setUsername(input.getProperty("user"));
            database.setPassword(input.getProperty("password")); database.setMaximumPoolSize(3);
            database.setMinimumIdle(0); database.setConnectionTimeout(3000);
            var jdbc = new JdbcTemplate(database); jdbc.setQueryTimeout(3);
            if (!ExecutionTestTarget.DATABASE_SCHEMA.equals(
                    jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class)))
                throw new IllegalStateException();
            var lease = new ExecutionCoordinatorLease(database);
            var paths = new ExecutionPaths(root, List.of());
            var runs = new ExecutionRunRepository(jdbc);
            var run = runs.get(UUID.fromString(input.getProperty("project")), UUID.fromString(input.getProperty("run")));
            Window window = Window.valueOf(input.getProperty("window"));
            if (window != Window.BEFORE_INTENT) {
                paths.createRun(run.id());
                UUID execution = UUID.randomUUID();
                new TransactionTemplate(new DataSourceTransactionManager(database)).execute(tx -> runs.begin(run, execution));
                Files.writeString(evidence.resolve("request.tmp"), execution.toString(), StandardOpenOption.CREATE_NEW);
                Files.move(evidence.resolve("request.tmp"), evidence.resolve("request"), StandardCopyOption.ATOMIC_MOVE);
                long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
                while (!Files.exists(evidence.resolve("executed"))) {
                    if (System.nanoTime() >= deadline || !lease.healthy()) throw new IllegalStateException();
                    Thread.sleep(25);
                }
                var current = runs.get(run.projectId(), run.id());
                String expected = window == Window.AFTER_READY ? "READY" : "STARTING";
                if (!expected.equals(current.status()) || !current.executionPending()) throw new IllegalStateException();
            }
            if (!lease.healthy()) throw new IllegalStateException();
            paths.checkedRoot();
            Files.writeString(evidence.resolve("halt-window"), window.name(), StandardOpenOption.CREATE_NEW);
            Runtime.getRuntime().halt(71); // Deliberately no close/finally/shutdown hooks in the coordinator JVM.
        } catch (Throwable error) {
            Files.writeString(evidence.resolve("fixture-failure"), error.getClass().getName(), StandardOpenOption.CREATE_NEW);
            throw new IllegalStateException("RUN_CRASH_PROBE_FAILED");
        }
    }
}
