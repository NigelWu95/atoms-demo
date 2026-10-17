package dev.atoms.demo.identity;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionAccessTest {
    @Test void acceptsLegacyVersionZeroButNeverPromotesOldOrRestoredContextsAfterPasswordChange() {
        var revocations=mock(SessionRevocations.class);
        var accounts=mock(AccountService.class);
        var access=new SessionAccess(revocations,accounts,mock(JdbcIndexedSessionRepository.class));
        UUID owner=UUID.randomUUID();
        when(accounts.exists(owner)).thenReturn(true);
        var auth=UsernamePasswordAuthenticationToken.authenticated(owner.toString(),null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThat(access.valid("legacy",auth)).isTrue();
        when(accounts.version(owner)).thenReturn(1L);
        // A late save of the old serialized context cannot acquire the current version.
        assertThat(access.valid("restored-legacy",auth)).isFalse();
        auth.setDetails(1L);
        assertThat(access.valid("current",auth)).isTrue();
        when(accounts.version(owner)).thenReturn(2L);
        assertThat(access.valid("restored-stale",auth)).isFalse();
        auth.setDetails(2L);
        assertThat(access.valid("current",auth)).isTrue();
        auth.setDetails("2");
        assertThat(access.valid("unknown-format",auth)).isFalse();
        auth.setDetails(2L);
        when(revocations.revoked("revoked")).thenReturn(true);
        assertThat(access.valid("revoked",auth)).isFalse();
        var guest=UsernamePasswordAuthenticationToken.authenticated(owner.toString(),null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
        guest.setDetails(2L);
        assertThat(access.valid("guest",guest)).isFalse();
    }
}
