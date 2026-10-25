package dev.samridhi.orders;

import java.util.List;

/**
 * Immutable snapshot of an order. State changes create a new Order; the service swaps snapshots atomically, so a
 * reader can never observe a half-updated order.
 */
public record Order(String id, String customerId, List<OrderLine> lines, long totalCents, OrderStatus status,
                    String paymentRef, long version) {

    public Order {
        lines = List.copyOf(lines);
    }

    Order moveTo(OrderStatus to, String newPaymentRef) {
        if (!status.canMoveTo(to)) {
            throw new OrderException(OrderException.Code.INVALID_STATE,
                    "order " + id + " is " + status + " and cannot become " + to);
        }
        return new Order(id, customerId, lines, totalCents, to, newPaymentRef, version + 1);
    }
}
