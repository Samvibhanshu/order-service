package dev.samridhi.orders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.CRC32;

/**
 * The write-ahead log: every change to orders and stock is appended here, and made durable with {@code fsync},
 * before the client is told it happened. On start the service replays the log to rebuild its state.
 *
 * <p><b>Format.</b> One record per line: the CRC-32 of the JSON as 8 hex digits, a space, the JSON, a newline.
 * Text so you can read it with {@code less}; the checksum and the newline are how a half-written record is spotted.
 *
 * <p><b>Group commit.</b> Records are written under a lock, which fixes their order; {@code fsync} happens outside it.
 * A thread waiting to sync often finds that another thread's fsync already covered its record, so 16 concurrent
 * requests can share one fsync instead of queueing for 16.
 *
 * <p><b>Recovery</b> keeps the longest prefix of complete, valid records and cuts the file there (the "point in
 * time" policy). A crash can leave a torn last record; anything after the last fsync was never acknowledged, so
 * dropping it loses nothing a client was promised. The file is truncated before new records are appended,
 * otherwise a later recovery would stop at the old garbage and lose the new records behind it.
 *
 * <p><b>Fail-stop.</b> If a write or fsync fails, the journal refuses every later write. After a failed fsync the
 * operating system may have dropped the dirty pages, so retrying could report success for data that is gone.
 */
public final class Journal implements AutoCloseable {

    /** How appends are made durable. Both fsync before the caller is answered; they differ in sharing. */
    public enum Sync {
        /** One fsync per record, inside the lock: the simple version, kept to measure what group commit buys. */
        EVERY_RECORD,
        /** Concurrent appends share fsyncs. The default. */
        GROUP_COMMIT
    }

    /** What recovery found in an existing journal. */
    public record Recovery(List<Map<String, Object>> records, long goodBytes, long discardedBytes, String problem) {
        public boolean clean() {
            return discardedBytes == 0;
        }
    }

    private final Path path;
    private final FileChannel channel;
    private final Sync mode;
    private final Object syncLock = new Object();
    private long written;                       // bytes in the file, guarded by this
    private volatile long synced;               // durable prefix
    private volatile Throwable failure;         // set once, then every write is refused
    private volatile boolean failNextWrite;     // fault injection for tests
    private long records;                       // guarded by this
    private long fsyncs;                        // guarded by syncLock (or this, in EVERY_RECORD mode)

    private Journal() {
        this.path = null;
        this.channel = null;
        this.mode = Sync.GROUP_COMMIT;
    }

    private Journal(Path path, FileChannel channel, Sync mode, long size) {
        this.path = path;
        this.channel = channel;
        this.mode = mode;
        this.written = size;
        this.synced = size;
    }

    /** A journal that keeps nothing: the service then behaves exactly as before, in memory only. */
    public static Journal inMemory() {
        return new Journal();
    }

    /**
     * Reads every valid record, truncates a torn or corrupt tail, and opens the file for appending.
     * The records are returned for the caller to replay.
     */
    public static Opened open(Path path, Sync mode) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            boolean created = !Files.exists(path);
            Recovery recovery = recover(path);
            FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            if (ch.size() != recovery.goodBytes()) {        // cut the tail before anything new is appended after it
                ch.truncate(recovery.goodBytes());
                ch.force(true);
            }
            if (created) syncDirectory(parent);              // make the new file's directory entry durable too
            return new Opened(new Journal(path, ch, mode, recovery.goodBytes()), recovery);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open journal " + path, e);
        }
    }

    public record Opened(Journal journal, Recovery recovery) {}

    /** Reads the longest valid prefix of a journal file without changing it. */
    public static Recovery recover(Path path) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.exists(path)) return new Recovery(out, 0, 0, null);
        long size = Files.size(path);
        long good = 0;
        String problem = null;
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[1 << 16];
            ByteArrayOutputStream line = new ByteArrayOutputStream(256);
            long offset = 0;                                  // bytes consumed so far
            int n;
            scan:
            while ((n = in.read(buf)) > 0) {
                int from = 0;
                for (int i = 0; i < n; i++) {
                    if (buf[i] != '\n') continue;
                    line.write(buf, from, i - from);
                    Map<String, Object> rec = decode(line.toByteArray());
                    if (rec == null) {
                        problem = "invalid record at byte " + good;
                        break scan;
                    }
                    out.add(rec);
                    good = offset + i + 1;
                    line.reset();
                    from = i + 1;
                }
                line.write(buf, from, n - from);
                offset += n;
            }
            if (problem == null && good < size) problem = "incomplete last record at byte " + good;
        }
        return new Recovery(out, good, size - good, problem);
    }

    /** Parses one line (without its newline); null if the checksum, the shape or the JSON is wrong. */
    static Map<String, Object> decode(byte[] line) {
        if (line.length < 10 || line[8] != ' ') return null;
        long expected;
        try {
            expected = Long.parseLong(new String(line, 0, 8, StandardCharsets.US_ASCII), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        CRC32 crc = new CRC32();
        crc.update(line, 9, line.length - 9);
        if (crc.getValue() != expected) return null;
        try {
            Object v = Json.parse(new String(line, 9, line.length - 9, StandardCharsets.UTF_8));
            if (!(v instanceof Map<?, ?> m) || !(m.get("t") instanceof String)) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> rec = (Map<String, Object>) m;
            return rec;
        } catch (OrderException e) {
            return null;
        }
    }

    static byte[] encode(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(body);
        byte[] head = String.format("%08x ", crc.getValue()).getBytes(StandardCharsets.US_ASCII);
        byte[] line = new byte[head.length + body.length + 1];
        System.arraycopy(head, 0, line, 0, head.length);
        System.arraycopy(body, 0, line, head.length, body.length);
        line[line.length - 1] = '\n';
        return line;
    }

    /**
     * Appends one record. {@code render} produces its JSON and {@code afterWrite} runs once it is written; both run
     * under the journal's lock, so whatever {@code afterWrite} does (appending to the event log) happens in exactly
     * the order of the records. Returns the end offset to pass to {@link #syncTo}. Nothing runs if the write fails.
     */
    public long append(Supplier<String> render, Runnable afterWrite) {
        if (channel == null) {
            synchronized (this) {
                afterWrite.run();
            }
            return 0;
        }
        synchronized (this) {
            checkHealthy();
            ByteBuffer buf = ByteBuffer.wrap(encode(render.get()));
            try {
                if (failNextWrite) {
                    failNextWrite = false;
                    throw new IOException("injected write failure");
                }
                while (buf.hasRemaining()) written += channel.write(buf, written);
                if (mode == Sync.EVERY_RECORD) {
                    channel.force(false);
                    fsyncs++;
                    synced = written;
                }
            } catch (IOException e) {
                fail(e);
            }
            records++;
            afterWrite.run();
            return written;
        }
    }

    /** Returns once every byte up to {@code end} is on disk. Shares fsyncs between concurrent callers. */
    public void syncTo(long end) {
        if (channel == null || synced >= end) return;
        synchronized (syncLock) {
            if (synced >= end) return;                    // another thread's fsync already covered this record
            checkHealthy();
            long target;
            synchronized (this) {
                target = written;
            }
            try {
                channel.force(false);
            } catch (IOException e) {
                fail(e);
            }
            fsyncs++;
            synced = target;
        }
    }

    private void fail(IOException e) {
        failure = e;
        throw new OrderException(OrderException.Code.UNAVAILABLE, "storage failed; this server no longer accepts changes");
    }

    private void checkHealthy() {
        if (failure != null) {
            throw new OrderException(OrderException.Code.UNAVAILABLE, "storage failed earlier; this server no longer accepts changes");
        }
    }

    public boolean durable() {
        return channel != null;
    }

    public boolean healthy() {
        return failure == null;
    }

    public Path path() {
        return path;
    }

    public synchronized long records() {
        return records;
    }

    public long fsyncs() {
        synchronized (syncLock) {
            synchronized (this) {
                return fsyncs;
            }
        }
    }

    /** Test hook: the next write throws an IOException, as a full disk would. */
    void failNextWrite() {
        failNextWrite = true;
    }

    @Override
    public void close() {
        if (channel == null) return;
        try {
            if (failure == null) channel.force(false);
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void syncDirectory(Path dir) {
        try (FileChannel d = FileChannel.open(dir, StandardOpenOption.READ)) {
            d.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // Not possible on some platforms (Windows); the file's own data is still synced on every commit.
        }
    }
}
