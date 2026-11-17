package fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Assigns a batch of orders to pick stations. Each order needs pods (shelf units) carried by a drive unit to a station;
 * the cost of serving order o at station s is the drive distance for its pods. Each station can take `cap` orders per
 * batch window. Orders that do not fit are deferred to the next window at a penalty cost.
 *
 * LP:     min sum_os d_os x_os + P sum_o y_o   s.t.  sum_s x_os + y_o = 1 (each order),  sum_o x_os <= cap_s,  x, y >= 0
 *         (a transportation problem: its constraint matrix is totally unimodular, so the optimum is 0/1)
 * Greedy: orders in arrival order, each to its nearest station with capacity left (the baseline)
 */
public final class Planner {
    public static final int DEFER = -1;

    /** Warehouse floor: pods on a grid, stations along the west wall. Distances are Manhattan, in metres. */
    public static final class Layout {
        public final int skus;
        public final double[][] podXY;
        public final double[][] stationXY;

        public Layout(int skus, int stations, long seed) {
            this.skus = skus;
            Random r = new Random(seed);
            podXY = new double[skus][];
            for (int k = 0; k < skus; k++) podXY[k] = new double[] {6 + r.nextInt(30) * 1.5, r.nextInt(40) * 1.5};
            stationXY = new double[stations][];
            for (int s = 0; s < stations; s++) stationXY[s] = new double[] {0, (s + 0.5) * 60.0 / stations};
        }

        public int stations() { return stationXY.length; }

        public double dist(int sku, int station) {
            double[] p = podXY[sku];
            double[] q = stationXY[station];
            return Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]);
        }

        /** Round trip for every pod of the order: storage -> station -> storage. */
        public double orderCost(int[] skuList, int station) {
            double c = 0;
            for (int k : skuList) c += 2 * dist(k, station);
            return c;
        }
    }

    public record Plan(int[] station, double travel, int deferred, int pivots) { }

    public static Plan lp(Layout L, List<int[]> orders, int[] cap, double deferPenalty) {
        int o = orders.size();
        int s = L.stations();
        if (o == 0) return new Plan(new int[0], 0, 0, 0);
        int nx = o * s;
        double[] c = new double[nx + o];
        for (int i = 0; i < o; i++) {
            for (int j = 0; j < s; j++) c[i * s + j] = L.orderCost(orders.get(i), j);
            c[nx + i] = deferPenalty;
        }
        double[][] a = new double[o + s][nx + o];
        double[] b = new double[o + s];
        char[] sense = new char[o + s];
        for (int i = 0; i < o; i++) {
            for (int j = 0; j < s; j++) a[i][i * s + j] = 1;
            a[i][nx + i] = 1;
            b[i] = 1;
            sense[i] = '=';
        }
        for (int j = 0; j < s; j++) {
            for (int i = 0; i < o; i++) a[o + j][i * s + j] = 1;
            b[o + j] = cap[j];
            sense[o + j] = '<';
        }
        Simplex.Result r = Simplex.minimize(c, a, b, sense);
        if (r.status() != Simplex.Status.OPTIMAL) throw new IllegalStateException("LP " + r.status());
        int[] st = new int[o];
        double travel = 0;
        int deferred = 0;
        for (int i = 0; i < o; i++) {
            st[i] = DEFER;
            for (int j = 0; j < s; j++) {
                double v = r.x()[i * s + j];
                if (v > 1e-6 && v < 1 - 1e-6) throw new IllegalStateException("fractional LP solution");
                if (v > 0.5) {
                    st[i] = j;
                    travel += c[i * s + j];
                }
            }
            if (st[i] == DEFER) deferred++;
        }
        return new Plan(st, travel, deferred, r.pivots());
    }

    public static Plan greedy(Layout L, List<int[]> orders, int[] cap) {
        int[] left = cap.clone();
        int[] st = new int[orders.size()];
        double travel = 0;
        int deferred = 0;
        for (int i = 0; i < orders.size(); i++) {
            int best = DEFER;
            double bestC = Double.POSITIVE_INFINITY;
            for (int j = 0; j < L.stations(); j++) {
                double cj = L.orderCost(orders.get(i), j);
                if (left[j] > 0 && cj < bestC) {
                    bestC = cj;
                    best = j;
                }
            }
            st[i] = best;
            if (best == DEFER) deferred++;
            else {
                left[best]--;
                travel += bestC;
            }
        }
        return new Plan(st, travel, deferred, 0);
    }

    public static List<int[]> randomOrders(Layout L, int n, Random r) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int items = 1 + r.nextInt(3);
            int[] k = new int[items];
            for (int t = 0; t < items; t++) k[t] = r.nextInt(L.skus);
            out.add(k);
        }
        return out;
    }
}
