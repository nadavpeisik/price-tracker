package com.np.pricehunt.bff.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.bff.testsupport.BffIntegrationTest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Pins the premise the proxy's malformed-target guard rests on: which bad request targets the container
 * filters for us, and which it hands straight to the application. Twice now a reviewer has proposed
 * deleting that guard as unreachable because "Tomcat rejects malformed URLs first". It rejects some of
 * them. A half-finished percent-escape is not one, and this is the test that says so out loud.
 *
 * <p>Needs a real container, because MockMvc never runs the HTTP parser. The request line is written to a
 * socket by hand: an HTTP client would reject these targets itself before sending. Anonymous is enough,
 * since the question is only whether the request reaches the security chain at all. The management port
 * property matches the other real-server suite so both share one cached context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "management.server.port=0")
class MalformedTargetTest extends BffIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void percentEscapesReachTheApplication_bracketsDoNot() throws Exception {
        // 401 means the chain answered, so the request got past the parser and would reach the proxy.
        assertThat(statusCodeOf("/bff/api/x?q=100%")).isEqualTo(401);
        assertThat(statusCodeOf("/bff/api/x?q=%zz")).isEqualTo(401);
        assertThat(statusCodeOf("/bff/api/x?q=a%2")).isEqualTo(401);
        assertThat(statusCodeOf("/bff/api/x?q=ok")).isEqualTo(401);

        // These the container really does reject on our behalf.
        assertThat(statusCodeOf("/bff/api/x?q=a[b]")).isEqualTo(400);
    }

    private int statusCodeOf(String requestTarget) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            // Without this a container that never answers hangs the build rather than failing it.
            socket.setSoTimeout((int) Duration.ofSeconds(10).toMillis());
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + requestTarget + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = in.readLine();
            assertThat(statusLine).as("status line for %s", requestTarget).isNotNull();
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}
