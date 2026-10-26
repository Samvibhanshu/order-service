package dev.samridhi.orders;

import static dev.samridhi.orders.testkit.Assert.assertEquals;
import static dev.samridhi.orders.testkit.Assert.assertTrue;

import dev.samridhi.orders.testkit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Restarting the service from its journal gives back exactly what clients could see before. */
public class RecoveryTests {

    @Test
    void aRestartRebuildsExactlyTheSameState() {
        Path f = Durability.tempJournal();
        Map<String, Long> added = new LinkedHashMap<>();
        Durability.Snapshot before;
        try (Durability.Node n = new Durability.Node(f)) {
            Durability.workload(n.service, 1, 3000, added, null);
            before = n.snapshot();
            Durability.checkInvariants(before, added);
        }
        try (Durability.Node n = new Durability.Node(f)) {
            assertEquals(before, n.snapshot());
            assertTrue(n.recovery.clean());
            // new ids continue after the old ones, and the service keeps working
            long maxId = before.orders().stream().mapToLong(o -> Long.parseLong(o.id().substring(4))).max().orElse(0);
            n.service.addStock("A", 5);
            added.merge("A", 5L, Long::sum);
            Order fresh = n.service.placeOrder(null, "new", Fixtures.lines("A", 1));
            assertTrue(Long.parseLong(fresh.id().substring(4)) > maxId, fresh.id() + " reuses an old id");
            Durability.workload(n.service, 2, 1000, added, null);
            before = n.snapshot();
        }
        try (Durability.Node n = new Durability.Node(f)) {              // and a second restart, after more work
            assertEquals(before, n.snapshot());
            Durability.checkInvariants(n.snapshot(), added);
        }
    }

    @Test
    void idempotencyAnswersSurviveARestart() {
        Path f = Durability.tempJournal();
        Order placed, paid;
        try (Durability.Node n = new Durability.Node(f)) {
            n.service.addStock("A", 3);
            placed = n.service.placeOrder("k-ok", "c1", Fixtures.lines("A", 2));
            expect(OrderException.Code.OUT_OF_STOCK, () -> n.service.placeOrder("k-fail", "c2", Fixtures.lines("A", 5)));
            paid = n.service.pay("p-1", placed.id(), "pay_1");
        }
        try (Durability.Node n = new Durability.Node(f)) {
            int events = n.events.size();
            assertEquals(placed, n.service.placeOrder("k-ok", "c1", Fixtures.lines("A", 2)));   // same order, not a second
            assertEquals(1, n.service.orderCount());
            expect(OrderException.Code.IDEMPOTENCY_MISMATCH, () -> n.service.placeOrder("k-ok", "c1", Fixtures.lines("A", 1)));
            n.service.addStock("A", 100);
            // one key, one answer: the remembered OUT_OF_STOCK comes back even though there is stock now
            expect(OrderException.Code.OUT_OF_STOCK, () -> n.service.placeOrder("k-fail", "c2", Fixtures.lines("A", 5)));
            assertEquals(paid, n.service.pay("p-1", placed.id(), "pay_1"));                    // no second charge
            assertEquals(events, n.events.size());                       // retries appended no events
        }
    }

    @Test
    void everyPrefixOfTheJournalIsAStateThatReallyExisted() throws Exception {
        Path f = Durability.tempJournal();
        Map<String, Long> added = new LinkedHashMap<>();
        List<Long> sizes = new ArrayList<>();
        List<Durability.Snapshot> states = new ArrayList<>();
        try (Durability.Node n = new Durability.Node(f)) {
            sizes.add(0L);
            states.add(n.snapshot());
            Durability.workload(n.service, 3, 400, added, i -> {
                try {
                    long size = Files.size(f);
                    if (size != sizes.get(sizes.size() - 1)) {           // this operation wrote a record
                        sizes.add(size);
                        states.add(n.snapshot());
                    }
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
        byte[] all = Files.readAllBytes(f);
        Random r = new Random(4);
        for (int trial = 0; trial < 150; trial++) {
            int cut = r.nextInt(all.length + 1);
            Path g = Durability.tempJournal();
            Files.write(g, java.util.Arrays.copyOf(all, cut));
            int k = 0;
            while (k + 1 < sizes.size() && sizes.get(k + 1) <= cut) k++;
            try (Durability.Node n = new Durability.Node(g)) {
                assertEquals(states.get(k), n.snapshot());
            }
        }
    }

    @Test
    void concurrentWorkThenRestartGivesTheSameState() throws Exception {
        Path f = Durability.tempJournal();
        Durability.Snapshot before;
        Map<String, Long> added = new java.util.concurrent.ConcurrentHashMap<>();
        try (Durability.Node n = new Durability.Node(f)) {
            for (String s : Durability.SKUS) {
                n.service.addStock(s, 400);
                added.put(s, 400L);
            }
            ExecutorService pool = Executors.newFixedThreadPool(16);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                long seed = 100 + t;
                fs.add(pool.submit(() -> Durability.workload(n.service, seed, 300, added, null)));
            }
            for (Future<?> x : fs) x.get(120, TimeUnit.SECONDS);
            pool.shutdown();
            before = n.snapshot();
            Durability.checkInvariants(before, added);
            assertTrue(n.journal.fsyncs() < n.journal.records(), "group commit shared fsyncs");
        }
        try (Durability.Node n = new Durability.Node(f)) {
            assertEquals(before, n.snapshot());
            Durability.checkInvariants(n.snapshot(), added);
        }
    }

    private static void expect(OrderException.Code code, Runnable r) {
        try {
            r.run();
        } catch (OrderException e) {
            assertEquals(code, e.code());
            return;
        }
        throw new AssertionError("expected " + code);
    }
}
