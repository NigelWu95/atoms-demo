package dev.atoms.demo.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class EmailTransportSecurityTest {
    @Test void publicPlaintextCannotPretendToBeLocalUsingHostOrForwardedHeaders() {
        var request = new MockHttpServletRequest();
        request.setServerName("127.0.0.1"); request.setRemoteAddr("198.51.100.10");
        request.addHeader("X-Forwarded-Proto", "https"); request.addHeader("X-Forwarded-For", "127.0.0.1");
        assertThat(EmailAuthController.secure(request)).isFalse();
        request.setRemoteAddr("127.0.0.1"); request.setServerName("public.example.com");
        assertThat(EmailAuthController.secure(request)).isFalse();
        request.setServerName("localhost"); assertThat(EmailAuthController.secure(request)).isTrue();
        request.setServerName("public.example.com"); request.setSecure(true);
        assertThat(EmailAuthController.secure(request)).isTrue();
    }
}
