package dev.samridhi.orders;

import static dev.samridhi.orders.Fixtures.lines;
import static dev.samridhi.orders.testkit.Assert.assertEquals;
import static dev.samridhi.orders.testkit.Assert.assertTrue;

import dev.samridhi.orders.testkit.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class IdempotencyTests {
    @Test
    void retryWithTheSameKeyReturnsTheSameOrderAndReservesOnce() {
        Fixtures.World w = new Fixtures.World().stock("A", 10);
        Order first = w.service.placeOrder("key-1", "c1", lines("A", 3));
        Order retry = w.service.placeOrder("key-1", "c1", lines("A", 3));
        assertEquals(first, retry);
        assertEquals(1, w.service.orderCount());
        assertEquals(new Inventory.Level(7, 3, 0), w.inventory.level("A"));
        assertEquals(1, w.events.size());
    }

    @Test
    void theSameRequestWrittenDifferentlyStillMatches() {
        Fixtures.World w = new Fixtures.World().stock("A", 10).stock("B", 10);
        Order a = w.service.placeOrder("k", "c1", lines("A", 1, "B", 2));
        Order b = w.service.placeOrder("k", "c1", lines("B", 2, "A", 1));             // same lines, other order
        assertEquals(a.id(), b.id());
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        Fixtures.World w = new Fixtures.World().stock("A", 10);
        w.service.placeOrder("k", "c1", lines("A", 1));
        OrderException e = StateMachineTests.expectOrderError(() -> w.service.placeOrder("k", "c1", lines("A", 2)));
        assertEquals(OrderException.Code.IDEMPOTENCY_MISMATCH, e.code());
        assertEquals(1, w.service.orderCount());
    }

    @Test
    void aBusinessFailureIsReplayedNotRetried() {
        Fixtures.World w = new Fixtures.World().stock("A", 1);
        assertEquals(OrderException.Code.OUT_OF_STOCK, StateMachineTests.expectOrderError(() -> w.service.placeOrder("k", "c", lines("A", 2))).code());
        w.inventory.addStock("A", 5);                                                 // stock arrives later...
        // ...but the same key still answers with the original outcome, so a client never gets two different answers
        assertEquals(OrderException.Code.OUT_OF_STOCK, StateMachineTests.expectOrderError(() -> w.service.placeOrder("k", "c", lines("A", 2))).code());
        assertEquals(0, w.service.orderCount());
    }

    @Test
    void anUnexpectedCrashIsNotRememberedSoTheRetryRuns() {
        IdempotencyStore<String> store = new IdempotencyStore<>();
        AtomicInteger calls = new AtomicInteger();
        try {
            store.execute("k", "req", () -> {
                calls.incrementAndGet();
                throw new IllegalStateException("database blip");
            });
        } catch (IllegalStateException expected) {
            // fall through
        }
        assertEquals("ok", store.execute("k", "req", () -> {
            calls.incrementAndGet();
            return "ok";
        }));
        assertEquals(2, calls.get());
    }

    @Test
    void simultaneousDuplicatesRunTheActionExactlyOnce() throws Exception {
        for (int round = 0; round < 50; round++) {
            Fixtures.World w = new Fixtures.World().stock("A", 1000);
            ExecutorService pool = Executors.newFixedThreadPool(16);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Order>> fs = new ArrayList<>();
            for (int i = 0; i < 16; i++) fs.add(pool.submit(() -> {
                go.await();
                return w.service.placeOrder("same-key", "c", lines("A", 1));
            }));
            go.countDown();
            Set<String> ids = new HashSet<>();
            for (Future<Order> f : fs) ids.add(f.get(10, TimeUnit.SECONDS).id());
            pool.shutdown();
            assertEquals(1, ids.size());
            assertEquals(1, w.service.orderCount());
            assertEquals(new Inventory.Level(999, 1, 0), w.inventory.level("A"));
        }
    }

    @Test
    void paymentRetriesAreSafeButASecondDifferentPaymentIsRefused() {
        Fixtures.World w = new Fixtures.World().stock("A", 5);
        String id = w.service.placeOrder(null, "c", lines("A", 1)).id();
        Order p1 = w.service.pay("pay-key", id, "pay_123");
        Order p2 = w.service.pay("pay-key", id, "pay_123");                            // network retry
        assertEquals(p1, p2);
        assertEquals(1L, w.events.after(0, 100).stream().filter(e -> e.type().equals("OrderPaid")).count());
        OrderException e = StateMachineTests.expectOrderError(() -> w.service.pay("other-key", id, "pay_999"));
        assertEquals(OrderException.Code.INVALID_STATE, e.code());                     // already paid: no double charge
        assertTrue(w.service.get(id).paymentRef().equals("pay_123"), "payment reference unchanged");
    }
}
