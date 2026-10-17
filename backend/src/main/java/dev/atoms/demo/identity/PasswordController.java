package dev.atoms.demo.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiError;
import dev.atoms.demo.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
final class PasswordController {
    private final PasswordService passwords;
    private final PasswordChallenges challenges;
    private final AccountSessions sessions;
    private final GuestIdentityService identities;
    private final EmailAuthSettings settings;
    private final ObjectMapper json;
    PasswordController(PasswordService passwords,PasswordChallenges challenges,AccountSessions sessions,
                       GuestIdentityService identities,EmailAuthSettings settings,ObjectMapper json) {
        this.passwords=passwords; this.challenges=challenges; this.sessions=sessions; this.identities=identities; this.settings=settings; this.json=json;
    }
    @GetMapping("/api/account/password")
    PasswordService.Status status(Authentication auth,HttpServletRequest request) {
        return passwords.status(UUID.fromString(auth.getName()),request.getSession().getId(),SessionAccess.version(auth));
    }
    @PostMapping(value="/api/auth/password/verifications",consumes="application/json")
    GuestIdentityService.Identity login(Authentication auth,HttpServletRequest request,HttpServletResponse response) {
        requireEnabled(request);
        var body=AuthRequest.fields(AuthRequest.read(request,json),Set.of("email","password"));
        long started=System.nanoTime();
        try {
            UUID owner=UUID.fromString(auth.getName());
            var result=passwords.login(owner,request.getSession().getId(),request.getRemoteAddr(),body.path("email").asText(),body.path("password").asText());
            sessions.authenticate(owner,result,request,response);
            EmailAuthController.event(request,"password_login","authenticated",started);
            return identities.get(result.userId().toString());
        } catch(ApiException failure) { EmailAuthController.event(request,"password_login",failure.code(),started); throw failure; }
    }
    @PostMapping(value="/api/account/password/challenges",consumes="application/json")
    ResponseEntity<EmailAuthService.Challenge> send(Authentication auth,HttpServletRequest request) {
        requireEnabled(request); AuthRequest.fields(AuthRequest.read(request,json),Set.of()); long started=System.nanoTime();
        try {
            var result=challenges.send(UUID.fromString(auth.getName()),request.getSession().getId(),request.getRemoteAddr(),SessionAccess.version(auth));
            EmailAuthController.event(request,"password_setup_send","accepted",started);
            return ResponseEntity.status(201).body(result);
        } catch(ApiException failure) { EmailAuthController.event(request,"password_setup_send",failure.code(),started); throw failure; }
    }
    @PutMapping(value="/api/account/password",consumes="application/json")
    PasswordService.Status update(Authentication auth,HttpServletRequest request,HttpServletResponse response) {
        requireEnabled(request); var body=AuthRequest.read(request,json); String mode=body.path("mode").asText();
        if(!Set.of("setup","change").contains(mode)) throw AuthRequest.invalid();
        AuthRequest.fields(body,mode.equals("setup")?Set.of("mode","challengeId","code","newPassword"):Set.of("mode","currentPassword","newPassword"));
        long started=System.nanoTime();
        try {
            UUID owner=UUID.fromString(auth.getName()); String session=request.getSession().getId(),ip=request.getRemoteAddr(); long version=SessionAccess.version(auth);
            VerifiedAccount result=mode.equals("setup")
                    ?passwords.setup(owner,session,ip,version,AuthRequest.uuid(body.path("challengeId").asText()),body.path("code").asText(),body.path("newPassword").asText())
                    :passwords.change(owner,session,ip,version,body.path("currentPassword").asText(),body.path("newPassword").asText());
            sessions.authenticate(owner,result,request,response);
            EmailAuthController.event(request,"password_update","completed",started);
            return passwords.status(owner,request.getSession().getId(),result.version());
        } catch(ApiException failure) { EmailAuthController.event(request,"password_update",failure.code(),started); throw failure; }
    }
    private void requireEnabled(HttpServletRequest request) {
        if(!settings.enabled()) throw new ApiException(503,"PASSWORD_AUTH_UNAVAILABLE","账号登录尚未配置。");
        if(!EmailAuthController.allowsPasswordTransport(request,settings)) throw new ApiException(503,"PASSWORD_SECURE_TRANSPORT_REQUIRED","公网密码登录与设置需要 HTTPS；当前仍可使用已开放的验证码登录。");
    }
    @ExceptionHandler(AuthRateLimited.class)
    ResponseEntity<ApiError> limited(AuthRateLimited failure,HttpServletRequest request) {
        return ResponseEntity.status(429).header("Retry-After",Long.toString(failure.retryAfter())).body(ApiError.of(request,failure.code(),failure.getMessage(),false));
    }
}
