package dev.samridhi.orders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 */
public final class OrderService {
    public static final int MAX_LINES = 50;
    public static final long MAX_QUANTITY = 1_000;

    private final Catalog catalog;
    private final Inventory inventory;
    private final EventLog events;
    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();
    private final IdempotencyStore<Order> placeRequests = new IdempotencyStore<>();
    private final IdempotencyStore<Order> payRequests = new IdempotencyStore<>();
    private final AtomicLong nextId = new AtomicLong(1);

    public OrderService(Catalog catalog, Inventory inventory, EventLog events) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.events = events;
    }

    /** Places an order, reserving all its stock or none of it. Safe to retry with the same idempotency key. */
    public Order placeOrder(String idempotencyKey, String customerId, List<OrderLine> lines) {
        Map<String, Long> units = validate(customerId, lines);
        String fingerprint = customerId + "|" + units;                     // units is sorted: same request, same text
        return placeRequests.execute(idempotencyKey, fingerprint, () -> doPlace(customerId, units));
    }

    private Order doPlace(String customerId, Map<String, Long> units) {
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
        orders.compute(id, (k, none) -> {
            events.append("OrderPlaced", id, Map.of("customerId", customerId, "totalCents", order.totalCents()));
            return order;
        });
        return order;
    }

    private Map<String, Long> validate(String customerId, List<OrderLine> lines) {
        if (customerId == null || customerId.isBlank()) throw new OrderException(VALIDATION, "customerId is required");
        if (lines == null || lines.isEmpty()) throw new OrderException(VALIDATION, "an order needs at least one line");
        if (lines.size() > MAX_LINES) throw new OrderException(VALIDATION, "at most " + MAX_LINES + " lines per order");
        Map<String, Long> units = new java.util.TreeMap<>();               // merges repeated SKUs, sorted
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
        return payRequests.execute(idempotencyKey, orderId + "|" + paymentRef,
                () -> transition(orderId, OrderStatus.PAID, paymentRef, "OrderPaid", Map.of("paymentRef", paymentRef)));
    }

    public Order ship(String orderId) {
        return transition(orderId, OrderStatus.SHIPPED, null, "OrderShipped", Map.of());
    }

    public Order deliver(String orderId) {
        return transition(orderId, OrderStatus.DELIVERED, null, "OrderDelivered", Map.of());
    }

    public Order cancel(String orderId) {
        return transition(orderId, OrderStatus.CANCELLED, null, "OrderCancelled", Map.of());
    }

    private Order transition(String orderId, OrderStatus to, String paymentRef, String eventType, Map<String, Object> data) {
        Order updated = orders.computeIfPresent(orderId, (id, current) -> {
            Order next = current.moveTo(to, paymentRef != null ? paymentRef : current.paymentRef());
            if (to == OrderStatus.SHIPPED) inventory.commit(id);           // reserved units are now sold
            if (to == OrderStatus.CANCELLED) inventory.release(id);        // back on the shelf
            Map<String, Object> eventData = new LinkedHashMap<>(data);
            if (to == OrderStatus.CANCELLED) eventData.put("refund", current.status() == OrderStatus.PAID);
            events.append(eventType, id, eventData);
            return next;
        });
        if (updated == null) throw new OrderException(NOT_FOUND, "no order " + orderId);
        return updated;
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
}
