package dev.atoms.demo.identity;

import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
final class AuthTransactions {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    AuthTransactions(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; tx = new TransactionTemplate(manager); tx.setTimeout(5);
    }
    <T> T run(Supplier<T> action) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '3s'");
            jdbc.execute("SET LOCAL statement_timeout = '5s'");
            // Low-volume Demo: serialize short auth decisions, never SMTP or arbitrary work.
            // https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS
            jdbc.execute("SELECT pg_advisory_xact_lock(1635100001)");
            return action.get();
        });
    }
}
