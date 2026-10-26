package dev.samridhi.orders;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static dev.samridhi.orders.OrderException.Code.NOT_FOUND;
import static dev.samridhi.orders.OrderException.Code.OUT_OF_STOCK;
import static dev.samridhi.orders.OrderException.Code.VALIDATION;

/**
 * The order workflow: place (reserve stock), pay, ship (stock becomes sold), deliver, cancel (stock released).
 *
 * <p>Each state change runs inside {@code ConcurrentHashMap.compute} for that order id, which is atomic per key:
 * two threads cancelling and shipping the same order at once are serialised, and exactly one of them wins. The event
 * for a change is appended inside the same step, so the event log's order always matches the real order of changes.
 *
 * <p><b>Durability.</b> With a file-backed {@link Journal}, every change is written to the journal inside that same
 * step, before the new state is visible, and fsynced before the caller gets an answer. A change that cannot be
 * written is not applied. {@link #open} rebuilds the whole state (orders, stock, reservations, events and stored
 * idempotency answers) by replaying the journal.
 */
public final class OrderService {
    public static final int MAX_LINES = 50;
    public static final long MAX_QUANTITY = 1_000;

    private final Catalog catalog;
    private final Inventory inventory;
    private final EventLog events;
    private final Journal journal;
    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();
    private final IdempotencyStore<Order> placeRequests;
    private final IdempotencyStore<Order> payRequests;
    private final AtomicLong nextId = new AtomicLong(1);

    /** In memory only: nothing survives a restart. */
    public OrderService(Catalog catalog, Inventory inventory, EventLog events) {
        this(catalog, inventory, events, Journal.inMemory());
    }

    public OrderService(Catalog catalog, Inventory inventory, EventLog events, Journal journal) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.events = events;
        this.journal = journal;
        this.placeRequests = new IdempotencyStore<>((k, f, e) -> recordError("place", k, f, e));
        this.payRequests = new IdempotencyStore<>((k, f, e) -> recordError("pay", k, f, e));
    }

    /** A service backed by a journal file, its state rebuilt from what the file already holds. */
    public record Durable(OrderService service, Journal journal, Journal.Recovery recovery, long replayMillis) {}

    public static Durable open(Catalog catalog, Inventory inventory, EventLog events, Path file, Journal.Sync sync) {
        Journal.Opened opened = Journal.open(file, sync);
        OrderService service = new OrderService(catalog, inventory, events, opened.journal());
        long t0 = System.nanoTime();
        service.replay(opened.recovery().records());
        return new Durable(service, opened.journal(), opened.recovery(), (System.nanoTime() - t0) / 1_000_000);
    }

    // ------------------------------------------------------------------------------------------------ stock

    /** Adds units to a SKU's stock, durably. */
    public void addStock(String sku, long units) {
        if (sku == null || sku.isBlank()) throw new OrderException(VALIDATION, "sku is required");
        if (units <= 0) throw new OrderException(VALIDATION, "units must be positive");
        Map<String, Object> rec = record("stock");
        rec.put("sku", sku);
        rec.put("units", units);
        long end = log(rec, null, null, null);
        inventory.addStock(sku, units);
        journal.syncTo(end);
    }

    // ------------------------------------------------------------------------------------------------ orders

    /** Places an order, reserving all its stock or none of it. Safe to retry with the same idempotency key. */
    public Order placeOrder(String idempotencyKey, String customerId, List<OrderLine> lines) {
        Map<String, Long> units = validate(customerId, lines);
        String fingerprint = customerId + "|" + units;                     // units is sorted: same request, same text
        String key = blank(idempotencyKey) ? null : idempotencyKey;
        return placeRequests.execute(key, fingerprint, () -> doPlace(customerId, units, key, fingerprint));
    }

    private Order doPlace(String customerId, Map<String, Long> units, String key, String fingerprint) {
        long total = 0;                                                    // priced BEFORE reserving: an error here
        List<OrderLine> lines = new ArrayList<>();                         // must not leave stock held by nobody
        for (Map.Entry<String, Long> e : units.entrySet()) {
            try {
                total = Math.addExact(total, Math.multiplyExact(catalog.price(e.getKey()), e.getValue()));
            } catch (ArithmeticException overflow) {
                throw new OrderException(VALIDATION, "order total too large");
            }
            lines.add(new OrderLine(e.getKey(), e.getValue()));
        }
        String id = "ord_" + nextId.getAndIncrement();
        if (!inventory.reserve(id, units)) {
            throw new OrderException(OUT_OF_STOCK, "not enough stock for " + units);
        }
        Order order = new Order(id, customerId, lines, total, OrderStatus.CREATED, null, 1);
        long[] end = {0};
        try {
            orders.compute(id, (k, none) -> {
                Map<String, Object> rec = record("place");
                rec.put("id", id);
                rec.put("customerId", customerId);
                rec.put("lines", linesJson(lines));
                rec.put("totalCents", order.totalCents());
                withKey(rec, key, fingerprint);
                end[0] = log(rec, "OrderPlaced", id, placedData(order));
                return order;
            });
        } catch (RuntimeException notWritten) {                            // not in the journal: hold no stock for it
            inventory.release(id);
            throw notWritten;
        }
        journal.syncTo(end[0]);
        return order;
    }

    private Map<String, Long> validate(String customerId, List<OrderLine> lines) {
        if (customerId == null || customerId.isBlank()) throw new OrderException(VALIDATION, "customerId is required");
        if (lines == null || lines.isEmpty()) throw new OrderException(VALIDATION, "an order needs at least one line");
        if (lines.size() > MAX_LINES) throw new OrderException(VALIDATION, "at most " + MAX_LINES + " lines per order");
        Map<String, Long> units = new TreeMap<>();                         // merges repeated SKUs, sorted
        for (OrderLine l : lines) {
            if (l == null || l.sku() == null || !catalog.contains(l.sku())) {
                throw new OrderException(VALIDATION, "unknown sku: " + (l == null ? null : l.sku()));
            }
            if (l.quantity() <= 0 || l.quantity() > MAX_QUANTITY) {
                throw new OrderException(VALIDATION, "quantity must be 1.." + MAX_QUANTITY + " for " + l.sku());
            }
            units.merge(l.sku(), l.quantity(), Long::sum);
        }
        for (Map.Entry<String, Long> e : units.entrySet()) {
            if (e.getValue() > MAX_QUANTITY) throw new OrderException(VALIDATION, "quantity must be 1.." + MAX_QUANTITY + " for " + e.getKey());
        }
        return units;
    }

    /** Records payment. A retry with the same key returns the same order; a second, different payment is refused. */
    public Order pay(String idempotencyKey, String orderId, String paymentRef) {
        if (paymentRef == null || paymentRef.isBlank()) throw new OrderException(VALIDATION, "paymentRef is required");
        String key = blank(idempotencyKey) ? null : idempotencyKey;
        String fingerprint = orderId + "|" + paymentRef;
        return payRequests.execute(key, fingerprint, () -> transition(orderId, OrderStatus.PAID, paymentRef, key, fingerprint));
    }

    public Order ship(String orderId) {
        return transition(orderId, OrderStatus.SHIPPED, null, null, null);
    }

    public Order deliver(String orderId) {
        return transition(orderId, OrderStatus.DELIVERED, null, null, null);
    }

    public Order cancel(String orderId) {
        return transition(orderId, OrderStatus.CANCELLED, null, null, null);
    }

    private Order transition(String orderId, OrderStatus to, String paymentRef, String key, String fingerprint) {
        long[] end = {0};
        Order updated = orders.computeIfPresent(orderId, (id, current) -> {
            Order next = current.moveTo(to, paymentRef != null ? paymentRef : current.paymentRef());
            boolean refund = to == OrderStatus.CANCELLED && current.status() == OrderStatus.PAID;
            Map<String, Object> rec = record("move");
            rec.put("id", id);
            rec.put("to", to.name());
            if (paymentRef != null) rec.put("paymentRef", paymentRef);
            withKey(rec, key, fingerprint);
            end[0] = log(rec, eventType(to), id, eventData(to, paymentRef, refund));   // throws if not written
            settleStock(id, to);
            return next;
        });
        if (updated == null) throw new OrderException(NOT_FOUND, "no order " + orderId);
        journal.syncTo(end[0]);
        return updated;
    }

    private void settleStock(String id, OrderStatus to) {
        if (to == OrderStatus.SHIPPED) inventory.commit(id);              // reserved units are now sold
        if (to == OrderStatus.CANCELLED) inventory.release(id);           // back on the shelf
    }

    public Optional<Order> find(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    public Order get(String orderId) {
        return find(orderId).orElseThrow(() -> new OrderException(NOT_FOUND, "no order " + orderId));
    }

    public int orderCount() {
        return orders.size();
    }

    /** Every order, sorted by id; for tests and the crash checker. */
    public List<Order> allOrders() {
        List<Order> out = new ArrayList<>(orders.values());
        out.sort((a, b) -> Long.compare(idNumber(a.id()), idNumber(b.id())));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ journal

    /**
     * Appends a record; the event (if any) is appended to the event log under the journal's lock, so events and
     * records are in the same order. The record carries the event's sequence number so replay can check it.
     */
    private long log(Map<String, Object> rec, String eventType, String orderId, Map<String, Object> eventData) {
        return journal.append(() -> {
            if (eventType != null) rec.put("seq", events.size() + 1L);
            return Json.write(rec);
        }, () -> {
            if (eventType != null) events.append(eventType, orderId, eventData);
        });
    }

    private void recordError(String store, String key, String fingerprint, OrderException e) {
        if (!journal.durable()) return;
        Map<String, Object> rec = record("idem-error");
        rec.put("store", store);
        withKey(rec, key, fingerprint);
        rec.put("code", e.code().name());
        rec.put("message", e.getMessage());
        journal.syncTo(log(rec, null, null, null));
    }

    private void replay(List<Map<String, Object>> records) {
        long maxId = 0;
        for (Map<String, Object> r : records) {
            String type = (String) r.get("t");
            switch (type) {
                case "stock" -> inventory.addStock(str(r, "sku"), num(r, "units"));
                case "place" -> {
                    String id = str(r, "id");
                    List<OrderLine> lines = new ArrayList<>();
                    Map<String, Long> units = new TreeMap<>();
                    for (Object o : (List<?>) r.get("lines")) {
                        Map<?, ?> l = (Map<?, ?>) o;
                        lines.add(new OrderLine((String) l.get("sku"), ((Number) l.get("quantity")).longValue()));
                        units.merge((String) l.get("sku"), ((Number) l.get("quantity")).longValue(), Long::sum);
                    }
                    Order order = new Order(id, str(r, "customerId"), lines, num(r, "totalCents"), OrderStatus.CREATED, null, 1);
                    if (orders.containsKey(id) || !inventory.reserve(id, units)) {
                        throw new IllegalStateException("journal is inconsistent at place " + id);
                    }
                    orders.put(id, order);
                    replayEvent(r, "OrderPlaced", id, placedData(order));
                    if (r.containsKey("key")) placeRequests.restore(str(r, "key"), str(r, "fp"), order);
                    maxId = Math.max(maxId, idNumber(id));
                }
                case "move" -> {
                    String id = str(r, "id");
                    Order current = orders.get(id);
                    if (current == null) throw new IllegalStateException("journal moves unknown order " + id);
                    OrderStatus to = OrderStatus.valueOf(str(r, "to"));
                    String paymentRef = (String) r.get("paymentRef");
                    Order next = current.moveTo(to, paymentRef != null ? paymentRef : current.paymentRef());
                    boolean refund = to == OrderStatus.CANCELLED && current.status() == OrderStatus.PAID;
                    orders.put(id, next);
                    settleStock(id, to);
                    replayEvent(r, eventType(to), id, eventData(to, paymentRef, refund));
                    if (r.containsKey("key")) payRequests.restore(str(r, "key"), str(r, "fp"), next);
                }
                case "idem-error" -> {
                    OrderException e = new OrderException(OrderException.Code.valueOf(str(r, "code")), str(r, "message"));
                    IdempotencyStore<Order> store = "place".equals(r.get("store")) ? placeRequests : payRequests;
                    store.restoreError(str(r, "key"), str(r, "fp"), e);
                }
                default -> throw new IllegalStateException("unknown journal record type " + type);
            }
        }
        nextId.set(maxId + 1);
    }

    private void replayEvent(Map<String, Object> r, String type, String id, Map<String, Object> data) {
        EventLog.Event e = events.append(type, id, data);
        if (e.seq() != num(r, "seq")) {
            throw new IllegalStateException("journal event order differs at " + id + ": " + e.seq() + " vs " + r.get("seq"));
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static Map<String, Object> record(String type) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("t", type);
        return rec;
    }

    private static void withKey(Map<String, Object> rec, String key, String fingerprint) {
        if (key == null) return;
        rec.put("key", key);
        rec.put("fp", fingerprint);
    }

    private static List<Object> linesJson(List<OrderLine> lines) {
        List<Object> out = new ArrayList<>();
        for (OrderLine l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sku", l.sku());
            m.put("quantity", l.quantity());
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> placedData(Order o) {
        return Map.of("customerId", o.customerId(), "totalCents", o.totalCents());
    }

    private static String eventType(OrderStatus to) {
        return switch (to) {
            case PAID -> "OrderPaid";
            case SHIPPED -> "OrderShipped";
            case DELIVERED -> "OrderDelivered";
            case CANCELLED -> "OrderCancelled";
            case CREATED -> throw new IllegalArgumentException("no transition to CREATED");
        };
    }

    private static Map<String, Object> eventData(OrderStatus to, String paymentRef, boolean refund) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (to == OrderStatus.PAID) data.put("paymentRef", paymentRef);
        if (to == OrderStatus.CANCELLED) data.put("refund", refund);
        return data;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Map<String, Object> r, String k) {
        Object v = r.get(k);
        if (!(v instanceof String s)) throw new IllegalStateException("journal record without " + k + ": " + r);
        return s;
    }

    private static long num(Map<String, Object> r, String k) {
        Object v = r.get(k);
        if (!(v instanceof Number n)) throw new IllegalStateException("journal record without " + k + ": " + r);
        return n.longValue();
    }

    private static long idNumber(String id) {
        try {
            return Long.parseLong(id.substring(id.indexOf('_') + 1));
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
