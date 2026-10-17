package dev.atoms.demo.identity;

import dev.atoms.demo.common.ApiException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
final class PasswordService {
    private final AuthTransactions tx;
    private final PasswordCredentials credentials;
    private final PasswordChallenges challenges;
    private final PasswordPolicy policy;
    private final AuthBudgetStore budgets;
    private final SessionRevocations revocations;
    PasswordService(AuthTransactions tx,PasswordCredentials credentials,PasswordChallenges challenges,PasswordPolicy policy,
                    AuthBudgetStore budgets,SessionRevocations revocations) {
        this.tx=tx; this.credentials=credentials; this.challenges=challenges; this.policy=policy; this.budgets=budgets; this.revocations=revocations;
    }
    public record Status(boolean configured, Instant updatedAt) {}
    Status status(UUID owner,String session,long version) {
        var account=credentials.requireOwner(owner,session,version);
        return new Status(account.hash!=null,account.updatedAt);
    }
    VerifiedAccount login(UUID guest,String session,String ip,String rawEmail,String password) {
        String email=EmailCode.email(rawEmail);
        PasswordPolicy.validate(password);
        var original=tx.run(()->{
            requireGuest(guest,session);
            Instant now=Instant.now();
            budgets.take("password-login-email",email,900,10,now);
            budgets.take("password-login-session",session,900,10,now);
            budgets.take("password-login-ip",ip,900,50,now);
            budgets.take("password-global","all",900,100,now);
            return credentials.findEmail(email);
        });
        return policy.bounded(()->{
            boolean matches=policy.matches(password,original==null?null:original.hash);
            if(original==null || original.hash==null || !matches) throw invalid();
            return tx.run(()->{
                requireGuest(guest,session);
                var current=credentials.findOwner(original.owner);
                if(current==null || !same(original,current)) throw invalid();
                revocations.revoke(session);
                return new VerifiedAccount(current.owner,current.version);
            });
        });
    }
    VerifiedAccount setup(UUID owner,String session,String ip,long version,UUID challenge,String code,String password) {
        PasswordPolicy.validate(password);
        if(code==null || !code.matches("[0-9]{6}")) throw new ApiException(422,"INVALID_INPUT","请输入六位数字验证码。");
        var original=reserveManagement(owner,session,ip,version);
        PasswordChallenges.requireUnset(original);
        boolean valid=tx.run(()->{
            var current=credentials.requireOwner(owner,session,version);
            PasswordChallenges.requireUnset(current);
            return challenges.valid(current,session,challenge,code,true);
        });
        if(!valid) throw invalidCode();
        return policy.bounded(()->{
            String hash=policy.encode(password);
            return tx.run(()->{
                var current=credentials.requireOwner(owner,session,version);
                PasswordChallenges.requireUnset(current);
                if(!same(original,current) || !challenges.valid(current,session,challenge,code,false)) throw invalidCode();
                challenges.consume(challenge);
                return credentials.save(current,hash,session);
            });
        });
    }
    VerifiedAccount change(UUID owner,String session,String ip,long version,String previous,String password) {
        PasswordPolicy.validate(previous); PasswordPolicy.validate(password);
        var original=reserveManagement(owner,session,ip,version);
        if(original.hash==null) throw new ApiException(409,"PASSWORD_NOT_CONFIGURED","请先验证邮箱并设置密码。");
        return policy.bounded(()->{
            if(!policy.matches(previous,original.hash)) throw invalid();
            String hash=policy.encode(password);
            return tx.run(()->{
                var current=credentials.requireOwner(owner,session,version);
                if(!same(original,current)) throw new ApiException(409,"PASSWORD_CHANGED","密码已被更新，请重新登录后再操作。");
                return credentials.save(current,hash,session);
            });
        });
    }
    private PasswordCredentials.Snapshot reserveManagement(UUID owner,String session,String ip,long version) {
        return tx.run(()->{
            var account=credentials.requireOwner(owner,session,version); Instant now=Instant.now();
            budgets.take("password-manage-owner",owner.toString(),900,10,now);
            budgets.take("password-manage-session",session,900,10,now);
            budgets.take("password-manage-ip",ip,900,50,now);
            budgets.take("password-global","all",900,100,now);
            return account;
        });
    }
    private void requireGuest(UUID guest,String session) {
        credentials.requireSession(session);
        if(credentials.findOwner(guest)!=null) throw new ApiException(409,"ACCOUNT_ALREADY_SIGNED_IN","请先退出当前账号。");
    }
    private static boolean same(PasswordCredentials.Snapshot first,PasswordCredentials.Snapshot second) {
        return first.version==second.version && Objects.equals(first.hash,second.hash);
    }
    private static ApiException invalid() { return new ApiException(422,"PASSWORD_CREDENTIALS_INVALID","邮箱或密码不正确，请重试或使用验证码登录。"); }
    private static ApiException invalidCode() { return new ApiException(422,"EMAIL_CODE_INVALID","验证码无效、已过期或已使用，请重新获取。"); }
}
