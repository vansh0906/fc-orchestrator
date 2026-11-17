package fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Discrete-event model of a goods-to-person fulfillment floor (simulated time, deterministic for a given seed).
 *
 * Orders arrive (Poisson). Every T seconds the planner assigns the waiting orders to pick stations (LP or greedy,
 * capacity = station pick rate x T per window). A drive unit carries the order's pods to the station, waits in the
 * station queue, the picker picks, and the unit returns. An order is done when it is picked.
 */
public final class Sim {
    public record Config(double arrivalsPerS, double horizonS, int stations, double picksPerS, int robots,
                         double speedMps, int skus, long seed) { }

    public record Result(double windowS, String planner, int orders, double p50, double p95, double travelPerOrder,
                         double robotUtil, double stationUtil, int maxBacklog) { }

    public static Config defaults() {
        return new Config(0.85, 4 * 3600, 4, 0.25, 160, 1.5, 400, 42);
    }

    public static Result run(Config c, double windowS, boolean useLp) {
        Random r = new Random(c.seed());
        Planner.Layout L = new Planner.Layout(c.skus(), c.stations(), c.seed() + 1);
        // arrivals (common random numbers: the same order stream for every configuration)
        List<double[]> arr = new ArrayList<>();
        List<int[]> skus = new ArrayList<>();
        for (double t = 0; ; ) {
            t += -Math.log(1 - r.nextDouble()) / c.arrivalsPerS();
            if (t > c.horizonS()) break;
            arr.add(new double[] {t});
            int items = 1 + r.nextInt(3);
            int[] k = new int[items];
            for (int i = 0; i < items; i++) k[i] = r.nextInt(c.skus());
            skus.add(k);
        }
        int n = arr.size();
        double[] done = new double[n];
        Arrays.fill(done, Double.NaN);
        PriorityQueue<Double> robots = new PriorityQueue<>();      // time each drive unit becomes free
        for (int i = 0; i < c.robots(); i++) robots.add(0.0);
        double[] stationFree = new double[c.stations()];
        double[] credit = new double[c.stations()];
        double travel = 0;
        double robotBusy = 0;
        double stationBusy = 0;
        int next = 0;
        int maxBacklog = 0;
        List<Integer> pending = new ArrayList<>();
        double pick = 1.0 / c.picksPerS();

        for (double t = windowS; next < n || !pending.isEmpty(); t += windowS) {
            while (next < n && arr.get(next)[0] <= t) pending.add(next++);
            maxBacklog = Math.max(maxBacklog, pending.size());
            if (pending.isEmpty()) continue;
            List<int[]> batch = new ArrayList<>();
            for (int id : pending) batch.add(skus.get(id));
            // capacity per window from each station's pick rate, carrying the fractional part to the next window
            int[] cap = new int[c.stations()];
            for (int s = 0; s < c.stations(); s++) {
                credit[s] = Math.min(credit[s] + c.picksPerS() * windowS, c.picksPerS() * windowS + 1);
                cap[s] = (int) Math.floor(credit[s] + 1e-9);
            }
            Planner.Plan p = useLp ? Planner.lp(L, batch, cap, 1e5) : Planner.greedy(L, batch, cap);
            List<Integer> still = new ArrayList<>();
            for (int k = 0; k < pending.size(); k++) {
                int id = pending.get(k);
                int s = p.station()[k];
                if (s == Planner.DEFER) {
                    still.add(id);
                    continue;
                }
                double trip = L.orderCost(skus.get(id), s) / c.speedMps();
                double start = Math.max(t, robots.poll());
                double atStation = start + trip / 2;
                double pickStart = Math.max(atStation, stationFree[s]);
                double pickEnd = pickStart + pick;
                stationFree[s] = pickEnd;
                credit[s] -= 1;
                double free = pickEnd + trip / 2;
                robots.add(free);
                robotBusy += free - start;
                stationBusy += pick;
                travel += L.orderCost(skus.get(id), s);
                done[id] = pickEnd;
            }
            pending = still;
            if (t > c.horizonS() * 4) throw new IllegalStateException("system unstable");
        }
        double[] lat = new double[n];
        double end = 0;
        for (int i = 0; i < n; i++) {
            lat[i] = done[i] - arr.get(i)[0];
            end = Math.max(end, done[i]);
        }
        Arrays.sort(lat);
        return new Result(windowS, useLp ? "lp" : "greedy", n, lat[n / 2], lat[(int) (0.95 * n)], travel / n,
                robotBusy / (c.robots() * end), stationBusy / (c.stations() * end), maxBacklog);
    }

    /** Golden-section search for the window that minimises f on [a, b] (f assumed unimodal there). */
    public static double[] goldenSection(java.util.function.DoubleUnaryOperator f, double a, double b, double tol,
                                         List<double[]> trace) {
        double g = (Math.sqrt(5) - 1) / 2;
        double x1 = b - g * (b - a);
        double x2 = a + g * (b - a);
        double f1 = f.applyAsDouble(x1);
        double f2 = f.applyAsDouble(x2);
        trace.add(new double[] {x1, f1});
        trace.add(new double[] {x2, f2});
        while (b - a > tol) {
            if (f1 <= f2) {
                b = x2;
                x2 = x1;
                f2 = f1;
                x1 = b - g * (b - a);
                f1 = f.applyAsDouble(x1);
                trace.add(new double[] {x1, f1});
            } else {
                a = x1;
                x1 = x2;
                f1 = f2;
                x2 = a + g * (b - a);
                f2 = f.applyAsDouble(x2);
                trace.add(new double[] {x2, f2});
            }
        }
        return f1 <= f2 ? new double[] {x1, f1} : new double[] {x2, f2};
    }

    static Map<String, Object> row(Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("window_s", r.windowS());
        m.put("planner", r.planner());
        m.put("orders", r.orders());
        m.put("p50_s", Math.round(r.p50() * 10) / 10.0);
        m.put("p95_s", Math.round(r.p95() * 10) / 10.0);
        m.put("travel_m_per_order", Math.round(r.travelPerOrder() * 10) / 10.0);
        m.put("robot_util", Math.round(r.robotUtil() * 1000) / 1000.0);
        m.put("station_util", Math.round(r.stationUtil() * 1000) / 1000.0);
        m.put("max_backlog", r.maxBacklog());
        return m;
    }

    public static void main(String[] args) {
        Config c = defaults();
        List<Object> sweep = new ArrayList<>();
        for (double w : new double[] {1, 2, 3, 4, 6, 8, 10, 12, 16, 20, 24, 32, 48, 64}) {
            for (boolean lp : new boolean[] {false, true}) sweep.add(row(run(c, w, lp)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", Map.of("arrivals_per_s", c.arrivalsPerS(), "hours", c.horizonS() / 3600, "stations", c.stations(),
                "picks_per_s_per_station", c.picksPerS(), "drive_units", c.robots(), "speed_mps", c.speedMps(), "skus", c.skus()));
        out.put("sweep", sweep);
        // nonlinear, derivative-free: golden-section search on log(window) for the window that minimises p95 latency
        Map<String, Object> golden = new LinkedHashMap<>();
        for (boolean lp : new boolean[] {false, true}) {
            List<double[]> trace = new ArrayList<>();
            double[] best = goldenSection(u -> run(c, Math.exp(u), lp).p95(), Math.log(1), Math.log(64), 0.05, trace);
            List<Object> tr = new ArrayList<>();
            for (double[] t : trace) tr.add(List.of(Math.round(Math.exp(t[0]) * 100) / 100.0, Math.round(t[1] * 10) / 10.0));
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("window_s", Math.round(Math.exp(best[0]) * 100) / 100.0);
            g.put("p95_s", Math.round(best[1] * 10) / 10.0);
            g.put("evaluations", trace.size());
            g.put("best", row(run(c, Math.exp(best[0]), lp)));
            g.put("trace", tr);
            golden.put(lp ? "lp" : "greedy", g);
        }
        out.put("golden_section", golden);
        System.out.println(Json.write(out));
    }
}
