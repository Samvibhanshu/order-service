package dev.samridhi.orders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Append-only log of what happened (the "outbox"). Downstream systems (email, warehouse, analytics) read from a
 * sequence number onward instead of being called directly, so a slow or failed consumer never blocks or undoes an
 * order. Sequence numbers start at 1 and have no gaps.
 */
public final class EventLog {
    public record Event(long seq, String type, String orderId, Map<String, Object> data) {}

    private final List<Event> events = new ArrayList<>();

    public synchronized Event append(String type, String orderId, Map<String, Object> data) {
        Event e = new Event(events.size() + 1L, type, orderId, Map.copyOf(data));
        events.add(e);
        return e;
    }

    /** Events with seq &gt; {@code afterSeq}, at most {@code limit} of them. */
    public synchronized List<Event> after(long afterSeq, int limit) {
        int from = (int) Math.max(0, Math.min(afterSeq, events.size()));
        return new ArrayList<>(events.subList(from, Math.min(events.size(), from + Math.max(0, limit))));
    }

    public synchronized int size() {
        return events.size();
    }
}
