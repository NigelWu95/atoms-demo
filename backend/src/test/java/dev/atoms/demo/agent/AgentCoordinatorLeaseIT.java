package dev.atoms.demo.agent;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class AgentCoordinatorLeaseIT extends AgentIntegrationSupport {
    @Autowired DataSource database;

    @Test void onlyOneDatabaseSessionOwnsDispatchAndAClosedOwnerCanBeReplaced() throws Exception {
        try (var first = new AgentCoordinatorLease(database)) {
            assertThat(first.healthy()).isTrue();
            assertThatThrownBy(() -> new AgentCoordinatorLease(database)).isInstanceOf(java.sql.SQLException.class);
            assertThat(first.healthy()).isTrue();
        }
        try (var next = new AgentCoordinatorLease(database)) { assertThat(next.healthy()).isTrue(); }
    }

    @Test void lossOfTheAdvisoryLockIsDetectedEvenWhenConnectionStillWorks() throws Exception {
        var connection = database.getConnection();
        try (var lease = new AgentCoordinatorLease(connection)) {
            assertThat(lease.healthy()).isTrue();
            try (var statement = connection.createStatement()) { statement.execute("SELECT pg_advisory_unlock(718395202)"); }
            assertThat(lease.healthy()).isFalse();
        }
    }
}
