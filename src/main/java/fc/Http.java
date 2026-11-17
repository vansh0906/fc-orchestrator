package fc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/** HTTP server helpers, and a client with timeouts, retries (exponential backoff + full jitter) and a circuit breaker. */
public final class Http {
    private Http() { }

    public record Response(int status, String body) { }

    public interface Handler {
        Response handle(String method, String path, String body) throws Exception;
    }

    public static HttpServer serve(int port, Handler h, Metrics m) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 512);
        server.createContext("/", ex -> {
            long t0 = System.nanoTime();
            Response r;
            try {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                r = h.handle(ex.getRequestMethod(), ex.getRequestURI().getPath(), body);
            } catch (IllegalArgumentException e) {
                r = new Response(400, Json.write(java.util.Map.of("error", String.valueOf(e.getMessage()))));
            } catch (Exception e) {
                r = new Response(500, Json.write(java.util.Map.of("error", String.valueOf(e))));
            }
            send(ex, r);
            m.counter("http_responses_" + r.status() / 100 + "xx_total").inc();
            m.latency("http_server_ms").record((System.nanoTime() - t0) / 1e6);
        });
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        return server;
    }

    private static void send(HttpExchange ex, Response r) throws IOException {
        byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", r.body().startsWith("{") || r.body().startsWith("[")
                ? "application/json" : "text/plain; version=0.0.4");
        ex.sendResponseHeaders(r.status(), b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        }
        ex.close();
    }

    // ------------------------------------------------------------------------------------------ circuit breaker

    /** Closed -> (N consecutive failures) -> Open -> (cool-down) -> Half-open: one trial call decides. */
    public static final class CircuitBreaker {
        public enum State { CLOSED, OPEN, HALF_OPEN }

        private final int threshold;
        private final long coolDownMs;
        private final LongSupplier clock;
        private State state = State.CLOSED;
        private int failures;
        private long openedAt;
        public int opens;

        public CircuitBreaker(int threshold, long coolDownMs, LongSupplier clockMs) {
            this.threshold = threshold;
            this.coolDownMs = coolDownMs;
            this.clock = clockMs;
        }

        public synchronized boolean allow() {
            if (state == State.OPEN && clock.getAsLong() - openedAt >= coolDownMs) state = State.HALF_OPEN;
            return state != State.OPEN;
        }

        public synchronized void success() {
            failures = 0;
            state = State.CLOSED;
        }

        public synchronized void failure() {
            failures++;
            if (state == State.HALF_OPEN || failures >= threshold) {
                if (state != State.OPEN) opens++;
                state = State.OPEN;
                openedAt = clock.getAsLong();
            }
        }

        public synchronized State state() {
            allow();
            return state;
        }
    }

    // ------------------------------------------------------------------------------------------ client

    /** Delay before retry number `attempt` (0-based): uniform in [0, min(cap, base * 2^attempt)] ("full jitter"). */
    public static long backoffMs(int attempt, long baseMs, long capMs, Random rng) {
        long ceiling = Math.min(capMs, baseMs << Math.min(attempt, 20));
        return (long) (rng.nextDouble() * (ceiling + 1));
    }

    public static final class Client {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        private final String base;
        private final CircuitBreaker breaker;
        private final Random rng = new Random();
        private final Metrics m;

        public Client(String base, CircuitBreaker breaker, Metrics m) {
            this.base = base;
            this.breaker = breaker;
            this.m = m;
        }

        public CircuitBreaker breaker() { return breaker; }

        /** One attempt; throws on transport error or 5xx. Fails fast without calling while the breaker is open. */
        public Response call(String method, String path, String body, long timeoutMs) throws IOException {
            if (!breaker.allow()) {
                m.counter("client_breaker_rejections_total").inc();
                throw new IOException("circuit open");
            }
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMillis(timeoutMs))
                        .header("Content-Type", "application/json");
                b = method.equals("GET") ? b.GET() : b.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() >= 500) throw new IOException("HTTP " + r.statusCode());
                breaker.success();
                return new Response(r.statusCode(), r.body());
            } catch (IOException e) {
                breaker.failure();
                m.counter("client_errors_total").inc();
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        /** Retries with backoff + jitter until success or the deadline; safe only for idempotent requests. */
        public Response callWithRetry(String method, String path, String body, long timeoutMs, long deadlineMs)
                throws IOException {
            long end = System.currentTimeMillis() + deadlineMs;
            IOException last = null;
            for (int attempt = 0; System.currentTimeMillis() < end; attempt++) {
                try {
                    return call(method, path, body, timeoutMs);
                } catch (IOException e) {
                    last = e;
                    m.counter("client_retries_total").inc();
                    long sleep = Math.min(backoffMs(attempt, 20, 1000, rng), Math.max(0, end - System.currentTimeMillis()));
                    try { Thread.sleep(sleep); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
            throw last != null ? last : new IOException("deadline");
        }
    }
}
