package dev.atoms.demo.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class EmailTransportSecurityTest {
    @Test void publicPlaintextCannotPretendToBeLocalUsingHostOrForwardedHeaders() {
        var settings = new EmailAuthSettings(new MockEnvironment());
        var request = new MockHttpServletRequest();
        request.setServerName("127.0.0.1"); request.setRemoteAddr("198.51.100.10");
        request.addHeader("X-Forwarded-Proto", "https"); request.addHeader("X-Forwarded-For", "127.0.0.1");
        assertThat(EmailAuthController.allowsTransport(request, settings)).isFalse();
        request.setRemoteAddr("127.0.0.1"); request.setServerName("public.example.com");
        assertThat(EmailAuthController.allowsTransport(request, settings)).isFalse();
        request.setServerName("localhost"); assertThat(EmailAuthController.allowsTransport(request, settings)).isTrue();
        request.setServerName("public.example.com"); request.setSecure(true);
        assertThat(EmailAuthController.allowsTransport(request, settings)).isTrue();
    }
    @Test void insecureTransportRequiresExplicitOptInAndDoesNotEnableEmailSending() {
        var request = new MockHttpServletRequest();
        request.setServerName("198.51.100.20"); request.setRemoteAddr("198.51.100.10");
        for (String flag : new String[]{"false", "", "invalid", "true"}) {
            var settings = new EmailAuthSettings(new MockEnvironment().withProperty("EMAIL_AUTH_ALLOW_INSECURE_HTTP", flag));
            assertThat(EmailAuthController.allowsTransport(request, settings)).isEqualTo(flag.equals("true"));
            assertThat(new EmailAuthController(settings, null, null, null, null).methods(request))
                    .containsEntry("emailCodeEnabled", false);
        }
    }
    @Test void defaultPolicyBlocksPublicSendAndVerificationEvenWithSmtpConfigured() {
        var settings = new EmailAuthSettings(new MockEnvironment().withProperty("EMAIL_AUTH_ENABLED", "true")
                .withProperty("SMTP_USERNAME", "fixture@qq.com").withProperty("SMTP_PASSWORD", "test-only")
                .withProperty("EMAIL_CODE_HMAC_KEY", "ab".repeat(32))
                .withProperty("ALLOW_INSECURE_HTTP_PUBLIC_ORIGIN", "true"));
        var request = new MockHttpServletRequest();
        request.setServerName("198.51.100.20"); request.setRemoteAddr("198.51.100.10");
        request.addHeader("EMAIL_AUTH_ALLOW_INSECURE_HTTP", "true");
        var controller = new EmailAuthController(settings, null, null, null, null);
        assertThat(controller.methods(request)).containsEntry("emailCodeEnabled", false);
        assertThatThrownBy(() -> controller.send(null, request))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> controller.verify(null, request, null))
                .isInstanceOf(dev.atoms.demo.common.ApiException.class)
                .hasMessageContaining("HTTPS");
    }
    @Test void defaultPolicyRejectsPublicAccountSessionsEvenWhenSendingIsDisabled() throws Exception {
        var request = new MockHttpServletRequest();
        request.setServerName("198.51.100.20"); request.setRemoteAddr("198.51.100.10");
        var session = request.getSession();
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                java.util.UUID.randomUUID().toString(), null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        var access = org.mockito.Mockito.mock(SessionAccess.class);
        org.mockito.Mockito.when(access.valid(session.getId(), auth)).thenReturn(true);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var chain = new org.springframework.mock.web.MockFilterChain();
        try {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
            new SessionAccessFilter(access, new com.fasterxml.jackson.databind.ObjectMapper(),
                    new EmailAuthSettings(new MockEnvironment())).doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
            assertThat(request.getSession(false)).isNull();
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
}
