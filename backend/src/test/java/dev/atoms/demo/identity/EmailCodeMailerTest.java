package dev.atoms.demo.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import static org.assertj.core.api.Assertions.*;

class EmailCodeMailerTest {
    static EmailAuthSettings settings() {
        return new EmailAuthSettings(new MockEnvironment().withProperty("EMAIL_AUTH_ENABLED", "true")
                .withProperty("SMTP_USERNAME", "owner@qq.com").withProperty("SMTP_PASSWORD", "smtp-secret-canary")
                .withProperty("EMAIL_CODE_HMAC_KEY", "ab".repeat(32)));
    }
    @Test void usesVerifiedTlsWithFiniteTimeoutsAndFixedSingleRecipientMessage() {
        var configured = SmtpEmailCodeMailer.configure(settings());
        assertThat(configured.getHost()).isEqualTo("smtp.qq.com");
        assertThat(configured.getPort()).isEqualTo(465);
        var p = configured.getJavaMailProperties();
        assertThat(p.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
        assertThat(p.getProperty("mail.smtp.timeout")).isEqualTo("5000");
        assertThat(p.getProperty("mail.smtp.writetimeout")).isEqualTo("5000");
        assertThat(p).doesNotContainKey("mail.smtp.ssl.trust");
        var capture = new JavaMailSenderImpl() {
            @Override public void send(SimpleMailMessage message) {
                assertThat(message.getTo()).containsExactly("reader@example.com");
                assertThat(message.getFrom()).isEqualTo("owner@qq.com");
                assertThat(message.getSubject()).isEqualTo("Atoms Demo 账号验证码");
                assertThat(message.getText()).contains("首次设置密码", "发起请求的页面").doesNotContain("首次验证将注册账号");
                assertThat(message.getText()).contains("000012", "10 分钟").doesNotContain("smtp-secret-canary");
                assertThat(message.getCc()).isNull();
                assertThat(message.getBcc()).isNull();
            }
        };
        new SmtpEmailCodeMailer(settings(), capture).send("reader@example.com", "000012");
    }
    @Test void neverLeaksProviderErrorsOrFallsBackToFakeSuccess() {
        var failed = new JavaMailSenderImpl() {
            @Override public void send(SimpleMailMessage message) { throw new MailSendException("smtp-secret-canary"); }
        };
        assertThatThrownBy(() -> new SmtpEmailCodeMailer(settings(), failed).send("reader@example.com", "123456"))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class).hasMessageNotContaining("smtp-secret-canary");
    }
}
