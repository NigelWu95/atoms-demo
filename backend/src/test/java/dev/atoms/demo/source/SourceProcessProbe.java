package dev.atoms.demo.source;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

/** Test-only abrupt exit, never targets a platform or user application process. */
public final class SourceProcessProbe {
    public static void main(String[] args) throws Exception {
        String password = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        var database = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms&connectTimeout=3&socketTimeout=5", "atoms_test", password);
        var jdbc = new JdbcTemplate(database);
        if (!"atoms_demo_test".equals(jdbc.queryForObject("SELECT current_database()", String.class))
                || !"atoms_test".equals(jdbc.queryForObject("SELECT current_user", String.class))) System.exit(9);
        var repository = new SourceRepository(jdbc);
        var environment = new ProjectSourceEnvironment(new MockEnvironment().withProperty("atoms.source.enabled", "true")
                .withProperty("atoms.source.root", args[2]).withProperty("atoms.source.work-root", args[3]).withProperty("atoms.source.git", "/usr/bin/git"));
        var service = new ProjectSourceService(new dev.atoms.demo.project.ProjectService(jdbc), repository,
                new DataSourceTransactionManager(database), environment);
        var operation = repository.operation(UUID.fromString(args[0]), UUID.fromString(args[1]));
        new SourceWorker(service, repository, environment, stage -> {
            if (stage == SourceWorker.Stage.AFTER_GIT) Runtime.getRuntime().halt(71);
        }).execute(operation, ProjectSourceInitializationIT.tree());
        System.exit(10);
    }
}
