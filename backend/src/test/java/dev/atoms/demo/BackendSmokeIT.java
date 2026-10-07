package dev.atoms.demo;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendSmokeIT {
    @Autowired TestRestTemplate http;
    @Autowired DataSource dataSource;

    @Test
    void servesOnlyPublicHealthInformationAndSecurityHeaders() {
        var response = http.getForEntity("/api/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void migratesAndWritesToAnIsolatedPostgresDatabaseWithoutSuperuserRights() throws Exception {
        try (Connection connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            try (var result = sql.executeQuery("SELECT current_database(), current_user, rolsuper "
                    + "FROM pg_roles WHERE rolname = current_user")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("atoms_demo_test");
                assertThat(result.getString(2)).isEqualTo("atoms_test");
                assertThat(result.getBoolean(3)).isFalse();
            }
            try (var result = sql.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
                result.next();
                assertThat(result.getInt(1)).isPositive();
            }
            connection.setAutoCommit(false);
            try {
                sql.execute("CREATE TEMP TABLE foundation_probe (value text NOT NULL) ON COMMIT DROP");
                sql.execute("INSERT INTO foundation_probe VALUES ('postgres-write-ok')");
                try (var result = sql.executeQuery("SELECT value FROM foundation_probe")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("postgres-write-ok");
                }
            } finally {
                connection.rollback();
            }
        }
    }
}
