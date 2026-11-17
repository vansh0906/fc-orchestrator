package fc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Order service: the front door. Accepts customer orders, makes them durable, and hands them to the allocator.
 *
 *   POST /orders            {"id": "...", "skus": [..]}  idempotent on id: 202 new, 200 duplicate
 *   POST /orders/done       {"ids": [...]}  batched completion callback from the allocator (idempotent)
 *   GET  /orders/{id}, /stats, /metrics, /health
 *
 * An order is acknowledged only after its WAL record is on disk. A forwarder thread sends ACCEPTED orders to the
 * allocator in batches (at-least-once: after a crash, anything not marked FORWARDED is sent again; the allocator
 * de-duplicates by order id).
 */
public final class OrderService {
    enum Status { ACCEPTED, FORWARDED, DONE }

    static final class Order {
        final String id;
        final List<Object> skus;
        final long acceptedMs;
        final java.util.concurrent.CompletableFuture<Void> durable = new java.util.concurrent.CompletableFuture<>();
        volatile Status status = Status.ACCEPTED;
        Order(String id, List<Object> skus, long acceptedMs) { this.id = id; this.skus = skus; this.acceptedMs = acceptedMs; }
    }

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Object doneLock = new Object();
    private final Metrics m = new Metrics();
    private final Wal wal;
    private final Http.Client allocator;
    private final Random rng = new Random();

    OrderService(Path data, Wal.Mode mode, String allocatorUrl) throws Exception {
        Path log = data.resolve("orders.wal");
        int torn = Wal.replay(log, this::apply);
        m.counter("wal_torn_bytes_on_recovery").add(torn);
        wal = new Wal(log, mode, m);
        allocator = new Http.Client(allocatorUrl, new Http.CircuitBreaker(3, 500, System::currentTimeMillis), m);
        m.gauge("orders_open", () -> orders.values().stream().filter(o -> o.status != Status.DONE).count());
        m.gauge("orders_unforwarded", () -> orders.values().stream().filter(o -> o.status == Status.ACCEPTED).count());
        m.gauge("orders_total", orders::size);
        m.gauge("breaker_open", () -> allocator.breaker().state() == Http.CircuitBreaker.State.OPEN ? 1 : 0);
        m.counter("recovered_orders").add(orders.size());
    }

    @SuppressWarnings("unchecked")
    private void apply(String line) {
        Map<String, Object> r = Json.obj(line);
        String id = (String) r.get("id");
        switch ((String) r.get("t")) {
            case "accept" -> {
                Order o = new Order(id, (List<Object>) r.get("skus"), Json.num(r.get("ms")));
                o.durable.complete(null);
                orders.putIfAbsent(id, o);
            }
            case "fwd" -> {
                for (Object x : (List<Object>) r.get("ids")) {
                    Order o = orders.get((String) x);
                    if (o != null && o.status == Status.ACCEPTED) o.status = Status.FORWARDED;
                }
            }
            case "done" -> { Order o = orders.get(id); if (o != null) o.status = Status.DONE; }
            default -> { }
        }
    }

    @SuppressWarnings("unchecked")
    Http.Response handle(String method, String path, String body) throws Exception {
        if (path.equals("/health")) return new Http.Response(200, "{\"ok\":true}");
        if (path.equals("/metrics")) return new Http.Response(200, m.render());
        if (path.equals("/stats")) return new Http.Response(200, Json.write(stats()));
        if (method.equals("POST") && path.equals("/orders")) {
            Map<String, Object> req = Json.obj(body);
            Object idObj = req.get("id");
            Object skus = req.get("skus");
            if (!(idObj instanceof String id) || id.isEmpty() || !(skus instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalArgumentException("order needs a non-empty string id and a non-empty skus list");
            }
            // claim the id in memory first (one accept per id, even under concurrent retries), then make it durable;
            // a duplicate that races the first request waits until that record is on disk before answering
            Order mine = new Order(id, (List<Object>) list, System.currentTimeMillis());
            Order existing = orders.putIfAbsent(id, mine);
            if (existing != null) {
                m.counter("orders_duplicate_requests_total").inc();
                try {
                    existing.durable.get(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    return new Http.Response(503, "{\"error\":\"retry\"}");
                }
                return new Http.Response(200, Json.write(Map.of("id", id, "status", existing.status.name(), "duplicate", true)));
            }
            try {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("t", "accept");
                rec.put("id", id);
                rec.put("skus", list);
                rec.put("ms", mine.acceptedMs);
                wal.append(Json.write(rec));       // concurrent appends share one fsync in GROUP_COMMIT mode
                mine.durable.complete(null);
            } catch (Exception e) {
                orders.remove(id, mine);
                mine.durable.completeExceptionally(e);
                return new Http.Response(503, "{\"error\":\"not stored, retry\"}");
            }
            m.counter("orders_accepted_total").inc();
            return new Http.Response(202, Json.write(Map.of("id", id, "status", "ACCEPTED")));
        }
        if (method.equals("POST") && path.equals("/orders/done")) {
            // batched completion callback from the allocator; idempotent per order id
            List<Object> ids = (List<Object>) Json.obj(body).get("ids");
            if (ids == null) throw new IllegalArgumentException("body needs an ids list");
            List<Order> fresh = new ArrayList<>();
            int dup = 0;
            int unknown = 0;
            long now = System.currentTimeMillis();
            synchronized (doneLock) {
                StringBuilder b = new StringBuilder();
                for (Object x : ids) {
                    Order o = orders.get((String) x);
                    if (o == null) { unknown++; continue; }
                    if (o.status == Status.DONE || fresh.contains(o)) { dup++; continue; }
                    fresh.add(o);
                    if (b.length() > 0) b.append('\n');
                    b.append(Json.write(Map.of("t", "done", "id", o.id, "ms", now)));
                }
                if (!fresh.isEmpty()) wal.append(b.toString());     // one durable write for the batch
                for (Order o : fresh) o.status = Status.DONE;
            }
            for (Order o : fresh) m.latency("order_e2e_ms").record(now - o.acceptedMs);
            m.counter("orders_done_total").add(fresh.size());
            m.counter("orders_duplicate_done_total").add(dup);
            return new Http.Response(200, Json.write(Map.of("updated", fresh.size(), "duplicates", dup, "unknown", unknown)));
        }
        if (method.equals("GET") && path.startsWith("/orders/")) {
            Order o = orders.get(path.substring(8));
            if (o == null) return new Http.Response(404, "{\"error\":\"unknown order\"}");
            return new Http.Response(200, Json.write(Map.of("id", o.id, "status", o.status.name())));
        }
        return new Http.Response(404, "{\"error\":\"not found\"}");
    }

    Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        long[] c = new long[3];
        for (Order o : orders.values()) c[o.status.ordinal()]++;
        s.put("orders", orders.size());
        s.put("accepted", c[0]);
        s.put("forwarded", c[1]);
        s.put("done", c[2]);
        s.put("duplicate_requests", m.counter("orders_duplicate_requests_total").get());
        s.put("duplicate_done", m.counter("orders_duplicate_done_total").get());
        s.put("breaker", allocator.breaker().state().name());
        s.put("breaker_opens", allocator.breaker().opens);
        return s;
    }

    /** Sends ACCEPTED orders to the allocator in batches; backs off (with jitter) while the allocator is failing. */
    void forwardLoop() {
        int failures = 0;
        while (true) {
            try {
                List<Order> batch = new ArrayList<>();
                for (Order o : orders.values()) {
                    if (o.status == Status.ACCEPTED && o.durable.isDone() && !o.durable.isCompletedExceptionally()) batch.add(o);
                    if (batch.size() >= 200) break;
                }
                if (batch.isEmpty()) {
                    Thread.sleep(20);
                    continue;
                }
                List<Object> items = new ArrayList<>();
                List<Object> ids = new ArrayList<>();
                for (Order o : batch) {
                    items.add(Map.of("id", o.id, "skus", o.skus));
                    ids.add(o.id);
                }
                allocator.call("POST", "/tasks", Json.write(Map.of("orders", items)), 1000);
                wal.append(Json.write(Map.of("t", "fwd", "ids", ids)));
                for (Order o : batch) if (o.status == Status.ACCEPTED) o.status = Status.FORWARDED;
                m.counter("orders_forwarded_total").add(batch.size());
                failures = 0;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                m.counter("forward_failures_total").inc();
                try { Thread.sleep(Http.backoffMs(failures++, 20, 1000, rng)); } catch (InterruptedException ie) { return; }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = Args.parse(args);
        OrderService s = new OrderService(Path.of(a.getOrDefault("data", "data/orders")),
                Wal.Mode.valueOf(a.getOrDefault("wal", "GROUP_COMMIT")), a.getOrDefault("allocator", "http://127.0.0.1:8102"));
        Http.serve(Integer.parseInt(a.getOrDefault("port", "8101")), s::handle, s.m);
        Thread f = new Thread(s::forwardLoop, "forwarder");
        f.setDaemon(true);
        f.start();
        System.out.println("order-service up, recovered " + s.orders.size() + " orders");
    }
}
