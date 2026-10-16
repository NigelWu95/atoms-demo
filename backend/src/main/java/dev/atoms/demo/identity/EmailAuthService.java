package dev.atoms.demo.identity;

import dev.atoms.demo.common.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EmailAuthService {
    private final EmailAuthSettings settings;
    private final EmailCodeMailer mailer;
    private final EmailChallengeStore challenges;
    public EmailAuthService(EmailAuthSettings settings, EmailCodeMailer mailer, EmailChallengeStore challenges) {
        this.settings = settings; this.mailer = mailer; this.challenges = challenges;
    }
    public record Challenge(UUID challengeId, Instant expiresAt, int resendAfterSeconds) {}
    public Challenge send(UUID owner, String session, String ip, String rawEmail) {
        requireEnabled();
        String email = EmailCode.email(rawEmail), code = EmailCode.generate();
        Challenge challenge = challenges.reserve(owner, session, ip, email, code);
        try {
            mailer.send(email, code);
            challenges.activate(owner, session, challenge.challengeId());
            return challenge;
        } catch (RuntimeException failure) {
            challenges.failed(challenge.challengeId());
            throw failure;
        }
    }
    public UUID verify(UUID owner, String session, String ip, UUID challenge, String code) {
        requireEnabled();
        if (code == null || !code.matches("[0-9]{6}")) throw new ApiException(422, "INVALID_INPUT", "请输入六位数字验证码。");
        return challenges.verify(owner, session, ip, challenge, code);
    }
    private void requireEnabled() {
        if (!settings.enabled()) throw new ApiException(503, "EMAIL_AUTH_UNAVAILABLE", "邮箱登录尚未配置，请继续使用访客体验。");
    }
}
