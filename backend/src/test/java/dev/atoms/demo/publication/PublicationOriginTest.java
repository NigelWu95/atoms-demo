package dev.atoms.demo.publication;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PublicationOriginTest {
    @Test void requiresExplicitOptInForAnHttpDemoWithoutRelaxingOriginValidation() {
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("PUBLIC_APP_ORIGIN", "http://198.51.100.1:8443")
                .withProperty("ALLOW_INSECURE_HTTP_PUBLIC_ORIGIN", "true");
        assertThatCode(() -> new PublicationService(null, null, null, null, null, env)).doesNotThrowAnyException();
        env.withProperty("ALLOW_INSECURE_HTTP_PUBLIC_ORIGIN", "false");
        assertThatThrownBy(() -> new PublicationService(null, null, null, null, null, env))
                .isInstanceOf(IllegalStateException.class);
        env.withProperty("ALLOW_INSECURE_HTTP_PUBLIC_ORIGIN", "true");
        for (String invalid : new String[]{"ftp://example.test", "http://user:secret@example.test", "http://example.test/path",
                "http://example.test?query=1", "http://example.test#fragment", "http://example.test:99999"}) {
            env.withProperty("PUBLIC_APP_ORIGIN", invalid);
            assertThatThrownBy(() -> new PublicationService(null, null, null, null, null, env))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(invalid);
        }
    }
    @Test void permitsOnlyConfiguredOriginsAndNeverTreatsHostHeadersAsConfiguration() {
        assertThat(PublicationService.publicOrigin("")).isEmpty();
        assertThat(PublicationService.publicOrigin("https://apps.example.test/")).isEqualTo("https://apps.example.test");
        assertThat(PublicationService.publicOrigin("http://127.0.0.1:8090")).isEqualTo("http://127.0.0.1:8090");
        for (String value : new String[]{"http://external.example", "javascript:alert(1)", "https://user:secret@example.test", "https://example.test/a",
                "https://example.test?x=1", "https://example.test#x", "https://example.test:99999", "https://example.test:0"})
            assertThatThrownBy(() -> PublicationService.publicOrigin(value)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(value);
    }
}
