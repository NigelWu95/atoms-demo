package dev.atoms.demo.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class EmailAuthSettingsTest {
    @Test void defaultsOffAndValidatesEnabledSecretsWithoutEchoingThem() {
        assertThat(new EmailAuthSettings(new MockEnvironment()).enabled()).isFalse();
        var env = new MockEnvironment().withProperty("EMAIL_AUTH_ENABLED", "true")
                .withProperty("SMTP_USERNAME", "owner@qq.com").withProperty("SMTP_PASSWORD", "secret-canary");
        assertThatThrownBy(() -> new EmailAuthSettings(env)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMAIL_CODE_HMAC_KEY").hasMessageNotContaining("secret-canary");
        env.withProperty("EMAIL_CODE_HMAC_KEY", "ab".repeat(32));
        var settings = new EmailAuthSettings(env);
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.host()).isEqualTo("smtp.qq.com");
        assertThat(settings.port()).isEqualTo(465);
    }
    @Test void acceptsOnlyOneConventionalAsciiMailboxWithoutAliasMerging() {
        assertThat(EmailCode.email("  Ab.C+test@Example.COM ")).isEqualTo("ab.c+test@example.com");
        for (String bad : new String[]{"", "x@y", "a@b.com\r\nBcc:other@example.com", "A <a@b.com>",
                "a@b.com,b@b.com", "a..b@b.com", ".a@b.com", "a@[127.0.0.1]", "用户@b.com", "a".repeat(65)+"@b.com"}) {
            assertThatThrownBy(() -> EmailCode.email(bad)).isInstanceOf(dev.atoms.demo.common.ApiException.class);
        }
    }
    @Test void codesAreSixDigitsAndDigestsBindAllContext() {
        var key = "cd".repeat(32);
        for (int i = 0; i < 100; i++) assertThat(EmailCode.generate()).matches("[0-9]{6}");
        var digest = EmailCode.digest(key, "challenge", "a@example.com", "000012");
        assertThat(digest).hasSize(64).doesNotContain("000012");
        assertThat(EmailCode.matches(digest, EmailCode.digest(key, "challenge", "a@example.com", "000012"))).isTrue();
        assertThat(EmailCode.matches(digest, EmailCode.digest(key, "other", "a@example.com", "000012"))).isFalse();
        assertThat(EmailCode.matches(digest, EmailCode.digest(key, "challenge", "b@example.com", "000012"))).isFalse();
    }
}
