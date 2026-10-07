package dev.atoms.demo.publication;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PublicationOriginTest {
    @Test void permitsOnlyConfiguredOriginsAndNeverTreatsHostHeadersAsConfiguration() {
        assertThat(PublicationService.publicOrigin("")).isEmpty();
        assertThat(PublicationService.publicOrigin("https://apps.example.test/")).isEqualTo("https://apps.example.test");
        assertThat(PublicationService.publicOrigin("http://127.0.0.1:8090")).isEqualTo("http://127.0.0.1:8090");
        for (String value : new String[]{"http://external.example", "javascript:alert(1)", "https://user:secret@example.test", "https://example.test/a",
                "https://example.test?x=1", "https://example.test#x", "https://example.test:99999", "https://example.test:0"})
            assertThatThrownBy(() -> PublicationService.publicOrigin(value)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(value);
    }
}
