package fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Counters, gauges and latency histograms, rendered in Prometheus text format at /metrics. */
public final class Metrics {
    public static final class Counter {
        private final AtomicLong v = new AtomicLong();
        public void inc() { v.incrementAndGet(); }
        public void add(long n) { v.addAndGet(n); }
        public long get() { return v.get(); }
    }

    /** Keeps the last N samples (ring buffer) for percentiles. */
    public static final class Latency {
        private final double[] ring;
        private int n;
        private long count;
        private double sum;

        Latency(int size) { ring = new double[size]; }

        public synchronized void record(double ms) {
            ring[(int) (count % ring.length)] = ms;
            count++;
            sum += ms;
            if (n < ring.length) n++;
        }

        public synchronized double percentile(double p) {
            if (n == 0) return 0;
            double[] c = Arrays.copyOf(ring, n);
            Arrays.sort(c);
            int idx = (int) Math.ceil(p / 100.0 * n) - 1;
            return c[Math.max(0, Math.min(n - 1, idx))];
        }

        public synchronized long count() { return count; }
        public synchronized double sum() { return sum; }
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Latency> latencies = new ConcurrentHashMap<>();
    private final Map<String, LongSupplier> gauges = new ConcurrentHashMap<>();

    public Counter counter(String name) { return counters.computeIfAbsent(name, k -> new Counter()); }
    public Latency latency(String name) { return latencies.computeIfAbsent(name, k -> new Latency(20000)); }
    public void gauge(String name, LongSupplier s) { gauges.put(name, s); }

    public String render() {
        List<String> out = new ArrayList<>();
        new TreeMap<>(counters).forEach((k, c) -> out.add(k + " " + c.get()));
        new TreeMap<>(gauges).forEach((k, g) -> out.add(k + " " + g.getAsLong()));
        new TreeMap<>(latencies).forEach((k, l) -> {
            out.add(k + "_count " + l.count());
            out.add(k + "_sum " + String.format("%.3f", l.sum()));
            for (double q : new double[] {50, 95, 99}) {
                out.add(k + "{quantile=\"" + (q / 100) + "\"} " + String.format("%.3f", l.percentile(q)));
            }
        });
        return String.join("\n", out) + "\n";
    }
}
