package dev.atoms.demo.identity;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest
class AccountIdentityIT {
    @Autowired GuestIdentityService guests;
    @Autowired AccountService accounts;
    @Autowired PlatformTransactionManager manager;
    @Test void upgradesStableGuestAndResolvesExistingEmailWithoutMergingAnotherGuest() {
        var first = UUID.fromString(guests.create().userId());
        var other = UUID.fromString(guests.create().userId());
        String email = first + "@example.com";
        var tx = new TransactionTemplate(manager);
        assertThat(tx.<UUID>execute(status -> accounts.claim(first, email))).isEqualTo(first);
        assertThat(guests.get(first.toString()).isGuest()).isFalse();
        assertThat(tx.<UUID>execute(status -> accounts.claim(other, email))).isEqualTo(first);
        assertThat(guests.get(other.toString()).isGuest()).isTrue();
        assertThatThrownBy(() -> tx.execute(status -> accounts.claim(first, other + "@example.com")))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class);
    }
}
