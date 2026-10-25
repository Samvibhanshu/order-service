package dev.samridhi.orders;

import static dev.samridhi.orders.Fixtures.lines;
import static dev.samridhi.orders.testkit.Assert.assertEquals;
import static dev.samridhi.orders.testkit.Assert.assertThrows;
import static dev.samridhi.orders.testkit.Assert.assertTrue;

import dev.samridhi.orders.testkit.Test;
import java.util.EnumSet;
import java.util.Set;

public class StateMachineTests {
    @Test
    void exactlyTheDocumentedTransitionsAreAllowed() {
        Set<String> allowed = Set.of("CREATED>PAID", "CREATED>CANCELLED", "PAID>SHIPPED", "PAID>CANCELLED", "SHIPPED>DELIVERED");
        for (OrderStatus from : OrderStatus.values())
            for (OrderStatus to : OrderStatus.values())
                assertEquals(allowed.contains(from + ">" + to), from.canMoveTo(to));
        assertEquals(EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED),
                EnumSet.copyOf(java.util.Arrays.stream(OrderStatus.values()).filter(OrderStatus::isFinal).toList()));
    }

    @Test
    void happyPathWalksTheLifecycleAndBumpsTheVersion() {
        Fixtures.World w = new Fixtures.World().stock("A", 5);
        Order o = w.service.placeOrder(null, "c1", lines("A", 2));
        assertEquals(OrderStatus.CREATED, o.status());
        assertEquals(200L, o.totalCents());
        o = w.service.pay(null, o.id(), "pay_1");
        assertEquals("pay_1", o.paymentRef());
        o = w.service.ship(o.id());
        o = w.service.deliver(o.id());
        assertEquals(OrderStatus.DELIVERED, o.status());
        assertEquals(4L, o.version());
        assertEquals(new Inventory.Level(3, 0, 2), w.inventory.level("A"));
    }

    @Test
    void illegalMovesAreRejectedAndChangeNothing() {
        Fixtures.World w = new Fixtures.World().stock("A", 5);
        String id = w.service.placeOrder(null, "c1", lines("A", 1)).id();
        assertThrows(OrderException.class, () -> w.service.ship(id));                 // not paid
        assertThrows(OrderException.class, () -> w.service.deliver(id));
        w.service.pay(null, id, "p");
        w.service.ship(id);
        int eventsBefore = w.events.size();
        OrderException e = expectOrderError(() -> w.service.cancel(id));             // shipped: too late
        assertEquals(OrderException.Code.INVALID_STATE, e.code());
        assertEquals(OrderStatus.SHIPPED, w.service.get(id).status());
        assertEquals(eventsBefore, w.events.size());                                  // no event for a refused move
        assertEquals(new Inventory.Level(4, 0, 1), w.inventory.level("A"));            // stock untouched
    }

    @Test
    void cancellingReleasesStockAndFlagsRefundOnlyIfPaid() {
        Fixtures.World w = new Fixtures.World().stock("A", 5);
        String unpaid = w.service.placeOrder(null, "c1", lines("A", 2)).id();
        String paid = w.service.placeOrder(null, "c2", lines("A", 3)).id();
        assertEquals(new Inventory.Level(0, 5, 0), w.inventory.level("A"));
        w.service.pay(null, paid, "p");
        w.service.cancel(unpaid);
        w.service.cancel(paid);
        assertEquals(new Inventory.Level(5, 0, 0), w.inventory.level("A"));
        var cancels = w.events.after(0, 100).stream().filter(ev -> ev.type().equals("OrderCancelled")).toList();
        assertEquals(false, cancels.get(0).data().get("refund"));
        assertEquals(true, cancels.get(1).data().get("refund"));
    }

    @Test
    void unknownOrdersAreNotFound() {
        Fixtures.World w = new Fixtures.World();
        assertEquals(OrderException.Code.NOT_FOUND, expectOrderError(() -> w.service.ship("ord_404")).code());
        assertEquals(OrderException.Code.NOT_FOUND, expectOrderError(() -> w.service.get("ord_404")).code());
    }

    @Test
    void validationRejectsBadOrdersBeforeTouchingStock() {
        Fixtures.World w = new Fixtures.World().stock("A", 5);
        Runnable[] bad = {
            () -> w.service.placeOrder(null, "", lines("A", 1)),
            () -> w.service.placeOrder(null, "c", lines()),
            () -> w.service.placeOrder(null, "c", lines("ZZZ", 1)),
            () -> w.service.placeOrder(null, "c", lines("A", 0)),
            () -> w.service.placeOrder(null, "c", lines("A", -3)),
            () -> w.service.placeOrder(null, "c", lines("A", 600, "A", 600)),        // merged quantity over the limit
        };
        for (Runnable r : bad) assertEquals(OrderException.Code.VALIDATION, expectOrderError(r::run).code());
        assertEquals(new Inventory.Level(5, 0, 0), w.inventory.level("A"));
        assertEquals(0, w.service.orderCount());
    }

    @Test
    void repeatedSkusAreMergedIntoOneLine() {
        Fixtures.World w = new Fixtures.World().stock("A", 5).stock("B", 5);
        Order o = w.service.placeOrder(null, "c", lines("B", 1, "A", 1, "A", 2));
        assertEquals(2, o.lines().size());
        assertEquals(new OrderLine("A", 3), o.lines().get(0));
        assertEquals(550L, o.totalCents());
    }

    @Test
    void outOfStockIsAllOrNothing() {
        Fixtures.World w = new Fixtures.World().stock("A", 5).stock("B", 1);
        assertEquals(OrderException.Code.OUT_OF_STOCK, expectOrderError(() -> w.service.placeOrder(null, "c", lines("A", 2, "B", 2))).code());
        assertEquals(new Inventory.Level(5, 0, 0), w.inventory.level("A"));           // A was not partially reserved
        assertTrue(w.events.size() == 0, "no event for a failed order");
    }

    static OrderException expectOrderError(Runnable r) {
        try {
            r.run();
        } catch (OrderException e) {
            return e;
        }
        throw new AssertionError("expected an OrderException");
    }
}
