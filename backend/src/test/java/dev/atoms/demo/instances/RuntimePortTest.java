package dev.atoms.demo.instances;

import java.net.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimePortTest {
    @Test void activeListenerIsRejectedButTimeWaitDoesNotPreventRestart() throws Exception {
        int port;
        try(var server=new ServerSocket()) {
            server.setReuseAddress(true);server.bind(new InetSocketAddress("127.0.0.1",0));port=server.getLocalPort();
            assertThatThrownBy(()->RuntimeProcess.checkPort(server.getLocalPort())).isInstanceOf(java.io.IOException.class);
            try(var client=new Socket("127.0.0.1",port)) {
                server.accept().close(); // Server-side active close leaves TIME_WAIT, not a listener.
                assertThat(client.getInputStream().read()).isEqualTo(-1);
            }
        }
        int released=port;
        assertThatCode(()->RuntimeProcess.checkPort(released)).doesNotThrowAnyException();
    }
}
