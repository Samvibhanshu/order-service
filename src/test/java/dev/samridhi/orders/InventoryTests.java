package dev.samridhi.orders;

import static dev.samridhi.orders.testkit.Assert.assertEquals;
import static dev.samridhi.orders.testkit.Assert.assertFalse;
import static dev.samridhi.orders.testkit.Assert.assertTrue;

import dev.samridhi.orders.testkit.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Inventory used directly, without OrderService's input clean-up, so its own guarantees are tested. */
public class InventoryTests {
    private static Map<String, Long> units(Object... skuQty) {
        Map<String, Long> m = new LinkedHashMap<>();                    // keeps the caller's order on purpose
        for (int i = 0; i < skuQty.length; i += 2) m.put((String) skuQty[i], ((Number) skuQty[i + 1]).longValue());
        return m;
    }

    @Test
    void releaseAndCommitTakeEffectExactlyOnce() {
        Inventory inv = new Inventory();
        inv.addStock("A", 10);
        assertTrue(inv.reserve("r1", units("A", 4)));
        inv.release("r1");
        inv.release("r1");                                             // duplicate message from a retry
        inv.commit("r1");                                              // and a stray commit
        assertEquals(new Inventory.Level(10, 0, 0), inv.level("A"));
        assertTrue(inv.reserve("r2", units("A", 4)));
        inv.commit("r2");
        inv.commit("r2");
        inv.release("r2");
        assertEquals(new Inventory.Level(6, 0, 4), inv.level("A"));
    }

    @Test
    void concurrentReleasesOfTheSameReservationCountOnce() throws Exception {
        for (int round = 0; round < 200; round++) {
            Inventory inv = new Inventory();
            inv.addStock("A", 5);
            inv.reserve("r", units("A", 5));
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 8; i++) fs.add(pool.submit(() -> {
                go.await();
                inv.release("r");
                return null;
            }));
            go.countDown();
            for (Future<?> f : fs) f.get(10, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(new Inventory.Level(5, 0, 0), inv.level("A"));
        }
    }

    @Test
    void reservingInOppositeOrdersCannotDeadlock() throws Exception {
        Inventory inv = new Inventory();
        for (String s : List.of("A", "B", "C", "D")) inv.addStock(s, 10_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger ids = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            final boolean forward = t % 2 == 0;
            fs.add(pool.submit(() -> {
                for (int i = 0; i < 20_000; i++) {
                    Map<String, Long> u = forward ? units("A", 1, "B", 1, "C", 1, "D", 1) : units("D", 1, "C", 1, "B", 1, "A", 1);
                    String id = "r" + ids.incrementAndGet();
                    inv.reserve(id, u);
                    inv.release(id);
                }
                return null;
            }));
        }
        try {
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);       // with unordered locking this hangs
        } finally {
            pool.shutdownNow();
        }
        assertEquals(new Inventory.Level(10_000_000, 0, 0), inv.level("A"));
    }

    @Test
    void failedReservationChangesNothing() {
        Inventory inv = new Inventory();
        inv.addStock("A", 3);
        inv.addStock("B", 1);
        assertFalse(inv.reserve("r", units("A", 2, "B", 5)));
        assertFalse(inv.reserve("r2", units("A", 1, "UNKNOWN", 1)));
        assertEquals(new Inventory.Level(3, 0, 0), inv.level("A"));
        assertEquals(new Inventory.Level(1, 0, 0), inv.level("B"));
    }
}
