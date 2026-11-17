package fc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Write-ahead log: one JSON record per line, appended and fsynced before the caller is told "accepted".
 *
 * Two durability modes:
 *   PER_WRITE    every append is its own write + fsync (simple, slow under load)
 *   GROUP_COMMIT a writer thread takes every append that is waiting, writes them together and fsyncs once,
 *                then completes all their futures (same guarantee: nothing is acknowledged before it is on disk)
 *
 * Replay ignores a torn last line (a crash in the middle of a write) and reports how many it dropped.
 */
public final class Wal implements AutoCloseable {
    public enum Mode { PER_WRITE, GROUP_COMMIT }

    private final FileChannel ch;
    private final Mode mode;
    private final LinkedBlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private final Thread writer;
    private volatile boolean closed;
    public final Metrics.Counter fsyncs;

    private record Pending(byte[] bytes, CompletableFuture<Void> done) { }

    public Wal(Path path, Mode mode, Metrics metrics) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        this.ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        this.mode = mode;
        this.fsyncs = metrics.counter("wal_fsyncs_total");
        if (mode == Mode.GROUP_COMMIT) {
            writer = new Thread(this::groupLoop, "wal-writer");
            writer.setDaemon(true);
            writer.start();
        } else {
            writer = null;
        }
    }

    /** Durable append: returns once the record is on disk. */
    public void append(String jsonLine) throws IOException {
        byte[] b = (jsonLine + "\n").getBytes(StandardCharsets.UTF_8);
        if (mode == Mode.PER_WRITE) {
            synchronized (this) {
                writeFully(ByteBuffer.wrap(b));
                ch.force(false);
                fsyncs.inc();
            }
            return;
        }
        CompletableFuture<Void> f = new CompletableFuture<>();
        queue.add(new Pending(b, f));
        try {
            f.get();
        } catch (Exception e) {
            throw new IOException("WAL write failed", e);
        }
    }

    private void groupLoop() {
        List<Pending> batch = new ArrayList<>();
        while (!closed) {
            try {
                Pending first = queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch);
                int total = 0;
                for (Pending p : batch) total += p.bytes.length;
                ByteBuffer buf = ByteBuffer.allocate(total);
                for (Pending p : batch) buf.put(p.bytes);
                buf.flip();
                writeFully(buf);
                ch.force(false);
                fsyncs.inc();
                for (Pending p : batch) p.done.complete(null);
            } catch (Exception e) {
                for (Pending p : batch) p.done.completeExceptionally(e);
            }
            batch.clear();
        }
    }

    private void writeFully(ByteBuffer b) throws IOException {
        while (b.hasRemaining()) ch.write(b);
    }

    /** Replays every complete record; returns the number of torn (incomplete) trailing bytes ignored. */
    public static int replay(Path path, Consumer<String> handler) throws IOException {
        if (!Files.exists(path)) return 0;
        byte[] all = Files.readAllBytes(path);
        int start = 0;
        for (int k = 0; k < all.length; k++) {
            if (all[k] == '\n') {
                if (k > start) handler.accept(new String(all, start, k - start, StandardCharsets.UTF_8));
                start = k + 1;
            }
        }
        return all.length - start;   // bytes after the last newline: a write cut off by a crash
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (writer != null) {
            try { writer.join(1000); } catch (InterruptedException ignored) { }
        }
        ch.close();
    }
}
