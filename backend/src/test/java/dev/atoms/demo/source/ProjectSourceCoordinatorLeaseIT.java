package dev.atoms.demo.source;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceCoordinatorLeaseIT extends SourceIntegrationSupport {
    @Autowired DataSource dataSource;

    @Test void onlyOneCoordinatorOwnsDatabaseQueueRegardlessOfItsFilesystemRoot() throws Exception {
        try (var first = new SourceCoordinatorLease(dataSource)) {
            assertThat(first.healthy()).isTrue();
            assertThatThrownBy(() -> new SourceCoordinatorLease(dataSource)).isInstanceOf(java.sql.SQLException.class);
            assertThat(first.healthy()).isTrue();
        }
        try (var next = new SourceCoordinatorLease(dataSource)) { assertThat(next.healthy()).isTrue(); }
    }

    @Test void aLostOwnedDatabaseConnectionCannotContinueToReportHealthy() throws Exception {
        var connection = dataSource.getConnection();
        try (var lease = new SourceCoordinatorLease(connection)) {
            assertThat(lease.healthy()).isTrue();
            connection.abort(Runnable::run);
            assertThat(lease.healthy()).isFalse();
        } finally { connection.close(); }
    }
}
