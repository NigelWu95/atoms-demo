package dev.atoms.demo.identity;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(properties = "EMAIL_CODE_HMAC_KEY=abababababababababababababababababababababababababababababababab")
class AuthBudgetIT {
    @Autowired AuthTransactions transactions;
    @Autowired AuthBudgetStore budgets;
    @Test void cooldownIsAnElapsedMinuteAndCountersPersistAcrossCalls() {
        String key = UUID.randomUUID().toString();
        Instant time = Instant.parse("2026-10-16T00:00:59Z");
        transactions.run(() -> { budgets.take("cooldown", key, 0, 1, time); return null; });
        assertThatThrownBy(() -> transactions.run(() -> { budgets.take("cooldown", key, 0, 1, time.plusSeconds(1)); return null; }))
                .isInstanceOf(AuthRateLimited.class);
        transactions.run(() -> { budgets.take("cooldown", key, 0, 1, time.plusSeconds(60)); return null; });
        transactions.run(() -> { budgets.take("fixed", key, 900, 2, time); budgets.take("fixed", key, 900, 2, time); return null; });
        assertThatThrownBy(() -> transactions.run(() -> { budgets.take("fixed", key, 900, 2, time); return null; }))
                .isInstanceOf(AuthRateLimited.class);
        transactions.run(() -> { budgets.take("fixed", key, 900, 2, time.plusSeconds(900)); return null; });
    }
}
