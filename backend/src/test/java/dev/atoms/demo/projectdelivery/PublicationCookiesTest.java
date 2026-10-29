package dev.atoms.demo.projectdelivery;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PublicationCookiesTest {
    @Test void isolatesPlatformAndOtherApplicationsAndNeverAllowsDomainOrReadableCookies() {
        var own = new PublicationCookies(UUID.randomUUID()); var other = new PublicationCookies(UUID.randomUUID());
        String emitted = own.outgoing(List.of("session=own-token; Domain=example.com; Path=/api; SameSite=None")).get(0);
        assertThat(emitted).endsWith("; Path=/; HttpOnly; SameSite=Strict").doesNotContain("Domain", "SameSite=None");
        String ownPair = emitted.split(";",2)[0], otherPair = other.outgoing(List.of("session=other-token")).get(0).split(";",2)[0];
        assertThat(own.incoming(List.of("ATOMS_HTTP_SESSION=PRIVATE_CANARY; "+ownPair+"; "+otherPair))).isEqualTo("session=own-token");
        assertThat(other.incoming(List.of(ownPair))).isNull();
        assertThat(own.outgoing(List.of("ATOMS_HTTP_SESSION=forged")).get(0)).startsWith("ATOMS_APP_").doesNotStartWith("ATOMS_HTTP_SESSION=");
    }
    @Test void keepsDeletionAndRejectsHeaderInjectionDuplicatesAndOversize() {
        var cookies = new PublicationCookies(UUID.randomUUID());
        String pair = cookies.outgoing(List.of("session=; Max-Age=0")).get(0);
        assertThat(pair).contains("Max-Age=0");
        assertThatThrownBy(() -> cookies.outgoing(List.of("session=x\r\nInjected: yes"))).hasMessageNotContaining("Injected");
        assertThatThrownBy(() -> cookies.incoming(List.of("x".repeat(16385)))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> cookies.incoming(List.of(pair.split(";",2)[0]+"; "+pair.split(";",2)[0]))).isInstanceOf(RuntimeException.class);
    }
}
