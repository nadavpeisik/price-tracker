package com.np.pricehunt.bff.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Stands in for the backend behind the proxy: records every request it receives (method, path and
 * raw query, headers, body) and answers with whatever the test scripted, so the proxy's forwarding
 * can be asserted byte-for-byte.
 */
public final class FakeBackend {

    public record Received(String method, String pathAndQuery, Map<String, List<String>> headers, byte[] body) {
        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    public record Scripted(int status, String contentType, String body, Map<String, String> headers) {
        public static Scripted json(int status, String body) {
            return new Scripted(status, "application/json", body, Map.of());
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Scripted next = Scripted.json(200, "{\"ok\":true}");
    private volatile CountDownLatch stall;

    private FakeBackend(HttpServer server) {
        this.server = server;
    }

    public static FakeBackend start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            FakeBackend fake = new FakeBackend(server);
            server.createContext("/", fake::handle);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the fake backend", e);
        }
    }

    public void stop() {
        server.stop(0);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        received.clear();
        next = Scripted.json(200, "{\"ok\":true}");
        stall = null;
    }

    public List<Received> received() {
        return received;
    }

    public Received last() {
        return received.get(received.size() - 1);
    }

    public void respondWith(Scripted scripted) {
        this.next = scripted;
    }

    /** The next requests hang until {@link #unstall()}; long enough for the proxy's read timeout. */
    public CountDownLatch stall() {
        CountDownLatch latch = new CountDownLatch(1);
        this.stall = latch;
        return latch;
    }

    public void unstall() {
        CountDownLatch latch = this.stall;
        this.stall = null;
        if (latch != null) {
            latch.countDown();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String pathAndQuery = exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null
                        ? ""
                        : "?" + exchange.getRequestURI().getRawQuery());
        received.add(new Received(exchange.getRequestMethod(), pathAndQuery, exchange.getRequestHeaders(), body));
        CountDownLatch latch = this.stall;
        if (latch != null) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Scripted scripted = this.next;
        byte[] bytes = scripted.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", scripted.contentType());
        scripted.headers()
                .forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(scripted.status(), bytes.length == 0 ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) {
            if (bytes.length > 0) {
                out.write(bytes);
            }
        }
    }
}
