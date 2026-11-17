package fc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Unit tests (plain Java, no framework): java -cp out fc.Tests */
public final class Tests {
    private static int run, failed;

    private static void check(boolean ok, String what) {
        run++;
        if (!ok) {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        json();
        wal();
        simplexMatchesBruteForce();
        simplexEdgeCases();
        plannerProperties();
        breakerAndBackoff();
        simulation();
        System.out.println(run + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void json() {
        String s = Json.write(Map.of("a", List.of(1, 2.5, "x\"y\n", true), "b", Map.of()));
        Map<String, Object> m = Json.obj(s);
        check(((List<?>) m.get("a")).get(2).equals("x\"y\n"), "json string escapes round-trip");
        check(Json.num(((List<?>) m.get("a")).get(0)) == 1, "json integers");
        for (String bad : new String[] {"{", "{\"a\":}", "[1,]", "{\"a\":1} x", "\"unterminated"}) {
            boolean threw = false;
            try { Json.parse(bad); } catch (RuntimeException e) { threw = true; }
            check(threw, "json rejects " + bad);
        }
    }

    static void wal() throws Exception {
        Path dir = Files.createTempDirectory("wal");
        Metrics m = new Metrics();
        Path p = dir.resolve("a.wal");
        try (Wal w = new Wal(p, Wal.Mode.PER_WRITE, m)) {
            for (int i = 0; i < 10; i++) w.append("{\"i\":" + i + "}");
        }
        Files.write(p, "{\"i\":10".getBytes(), StandardOpenOption.APPEND);     // torn write: crash mid-record
        List<String> got = new ArrayList<>();
        int torn = Wal.replay(p, got::add);
        check(got.size() == 10 && torn == 7, "replay keeps 10 complete records and drops the torn tail");

        // group commit: 64 threads x 50 appends; every record present, far fewer fsyncs than appends
        Path q = dir.resolve("b.wal");
        Metrics m2 = new Metrics();
        try (Wal w = new Wal(q, Wal.Mode.GROUP_COMMIT, m2)) {
            ExecutorService ex = Executors.newFixedThreadPool(64);
            AtomicLong n = new AtomicLong();
            for (int t = 0; t < 64; t++) {
                ex.submit(() -> {
                    for (int i = 0; i < 50; i++) {
                        try { w.append("{\"n\":" + n.incrementAndGet() + "}"); } catch (Exception e) { throw new RuntimeException(e); }
                    }
                });
            }
            ex.shutdown();
            ex.awaitTermination(60, TimeUnit.SECONDS);
            List<String> all = new ArrayList<>();
            Wal.replay(q, all::add);
            check(all.size() == 3200, "group commit keeps all 3200 records");
            check(w.fsyncs.get() < 3200, "group commit shares fsyncs (" + w.fsyncs.get() + " for 3200 appends)");
        }
    }

    // brute force over every assignment (station or defer) for tiny transportation problems
    static double brute(double[][] cost, int[] cap, double penalty) {
        int o = cost.length;
        int s = cap.length;
        double best = Double.POSITIVE_INFINITY;
        int[] pick = new int[o];
        long total = (long) Math.pow(s + 1, o);
        for (long code = 0; code < total; code++) {
            long c = code;
            int[] used = new int[s];
            double v = 0;
            boolean ok = true;
            for (int i = 0; i < o; i++) {
                pick[i] = (int) (c % (s + 1));
                c /= s + 1;
                if (pick[i] == s) v += penalty;
                else {
                    v += cost[i][pick[i]];
                    if (++used[pick[i]] > cap[pick[i]]) ok = false;
                }
            }
            if (ok) best = Math.min(best, v);
        }
        return best;
    }

    static void simplexMatchesBruteForce() {
        Random r = new Random(3);
        int mismatches = 0;
        int fractional = 0;
        for (int trial = 0; trial < 300; trial++) {
            int o = 1 + r.nextInt(6);
            int s = 1 + r.nextInt(3);
            int[] cap = new int[s];
            for (int j = 0; j < s; j++) cap[j] = r.nextInt(3);
            double[][] cost = new double[o][s];
            double penalty = 50 + r.nextInt(100);
            int nx = o * s;
            double[] c = new double[nx + o];
            double[][] a = new double[o + s][nx + o];
            double[] b = new double[o + s];
            char[] sense = new char[o + s];
            for (int i = 0; i < o; i++) {
                for (int j = 0; j < s; j++) {
                    cost[i][j] = 1 + r.nextInt(60);
                    c[i * s + j] = cost[i][j];
                    a[i][i * s + j] = 1;
                    a[o + j][i * s + j] = 1;
                }
                c[nx + i] = penalty;
                a[i][nx + i] = 1;
                b[i] = 1;
                sense[i] = '=';
            }
            for (int j = 0; j < s; j++) { b[o + j] = cap[j]; sense[o + j] = '<'; }
            Simplex.Result res = Simplex.minimize(c, a, b, sense);
            if (res.status() != Simplex.Status.OPTIMAL || Math.abs(res.objective() - brute(cost, cap, penalty)) > 1e-6) mismatches++;
            for (double x : res.x()) if (x > 1e-6 && x < 1 - 1e-6) fractional++;
        }
        check(mismatches == 0, "simplex optimum equals brute force on 300 random problems (" + mismatches + " mismatches)");
        check(fractional == 0, "transportation LP solutions are integral");
    }

    static void simplexEdgeCases() {
        // x1 + x2 = 1 and x1 + x2 <= 0.5: infeasible
        Simplex.Result r = Simplex.minimize(new double[] {1, 1}, new double[][] {{1, 1}, {1, 1}}, new double[] {1, 0.5},
                new char[] {'=', '<'});
        check(r.status() == Simplex.Status.INFEASIBLE, "detects infeasible");
        // min -x with x - y <= 1: unbounded (y can grow)
        r = Simplex.minimize(new double[] {-1, 0}, new double[][] {{1, -1}}, new double[] {1}, new char[] {'<'});
        check(r.status() == Simplex.Status.UNBOUNDED, "detects unbounded");
        // classic: min -3x - 5y, x <= 4, 2y <= 12, 3x + 2y <= 18 -> x=2, y=6, obj -36
        r = Simplex.minimize(new double[] {-3, -5}, new double[][] {{1, 0}, {0, 2}, {3, 2}}, new double[] {4, 12, 18},
                new char[] {'<', '<', '<'});
        check(r.status() == Simplex.Status.OPTIMAL && Math.abs(r.objective() + 36) < 1e-9, "textbook LP optimum -36");
    }

    static void plannerProperties() {
        Planner.Layout L = new Planner.Layout(200, 4, 7);
        Random r = new Random(5);
        boolean lpNeverWorse = true;
        boolean capsHeld = true;
        boolean deferCount = true;
        for (int t = 0; t < 100; t++) {
            List<int[]> o = Planner.randomOrders(L, 1 + r.nextInt(40), r);
            int[] cap = {r.nextInt(12), r.nextInt(12), r.nextInt(12), r.nextInt(12)};
            Planner.Plan lp = Planner.lp(L, o, cap, 1e5);
            Planner.Plan g = Planner.greedy(L, o, cap);
            int totalCap = cap[0] + cap[1] + cap[2] + cap[3];
            if (lp.deferred() != Math.max(0, o.size() - totalCap) || g.deferred() != lp.deferred()) deferCount = false;
            if (lp.travel() > g.travel() + 1e-6) lpNeverWorse = false;
            int[] used = new int[4];
            for (int s : lp.station()) if (s >= 0) used[s]++;
            for (int j = 0; j < 4; j++) if (used[j] > cap[j]) capsHeld = false;
        }
        check(lpNeverWorse, "LP travel never exceeds greedy when both serve the same number of orders");
        check(capsHeld, "LP respects station capacity");
        check(deferCount, "only orders beyond total capacity are deferred");
    }

    static void breakerAndBackoff() {
        long[] now = {0};
        Http.CircuitBreaker b = new Http.CircuitBreaker(3, 100, () -> now[0]);
        b.failure();
        b.failure();
        check(b.allow(), "breaker stays closed below the threshold");
        b.failure();
        check(!b.allow() && b.opens == 1, "breaker opens after 3 consecutive failures");
        now[0] = 99;
        check(!b.allow(), "breaker rejects during cool-down");
        now[0] = 100;
        check(b.allow() && b.state() == Http.CircuitBreaker.State.HALF_OPEN, "breaker half-opens after cool-down");
        b.failure();
        check(!b.allow() && b.opens == 2, "a failed trial call re-opens it");
        now[0] = 250;
        b.allow();
        b.success();
        check(b.state() == Http.CircuitBreaker.State.CLOSED, "a successful trial call closes it");
        Random r = new Random(1);
        boolean bounded = true;
        for (int n = 0; n < 40; n++) {
            long d = Http.backoffMs(n, 20, 1000, r);
            if (d < 0 || d > Math.min(1000, 20L << Math.min(n, 20))) bounded = false;
        }
        check(bounded, "backoff with full jitter stays within [0, min(cap, base*2^n)]");
    }

    static void simulation() {
        Sim.Config c = new Sim.Config(0.85, 3600, 4, 0.25, 160, 1.5, 400, 7);
        Sim.Result a = Sim.run(c, 5, true);
        Sim.Result b = Sim.run(c, 5, true);
        check(a.p95() == b.p95() && a.orders() == b.orders(), "simulation is deterministic for a seed");
        Sim.Result g = Sim.run(c, 5, false);
        check(a.travelPerOrder() < g.travelPerOrder(), "LP uses less drive-unit travel than greedy in the floor model");
        List<double[]> trace = new ArrayList<>();
        double[] best = Sim.goldenSection(x -> (x - 2) * (x - 2) + 1, -5, 10, 1e-4, trace);
        check(Math.abs(best[0] - 2) < 1e-3 && Math.abs(best[1] - 1) < 1e-6, "golden-section finds the minimum of a parabola");
    }
}
