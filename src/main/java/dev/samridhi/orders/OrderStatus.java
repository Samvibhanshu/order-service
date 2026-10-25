package dev.samridhi.orders;

import java.util.EnumSet;
import java.util.Set;

/**
 * Order lifecycle. Every allowed move is listed here and nowhere else, so an illegal transition (shipping an unpaid
 * order, cancelling a shipped one) is rejected in one place instead of by scattered if-statements.
 *
 * <pre>
 *   CREATED --pay--> PAID --ship--> SHIPPED --deliver--> DELIVERED
 *      |               |
 *      +----cancel-----+----> CANCELLED      (stock is released; a paid order is refunded)
 * </pre>
 */
public enum OrderStatus {
    CREATED, PAID, SHIPPED, DELIVERED, CANCELLED;

    public Set<OrderStatus> next() {
        return switch (this) {
            case CREATED -> EnumSet.of(PAID, CANCELLED);
            case PAID -> EnumSet.of(SHIPPED, CANCELLED);
            case SHIPPED -> EnumSet.of(DELIVERED);
            case DELIVERED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canMoveTo(OrderStatus to) {
        return next().contains(to);
    }

    public boolean isFinal() {
        return next().isEmpty();
    }
}
