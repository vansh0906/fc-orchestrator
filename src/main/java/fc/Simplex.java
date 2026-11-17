package fc;

/**
 * Dense tableau simplex for: minimize c.x subject to A x (<= or =) b, x >= 0, with b >= 0.
 * Equality rows get artificial variables with a big-M cost; Bland's rule prevents cycling on degenerate problems
 * (transportation problems are highly degenerate).
 */
public final class Simplex {
    public enum Status { OPTIMAL, INFEASIBLE, UNBOUNDED }

    public record Result(Status status, double[] x, double objective, int pivots) { }

    private static final double EPS = 1e-9;

    private Simplex() { }

    public static Result minimize(double[] c, double[][] a, double[] b, char[] sense) {
        int m = a.length;
        int n = c.length;
        int slacks = 0;
        int arts = 0;
        for (char s : sense) {
            if (s == '<') slacks++;
            else if (s == '=') arts++;
            else throw new IllegalArgumentException("sense must be '<' or '='");
        }
        int cols = n + slacks + arts;
        double[][] t = new double[m + 1][cols + 1];
        int[] basis = new int[m];
        double maxC = 1;
        for (double v : c) maxC = Math.max(maxC, Math.abs(v));
        double bigM = 1e4 * maxC * (m + 1);

        int si = n;
        int ai = n + slacks;
        for (int i = 0; i < m; i++) {
            if (b[i] < 0) throw new IllegalArgumentException("b must be >= 0");
            System.arraycopy(a[i], 0, t[i], 0, n);
            t[i][cols] = b[i];
            if (sense[i] == '<') {
                t[i][si] = 1;
                basis[i] = si++;
            } else {
                t[i][ai] = 1;
                basis[i] = ai++;
            }
        }
        // objective row holds reduced costs: start from c (+ M on artificials), then price out the basic artificials
        System.arraycopy(c, 0, t[m], 0, n);
        for (int j = n + slacks; j < cols; j++) t[m][j] = bigM;
        for (int i = 0; i < m; i++) {
            if (basis[i] >= n + slacks) {
                for (int j = 0; j <= cols; j++) t[m][j] -= bigM * t[i][j];
            }
        }

        int pivots = 0;
        while (true) {
            int enter = -1;
            for (int j = 0; j < cols; j++) {           // Bland: lowest-index column with a negative reduced cost
                if (t[m][j] < -EPS * bigM * 1e-6 && t[m][j] < -1e-9) { enter = j; break; }
            }
            if (enter < 0) break;
            int leave = -1;
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < m; i++) {
                if (t[i][enter] > EPS) {
                    double r = t[i][cols] / t[i][enter];
                    if (r < best - EPS || (Math.abs(r - best) <= EPS && basis[i] < basis[leave])) {
                        best = r;
                        leave = i;
                    }
                }
            }
            if (leave < 0) return new Result(Status.UNBOUNDED, null, Double.NEGATIVE_INFINITY, pivots);
            pivot(t, leave, enter);
            basis[leave] = enter;
            pivots++;
            if (pivots > 100000) throw new IllegalStateException("simplex did not converge");
        }

        double[] x = new double[n];
        for (int i = 0; i < m; i++) {
            if (basis[i] >= n + slacks && t[i][cols] > 1e-7) {
                return new Result(Status.INFEASIBLE, null, Double.NaN, pivots);
            }
            if (basis[i] < n) x[basis[i]] = t[i][cols];
        }
        double obj = 0;
        for (int j = 0; j < n; j++) obj += c[j] * x[j];
        return new Result(Status.OPTIMAL, x, obj, pivots);
    }

    private static void pivot(double[][] t, int r, int col) {
        double p = t[r][col];
        double[] row = t[r];
        for (int j = 0; j < row.length; j++) row[j] /= p;
        for (int i = 0; i < t.length; i++) {
            if (i == r) continue;
            double f = t[i][col];
            if (f == 0) continue;
            double[] ti = t[i];
            for (int j = 0; j < ti.length; j++) ti[j] -= f * row[j];
        }
    }
}
