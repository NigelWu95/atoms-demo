package dev.atoms.fixtures;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.common.IdempotencyService;
import dev.atoms.demo.project.ProjectService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@TestConfiguration(proxyBeanMethods = false)
public class FixtureConfiguration {
    @Bean @DependsOnDatabaseInitialization
    FixtureController fixtureController(JdbcTemplate jdbc, Environment environment, ArtifactService artifacts,
                                         ProjectService projects, IdempotencyService idempotency, ObjectMapper json) {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        String role = jdbc.queryForObject("SELECT current_user", String.class);
        requireTestDatabase(database, role, environment.getProperty("server.address"));
        return new FixtureController(artifacts, projects, idempotency, json);
    }
    static void requireTestDatabase(String database, String role, String address) {
        if (!"atoms_demo_test".equals(database) || !"atoms_test".equals(role) || !"127.0.0.1".equals(address)) {
            throw new IllegalStateException("Fixtures require the dedicated test database/role and loopback binding");
        }
    }
    @Bean @Order(0)
    SecurityFilterChain fixturePage(HttpSecurity http) throws Exception {
        // Only a static test shell is public. Fixture writes still use the real guest/CSRF security chain.
        return http.securityMatcher(request -> "GET".equals(request.getMethod())
                        && ("/test-lab".equals(request.getServletPath()) || "/test-lab.js".equals(request.getServletPath())))
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, "/test-lab", "/test-lab.js").permitAll()
                        .anyRequest().denyAll()).build();
    }
}
