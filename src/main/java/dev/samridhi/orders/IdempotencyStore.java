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
 */
public final class IdempotencyStore<T> {
    private record Entry<T>(String fingerprint, CompletableFuture<T> result) {}

    private final ConcurrentHashMap<String, Entry<T>> entries = new ConcurrentHashMap<>();

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
        } catch (OrderException e) {                 // deterministic business outcome: replay it on retry
            mine.result().completeExceptionally(e);
            throw e;
        } catch (RuntimeException e) {               // unexpected failure: forget the key so a retry runs again
            entries.remove(key, mine);
            mine.result().completeExceptionally(e);
            throw e;
        }
    }

    public int size() {
        return entries.size();
    }
}
