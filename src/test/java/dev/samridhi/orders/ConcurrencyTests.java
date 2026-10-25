package dev.samridhi.orders;

import static dev.samridhi.orders.Fixtures.lines;
import static dev.samridhi.orders.testkit.Assert.assertEquals;
import static dev.samridhi.orders.testkit.Assert.assertTrue;

import dev.samridhi.orders.testkit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Many threads, released at the same instant, hammering the same few products. */
public class ConcurrencyTests {
    private static <T> List<T> race(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) fs.add(pool.submit(() -> {
            start.await();
            return task.call();
        }));
        start.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : fs) out.add(f.get(30, TimeUnit.SECONDS));     // a deadlock shows up as a timeout here
        pool.shutdown();
        return out;
    }

    @Test
    void neverSellsMoreThanIsInStock() throws Exception {
        for (int round = 0; round < 20; round++) {
            Fixtures.World w = new Fixtures.World().stock("A", 50);
            List<Boolean> ok = race(64, () -> {
                try {
                    w.service.placeOrder(null, "c", lines("A", 1));
                    return true;
                } catch (OrderException e) {
                    return false;
                }
            });
            assertEquals(50L, ok.stream().filter(b -> b).count());               // exactly the stock, never more
            assertEquals(new Inventory.Level(0, 50, 0), w.inventory.level("A"));
        }
    }

    @Test
    void opposingMultiSkuOrdersDoNotDeadlock() throws Exception {
        Fixtures.World w = new Fixtures.World().stock("A", 100_000).stock("B", 100_000).stock("C", 100_000);
        AtomicInteger n = new AtomicInteger();
        race(32, () -> {
            for (int i = 0; i < 300; i++) {
                int k = n.incrementAndGet();
                // orders list SKUs in different orders on purpose: A,B,C / C,B,A / B,A
                List<OrderLine> l = switch (k % 3) {
                    case 0 -> lines("A", 1, "B", 1, "C", 1);
                    case 1 -> lines("C", 1, "B", 1, "A", 1);
                    default -> lines("B", 1, "A", 1);
                };
                w.service.placeOrder(null, "c", l);
            }
            return null;
        });
        assertEquals(9600, w.service.orderCount());
    }

    @Test
    void stockInvariantHoldsUnderRandomConcurrentWorkflows() throws Exception {
        Fixtures.World w = new Fixtures.World().stock("A", 40).stock("B", 40).stock("C", 40);
        AtomicInteger seeds = new AtomicInteger();
        race(24, () -> {
            Random rnd = new Random(seeds.incrementAndGet());
            for (int i = 0; i < 400; i++) {
                try {
                    String sku1 = "ABC".charAt(rnd.nextInt(3)) + "", sku2 = "ABC".charAt(rnd.nextInt(3)) + "";
                    Order o = w.service.placeOrder(null, "c", lines(sku1, 1 + rnd.nextInt(3), sku2, 1 + rnd.nextInt(3)));
                    switch (rnd.nextInt(4)) {
                        case 0 -> w.service.cancel(o.id());
                        case 1 -> { w.service.pay(null, o.id(), "p"); w.service.ship(o.id()); }
                        case 2 -> { w.service.pay(null, o.id(), "p"); w.service.cancel(o.id()); }
                        default -> { }                                            // left reserved
                    }
                } catch (OrderException expected) {
                    // out of stock is a normal outcome under contention
                }
            }
            return null;
        });
        for (String sku : List.of("A", "B", "C")) {
            Inventory.Level l = w.inventory.level(sku);
            assertTrue(l.available() >= 0 && l.reserved() >= 0 && l.sold() >= 0, sku + " went negative: " + l);
            assertEquals(40L, l.total());                                         // nothing created or lost
        }
        // reserved units must equal what open (CREATED/PAID) orders hold
        long held = 0;
        for (int i = 1; i <= 24 * 400 + 10; i++) {
            Order o = w.service.find("ord_" + i).orElse(null);
            if (o != null && (o.status() == OrderStatus.CREATED || o.status() == OrderStatus.PAID))
                held += o.lines().stream().mapToLong(OrderLine::quantity).sum();
        }
        long reserved = 0;
        for (String sku : List.of("A", "B", "C")) reserved += w.inventory.level(sku).reserved();
        assertEquals(held, reserved);
    }

    @Test
    void racingShipAndCancelHaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 200; round++) {
            Fixtures.World w = new Fixtures.World().stock("A", 1);
            String id = w.service.placeOrder(null, "c", lines("A", 1)).id();
            w.service.pay(null, id, "p");
            AtomicInteger k = new AtomicInteger();
            List<String> outcome = race(2, () -> {
                try {
                    if (k.getAndIncrement() == 0) w.service.ship(id);
                    else w.service.cancel(id);
                    return "won";
                } catch (OrderException e) {
                    return "lost";
                }
            });
            assertEquals(1L, outcome.stream().filter("won"::equals).count());
            Inventory.Level l = w.inventory.level("A");
            OrderStatus s = w.service.get(id).status();
            assertEquals(s == OrderStatus.SHIPPED ? new Inventory.Level(0, 0, 1) : new Inventory.Level(1, 0, 0), l);
        }
    }
}
