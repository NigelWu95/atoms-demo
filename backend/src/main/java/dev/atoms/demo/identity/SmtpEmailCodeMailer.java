package dev.atoms.demo.identity;

import dev.atoms.demo.common.ApiException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
public final class SmtpEmailCodeMailer implements EmailCodeMailer {
    private final EmailAuthSettings settings;
    private final JavaMailSender sender;
    private final Semaphore concurrent = new Semaphore(2);
    @Autowired public SmtpEmailCodeMailer(EmailAuthSettings settings) { this(settings, configure(settings)); }
    SmtpEmailCodeMailer(EmailAuthSettings settings, JavaMailSender sender) { this.settings = settings; this.sender = sender; }
    // https://docs.spring.io/spring-boot/3.5/reference/io/email.html
    // https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html
    static JavaMailSenderImpl configure(EmailAuthSettings settings) {
        var sender = new JavaMailSenderImpl();
        sender.setHost(settings.host()); sender.setPort(settings.port());
        sender.setUsername(settings.username()); sender.setPassword(settings.password());
        sender.setDefaultEncoding("UTF-8");
        var p = sender.getJavaMailProperties();
        p.setProperty("mail.smtp.auth", "true");
        p.setProperty("mail.smtp.ssl.enable", "true");
        p.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        p.setProperty("mail.smtp.connectiontimeout", "5000");
        p.setProperty("mail.smtp.timeout", "5000");
        p.setProperty("mail.smtp.writetimeout", "5000");
        p.setProperty("mail.debug", "false");
        return sender;
    }
    @Override public void send(String recipient, String code) {
        if (!settings.enabled()) throw new ApiException(503, "EMAIL_AUTH_UNAVAILABLE", "邮箱登录尚未配置，请继续使用访客体验。");
        recipient = EmailCode.email(recipient);
        if (code == null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("Invalid verification code");
        if (!concurrent.tryAcquire()) throw unavailable();
        try {
            var message = new SimpleMailMessage();
            message.setFrom(settings.username()); message.setTo(recipient);
            message.setSubject("Atoms Demo 账号验证码");
            message.setText("你的 Atoms Demo 验证码是：" + code + "\n\n验证码 10 分钟内有效，仅可使用一次。"
                    + "\n请返回发起请求的页面，完成对应的注册、登录或首次设置密码操作。验证码仅限本次请求使用，不能混用。"
                    + "\n请勿向他人透露验证码。如果不是你本人操作，请忽略此邮件。");
            sender.send(message);
        } catch (org.springframework.mail.MailException failure) {
            // Upstream exceptions may contain recipient, SMTP credentials or message text.
            throw unavailable();
        } finally { concurrent.release(); }
    }
    private static ApiException unavailable() {
        return new ApiException(503, "EMAIL_DELIVERY_UNCONFIRMED", "邮件发送未确认，请稍后重新获取验证码。");
    }
}
