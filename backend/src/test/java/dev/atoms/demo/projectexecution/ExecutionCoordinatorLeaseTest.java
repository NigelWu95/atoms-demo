package dev.atoms.demo.projectexecution;

import java.sql.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionCoordinatorLeaseTest {
    Connection connection;
    Statement statement;
    ResultSet rows;
    Consumer<Connection> evict;

    @SuppressWarnings("unchecked") @BeforeEach void connection() throws Exception {
        connection = mock(Connection.class); statement = mock(Statement.class); rows = mock(ResultSet.class);
        evict = mock(Consumer.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true); when(rows.getInt(1)).thenReturn(42); when(rows.getBoolean(2)).thenReturn(true);
    }

    @Test void acquisitionTimeoutDiscardsBeforeReturningConnectionAndDoesNotExposeDriverText() throws Exception {
        when(statement.executeQuery(anyString())).thenThrow(new SQLTimeoutException("PRIVATE_DB_CANARY"));
        unavailable(() -> new ExecutionCoordinatorLease(connection, evict));
        var order = inOrder(connection, evict);
        order.verify(connection).abort(any()); order.verify(evict).accept(connection); order.verify(connection).close();
        verify(statement).setQueryTimeout(3);
    }

    @Test void failedAbortNeverReturnsAnUnknownSessionToThePool() throws Exception {
        when(statement.executeQuery(anyString())).thenThrow(new SQLTimeoutException("PRIVATE_DB_CANARY"));
        doThrow(new SQLException("PRIVATE_ABORT_CANARY")).when(connection).abort(any());
        unavailable(() -> new ExecutionCoordinatorLease(connection, evict));
        verify(connection, never()).close();
        verifyNoInteractions(evict);
    }

    @Test void failedPoolEvictionNeverReturnsTheConnection() throws Exception {
        when(statement.executeQuery(anyString())).thenThrow(new SQLTimeoutException("PRIVATE_DB_CANARY"));
        doThrow(new IllegalStateException("PRIVATE_POOL_CANARY")).when(evict).accept(connection);
        unavailable(() -> new ExecutionCoordinatorLease(connection, evict));
        verify(connection).abort(any()); verify(connection, never()).close();
    }

    @Test void contentionAndTransactionalConnectionsCannotBecomeOwners() throws Exception {
        when(rows.getBoolean(2)).thenReturn(false);
        unavailable(() -> new ExecutionCoordinatorLease(connection, evict));
        verify(connection).abort(any()); verify(connection).close();
        reset(connection); when(connection.getAutoCommit()).thenReturn(false);
        unavailable(() -> new ExecutionCoordinatorLease(connection, evict));
        verify(connection, never()).createStatement(); verify(connection).abort(any());
    }

    @Test void ambientTransactionIsRejectedBeforeBorrowingAConnection() throws Exception {
        var database = mock(DataSource.class);
        boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            unavailable(() -> new ExecutionCoordinatorLease(database));
            verifyNoInteractions(database);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(previous); }
    }

    @Test void connectionBorrowFailureDoesNotExposeCredentials() throws Exception {
        var database = mock(com.zaxxer.hikari.HikariDataSource.class);
        when(database.unwrap(com.zaxxer.hikari.HikariDataSource.class)).thenReturn(database);
        when(database.getConnection()).thenThrow(new SQLException("PRIVATE_DB_CANARY"));
        unavailable(() -> new ExecutionCoordinatorLease(database));
        verify(database).getConnection();
    }

    @Test void unsupportedPoolIsRejectedBeforeBorrowing() throws Exception {
        var database = mock(DataSource.class);
        unavailable(() -> new ExecutionCoordinatorLease(database));
        verify(database, never()).getConnection();
    }

    @Test void changedBackendPermanentlyInvalidatesTheLease() throws Exception {
        var lease = new ExecutionCoordinatorLease(connection, evict);
        when(rows.getInt(1)).thenReturn(43);
        assertThat(lease.healthy()).isFalse();
        when(rows.getInt(1)).thenReturn(42);
        assertThat(lease.healthy()).isFalse();
        lease.close(); verify(connection).abort(any()); verify(connection).close();
    }

    @Test void failedHealthCheckStaysLostAndDisposalCanRetryWithoutReleasingToPool() throws Exception {
        var lease = new ExecutionCoordinatorLease(connection, evict);
        when(statement.executeQuery(anyString())).thenThrow(new SQLTimeoutException("PRIVATE_DB_CANARY"));
        assertThat(lease.healthy()).isFalse();
        doThrow(new SQLException("PRIVATE_ABORT_CANARY")).doNothing().when(connection).abort(any());
        unavailable(lease::close); verify(connection, never()).close();
        assertThat(lease.healthy()).isFalse();
        lease.close(); lease.close();
        verify(connection, times(2)).abort(any()); verify(connection).close();
    }

    @Test void failedUnlockDiscardsBeforeCloseAndUsesAFixedError() throws Exception {
        var lease = new ExecutionCoordinatorLease(connection, evict);
        when(statement.executeQuery(anyString())).thenThrow(new SQLTimeoutException("PRIVATE_UNLOCK_CANARY"));
        unavailable(lease::close);
        var order = inOrder(connection, evict);
        order.verify(connection).abort(any()); order.verify(evict).accept(connection); order.verify(connection).close();
        lease.close(); verify(connection).close();
    }

    @Test void normalCloseUnlocksOnceAndDoesNotAbortTheConnection() throws Exception {
        var lease = new ExecutionCoordinatorLease(connection, evict);
        assertThat(lease.healthy()).isTrue(); lease.close(); lease.close();
        assertThat(lease.healthy()).isFalse();
        verify(statement).executeQuery(contains("pg_advisory_unlock(718395204)"));
        verify(connection).close(); verify(connection, never()).abort(any());
        verifyNoInteractions(evict);
    }

    private void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(SQLException.class, error -> {
            assertThat(error.getMessage()).isEqualTo("EXECUTION_COORDINATOR_UNAVAILABLE");
            assertThat(error.getCause()).isNull(); assertThat(error.getSuppressed()).isEmpty();
        });
    }
}
