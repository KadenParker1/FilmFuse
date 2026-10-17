package com.pm.filmfuse.common.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Regression: over plain http the JDK client's default HTTP/2 sends {@code Upgrade: h2c}, which
 * uvicorn rejects while dropping the body (every ML call came back 422). MockRestServiceServer
 * bypasses the real request factory, so this test talks to a real socket.
 */
class RestClientsTest {

    @Test
    void clientIsPinnedToHttp11() {
        assertThat(RestClients.newHttpClient(Duration.ofSeconds(1)).version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }

    @Test
    void postsReachAPlainHttpServerWithBodyAndWithoutAnUpgradeHeader() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> upgrade = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            String response = RestClients.withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2))
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build()
                    .post().uri("/echo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"userId\":\"x\"}")
                    .retrieve()
                    .body(String.class);

            assertThat(response).isEqualTo("{\"ok\":true}");
            assertThat(body.get()).isEqualTo("{\"userId\":\"x\"}");
            assertThat(upgrade.get()).isNull();
        } finally {
            server.stop(0);
        }
    }
}
