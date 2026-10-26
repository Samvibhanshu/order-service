package dev.samridhi.orders;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Makes retries safe. A client that times out does not know whether its "place order" or "pay" request ran; it
 * retries with the same Idempotency-Key and must get the original result, not a second order or a second charge.
 *
 * <ul>
 *   <li>First request with a key runs the action and stores its result (or its business error).</li>
 *   <li>A repeat with the same key and the same request returns that stored result without running anything.</li>
 *   <li>Two identical requests arriving at the same moment: {@code putIfAbsent} lets exactly one run; the other
 *       waits for its result.</li>
 *   <li>The same key with a <em>different</em> request is a client bug and is rejected.</li>
 *   <li>An unexpected crash (not a business error) is not stored, so the client can retry it.</li>
 * </ul>
 *
 * <p><b>Durability.</b> A successful result is written to the journal by the action itself, in the same record as the
 * change it made (an order and its key are durable together, or not at all). A business error has no change to ride
 * on, so {@link ErrorRecorder} journals it before the client sees it. On restart, {@link #restore} and
 * {@link #restoreError} put the answers back.
 */
public final class IdempotencyStore<T> {
    private record Entry<T>(String fingerprint, CompletableFuture<T> result) {}

    /** Called with a business error before it is returned, so it can be made durable. */
    public interface ErrorRecorder {
        void record(String key, String fingerprint, OrderException error);
    }

    private final ConcurrentHashMap<String, Entry<T>> entries = new ConcurrentHashMap<>();
    private final ErrorRecorder errors;

    public IdempotencyStore() {
        this((k, f, e) -> {});
    }

    public IdempotencyStore(ErrorRecorder errors) {
        this.errors = errors;
    }

    public T execute(String key, String fingerprint, Supplier<T> action) {
        if (key == null || key.isBlank()) return action.get();               // no key: no protection asked for
        Entry<T> mine = new Entry<>(fingerprint, new CompletableFuture<>());
        Entry<T> existing = entries.putIfAbsent(key, mine);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new OrderException(OrderException.Code.IDEMPOTENCY_MISMATCH,
                        "Idempotency-Key " + key + " was already used for a different request");
            }
            try {
                return existing.result().join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException r) throw r;
                throw e;
            }
        }
        try {
            T value = action.get();
            mine.result().complete(value);
            return value;
        } catch (OrderException e) {
            if (e.transientFailure()) throw forget(key, mine, e);  // storage trouble says nothing about the request
            try {
                errors.record(key, fingerprint, e);                   // deterministic business outcome: replay it on retry
            } catch (RuntimeException notRecorded) {
                throw forget(key, mine, notRecorded);
            }
            mine.result().completeExceptionally(e);
            throw e;
        } catch (RuntimeException e) {               // unexpected failure: forget the key so a retry runs again
            throw forget(key, mine, e);
        }
    }

    private RuntimeException forget(String key, Entry<T> mine, RuntimeException e) {
        entries.remove(key, mine);
        mine.result().completeExceptionally(e);
        return e;
    }

    /** Replay: the request with this key succeeded with {@code value}. */
    public void restore(String key, String fingerprint, T value) {
        entries.put(key, new Entry<>(fingerprint, CompletableFuture.completedFuture(value)));
    }

    /** Replay: the request with this key failed with a business error. */
    public void restoreError(String key, String fingerprint, OrderException error) {
        entries.put(key, new Entry<>(fingerprint, CompletableFuture.failedFuture(error)));
    }

    public int size() {
        return entries.size();
    }
}
