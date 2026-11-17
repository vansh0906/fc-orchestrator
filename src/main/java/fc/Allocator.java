package fc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allocator: batches incoming orders every window, assigns them to pick stations (LP or greedy), dispatches drive
 * units, and reports each completion back to the order service.
 *
 *   POST /tasks  {"orders":[{"id","skus"}...]}  idempotent per order id
 *   GET  /stats, /metrics, /health
 *
 * The floor runs in accelerated time (--scale 0.01: one simulated second = 10 ms) with the same drive-unit and
 * station model as Sim. Every state change is in the WAL; completions go out through an outbox that retries until
 * the order service acknowledges, so a crash on either side neither loses nor double-completes an order.
 */
public final class Allocator {
    enum Status { PENDING, ASSIGNED, DONE, NOTIFIED }

    static final class Task {
        final String id;
        final int[] skus;
        final long seq = SEQ.incrementAndGet();
        volatile Status status = Status.PENDING;
        volatile int station = -1;
        volatile long etaMs;
        Task(String id, int[] skus) { this.id = id; this.skus = skus; }
    }

    static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();
    static final int MAX_BATCH = 120;            // bounds the LP size after a backlog builds up (oldest orders first)

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Metrics m = new Metrics();
    private final Wal wal;
    private final Http.Client orders;
    private final Planner.Layout layout;
    private final boolean useLp;
    private final double windowS;
    private final double scale;
    private final double picksPerS = 0.25;
    private final double speed = 1.5;
    private final PriorityQueue<Long> robots = new PriorityQueue<>();
    private final long[] stationFree;
    private final double[] credit;
    private final Random rng = new Random();

    Allocator(Path data, Wal.Mode mode, String ordersUrl, boolean useLp, double windowS, double scale, int units)
            throws Exception {
        layout = new Planner.Layout(400, 4, 43);
        this.useLp = useLp;
        this.windowS = windowS;
        this.scale = scale;
        stationFree = new long[layout.stations()];
        credit = new double[layout.stations()];
        long now = System.currentTimeMillis();
        for (int i = 0; i < units; i++) robots.add(now);
        Path log = data.resolve("allocator.wal");
        m.counter("wal_torn_bytes_on_recovery").add(Wal.replay(log, this::apply));
        wal = new Wal(log, mode, m);
        orders = new Http.Client(ordersUrl, new Http.CircuitBreaker(3, 500, System::currentTimeMillis), m);
        for (Status s : Status.values()) {
            m.gauge("tasks_" + s.name().toLowerCase(), () -> tasks.values().stream().filter(t -> t.status == s).count());
        }
        m.counter("recovered_tasks").add(tasks.size());
    }

    @SuppressWarnings("unchecked")
    private void apply(String line) {
        Map<String, Object> r = Json.obj(line);
        String id = (String) r.get("id");
        switch ((String) r.get("t")) {
            case "task" -> {
                List<Object> k = (List<Object>) r.get("skus");
                int[] skus = new int[k.size()];
                for (int i = 0; i < skus.length; i++) skus[i] = (int) Json.num(k.get(i));
                tasks.putIfAbsent(id, new Task(id, skus));
            }
            case "assign" -> {
                Task t = tasks.get(id);
                if (t != null && t.status == Status.PENDING) {
                    t.status = Status.ASSIGNED;
                    t.station = (int) Json.num(r.get("s"));
                    t.etaMs = Json.num(r.get("eta"));
                }
            }
            case "done" -> { Task t = tasks.get(id); if (t != null && t.status.ordinal() < 2) t.status = Status.DONE; }
            case "notified" -> { Task t = tasks.get(id); if (t != null) t.status = Status.NOTIFIED; }
            default -> { }
        }
    }

    @SuppressWarnings("unchecked")
    Http.Response handle(String method, String path, String body) throws Exception {
        if (path.equals("/health")) return new Http.Response(200, "{\"ok\":true}");
        if (path.equals("/metrics")) return new Http.Response(200, m.render());
        if (path.equals("/stats")) return new Http.Response(200, Json.write(stats()));
        if (method.equals("POST") && path.equals("/tasks")) {
            List<Object> list = (List<Object>) Json.obj(body).get("orders");
            if (list == null) throw new IllegalArgumentException("body needs an orders list");
            List<Task> fresh = new ArrayList<>();
            int dup = 0;
            synchronized (tasks) {
                for (Object o : list) {
                    Map<String, Object> e = (Map<String, Object>) o;
                    String id = (String) e.get("id");
                    if (tasks.containsKey(id)) { dup++; continue; }
                    List<Object> k = (List<Object>) e.get("skus");
                    int[] skus = new int[k.size()];
                    for (int i = 0; i < skus.length; i++) skus[i] = Math.floorMod((int) Json.num(k.get(i)), layout.skus);
                    fresh.add(new Task(id, skus));
                }
                StringBuilder b = new StringBuilder();
                for (Task t : fresh) {
                    if (b.length() > 0) b.append('\n');
                    b.append(Json.write(Map.of("t", "task", "id", t.id, "skus", Arrays.stream(t.skus).boxed().toList())));
                }
                if (!fresh.isEmpty()) wal.append(b.toString());     // one durable write for the whole batch
                for (Task t : fresh) tasks.put(t.id, t);
            }
            m.counter("tasks_received_total").add(fresh.size());
            m.counter("tasks_duplicate_total").add(dup);
            return new Http.Response(200, Json.write(Map.of("accepted", fresh.size(), "duplicates", dup)));
        }
        return new Http.Response(404, "{\"error\":\"not found\"}");
    }

    Map<String, Object> stats() {
        long[] c = new long[4];
        for (Task t : tasks.values()) c[t.status.ordinal()]++;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("tasks", tasks.size());
        s.put("pending", c[0]);
        s.put("assigned", c[1]);
        s.put("done", c[2]);
        s.put("notified", c[3]);
        s.put("planner", useLp ? "lp" : "greedy");
        s.put("window_s", windowS);
        return s;
    }

    /** Every window: plan the pending orders, dispatch drive units, schedule completions (accelerated time). */
    void planLoop() {
        long windowMs = Math.max(1, Math.round(windowS * 1000 * scale));
        while (true) {
            try {
                Thread.sleep(windowMs);
                long now = System.currentTimeMillis();
                List<Task> pending = new ArrayList<>();
                for (Task t : tasks.values()) if (t.status == Status.PENDING) pending.add(t);
                if (pending.isEmpty()) continue;
                pending.sort(java.util.Comparator.comparingLong(t -> t.seq));
                if (pending.size() > MAX_BATCH) pending = new ArrayList<>(pending.subList(0, MAX_BATCH));
                int[] cap = new int[layout.stations()];
                for (int s = 0; s < cap.length; s++) {
                    credit[s] = Math.min(credit[s] + picksPerS * windowS, picksPerS * windowS + 1);
                    cap[s] = (int) Math.floor(credit[s] + 1e-9);
                }
                List<int[]> batch = new ArrayList<>();
                for (Task t : pending) batch.add(t.skus);
                long t0 = System.nanoTime();
                Planner.Plan p = useLp ? Planner.lp(layout, batch, cap, 1e5) : Planner.greedy(layout, batch, cap);
                m.latency("plan_ms").record((System.nanoTime() - t0) / 1e6);
                m.counter("plans_total").inc();
                StringBuilder b = new StringBuilder();
                List<Task> assigned = new ArrayList<>();
                for (int k = 0; k < pending.size(); k++) {
                    int s = p.station()[k];
                    if (s == Planner.DEFER) continue;
                    Task t = pending.get(k);
                    double tripMs = layout.orderCost(t.skus, s) / speed * 1000 * scale;
                    long start = Math.max(now, robots.poll());
                    long atStation = start + Math.round(tripMs / 2);
                    long pickEnd = Math.max(atStation, stationFree[s]) + Math.round(1000 * scale / picksPerS);
                    stationFree[s] = pickEnd;
                    credit[s] -= 1;
                    robots.add(pickEnd + Math.round(tripMs / 2));
                    t.station = s;
                    t.etaMs = pickEnd;
                    assigned.add(t);
                    if (b.length() > 0) b.append('\n');
                    b.append(Json.write(Map.of("t", "assign", "id", t.id, "s", s, "eta", pickEnd)));
                }
                if (!assigned.isEmpty()) {
                    wal.append(b.toString());
                    for (Task t : assigned) t.status = Status.ASSIGNED;
                    m.counter("tasks_assigned_total").add(assigned.size());
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                m.counter("plan_errors_total").inc();
            }
        }
    }

    /** Marks picks complete when their time comes. */
    void completionLoop() {
        while (true) {
            try {
                Thread.sleep(5);
                long now = System.currentTimeMillis();
                List<Task> due = new ArrayList<>();
                for (Task t : tasks.values()) if (t.status == Status.ASSIGNED && t.etaMs <= now) due.add(t);
                if (due.isEmpty()) continue;
                StringBuilder b = new StringBuilder();
                for (Task t : due) {
                    if (b.length() > 0) b.append('\n');
                    b.append(Json.write(Map.of("t", "done", "id", t.id)));
                }
                wal.append(b.toString());
                for (Task t : due) t.status = Status.DONE;
                m.counter("tasks_done_total").add(due.size());
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                m.counter("completion_errors_total").inc();
            }
        }
    }

    /** Outbox: reports DONE tasks to the order service in batches until it acknowledges (at-least-once). */
    void outboxLoop() {
        int failures = 0;
        while (true) {
            try {
                List<Task> batch = new ArrayList<>();
                for (Task t : tasks.values()) {
                    if (t.status == Status.DONE) batch.add(t);
                    if (batch.size() >= 500) break;
                }
                if (batch.isEmpty()) {
                    Thread.sleep(5);
                    continue;
                }
                List<Object> ids = new ArrayList<>();
                for (Task t : batch) ids.add(t.id);
                orders.call("POST", "/orders/done", Json.write(Map.of("ids", ids)), 2000);
                StringBuilder b = new StringBuilder();
                for (Task t : batch) {
                    if (b.length() > 0) b.append('\n');
                    b.append(Json.write(Map.of("t", "notified", "id", t.id)));
                }
                wal.append(b.toString());
                for (Task t : batch) t.status = Status.NOTIFIED;
                m.counter("outbox_delivered_total").add(batch.size());
                failures = 0;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                m.counter("outbox_failures_total").inc();
                try { Thread.sleep(Http.backoffMs(failures++, 20, 1000, rng)); } catch (InterruptedException ie) { return; }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = Args.parse(args);
        Allocator s = new Allocator(Path.of(a.getOrDefault("data", "data/allocator")),
                Wal.Mode.valueOf(a.getOrDefault("wal", "GROUP_COMMIT")), a.getOrDefault("orders", "http://127.0.0.1:8101"),
                !a.getOrDefault("planner", "lp").equals("greedy"), Double.parseDouble(a.getOrDefault("window-s", "5")),
                Double.parseDouble(a.getOrDefault("scale", "0.01")), Integer.parseInt(a.getOrDefault("units", "160")));
        Http.serve(Integer.parseInt(a.getOrDefault("port", "8102")), s::handle, s.m);
        for (Runnable r : new Runnable[] {s::planLoop, s::completionLoop, s::outboxLoop}) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.start();
        }
        System.out.println("allocator up, recovered " + s.tasks.size() + " tasks");
    }
}
