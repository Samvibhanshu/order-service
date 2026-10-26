package dev.samridhi.orders;

import static dev.samridhi.orders.testkit.Assert.assertEquals;
import static dev.samridhi.orders.testkit.Assert.assertThrows;
import static dev.samridhi.orders.testkit.Assert.assertTrue;

import dev.samridhi.orders.testkit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** The journal file on its own: format, torn tails, corruption, group commit, fail-stop. */
public class JournalTests {

    private static List<Map<String, Object>> write(Path file, int n) {
        List<Map<String, Object>> recs = new ArrayList<>();
        try (Journal j = Journal.open(file, Journal.Sync.GROUP_COMMIT).journal()) {
            for (int i = 0; i < n; i++) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("t", "test");
                r.put("i", (long) i);
                r.put("text", "line \"" + i + "\"\nwith a newline and ünïcode");
                recs.add(r);
                String json = Json.write(r);
                j.syncTo(j.append(() -> json, () -> {}));
            }
        }
        return recs;
    }

    @Test
    void recordsComeBackExactly() throws Exception {
        Path f = Durability.tempJournal();
        List<Map<String, Object>> written = write(f, 200);
        Journal.Recovery r = Journal.recover(f);
        assertEquals(written, r.records());
        assertTrue(r.clean());
        assertEquals(Files.size(f), r.goodBytes());
    }

    @Test
    void aTornTailIsCutAtEveryPossibleByteAndLaterRecordsSurvive() throws Exception {
        Path f = Durability.tempJournal();
        List<Map<String, Object>> written = write(f, 12);
        byte[] all = Files.readAllBytes(f);
        List<Integer> ends = new ArrayList<>();                          // byte offset after each record
        for (int i = 0; i < all.length; i++) if (all[i] == '\n') ends.add(i + 1);
        int checked = 0;
        for (int cut = ends.get(8); cut <= all.length; cut++) {          // every byte of the last 3-4 records
            Path g = Durability.tempJournal();
            Files.write(g, java.util.Arrays.copyOf(all, cut));
            int complete = 0;
            while (complete < ends.size() && ends.get(complete) <= cut) complete++;
            Journal.Opened o = Journal.open(g, Journal.Sync.GROUP_COMMIT);
            assertEquals(written.subList(0, complete), o.recovery().records());
            assertEquals((long) (cut - (complete == 0 ? 0 : ends.get(complete - 1))), o.recovery().discardedBytes());
            Map<String, Object> next = new LinkedHashMap<>(Map.of("t", "after", "cut", (long) cut));
            o.journal().syncTo(o.journal().append(() -> Json.write(next), () -> {}));
            o.journal().close();
            List<Map<String, Object>> again = Journal.recover(g).records();       // the new record is not lost
            assertEquals(complete + 1, again.size());
            assertEquals(next, again.get(again.size() - 1));
            checked++;
        }
        assertTrue(checked > 200, "checked " + checked + " cut points");
    }

    @Test
    void aCorruptByteStopsRecoveryAtThatRecord() throws Exception {
        Path f = Durability.tempJournal();
        List<Map<String, Object>> written = write(f, 30);
        byte[] all = Files.readAllBytes(f);
        int start = 0, line = 0;
        for (int i = 0; line < 20; i++) if (all[i] == '\n') { line++; start = i + 1; }
        all[start + 15] ^= 0x04;                                          // one flipped bit inside record 20
        Files.write(f, all);
        Journal.Recovery r = Journal.recover(f);
        assertEquals(written.subList(0, 20), r.records());
        assertEquals((long) start, r.goodBytes());
        assertTrue(r.problem().contains("invalid record at byte " + start), r.problem());
    }

    @Test
    void groupCommitSharesFsyncsAndEveryRecordModeDoesNot() throws Exception {
        for (Journal.Sync mode : Journal.Sync.values()) {
            Path f = Durability.tempJournal();
            Journal j = Journal.open(f, mode).journal();
            ExecutorService pool = Executors.newFixedThreadPool(16);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                fs.add(pool.submit(() -> {
                    for (int i = 0; i < 100; i++) j.syncTo(j.append(() -> "{\"t\":\"x\"}", () -> {}));
                }));
            }
            for (Future<?> x : fs) x.get(60, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(1600L, j.records());
            if (mode == Journal.Sync.EVERY_RECORD) assertEquals(1600L, j.fsyncs());
            else assertTrue(j.fsyncs() < 1600, "group commit used " + j.fsyncs() + " fsyncs for 1600 records");
            j.close();
            assertEquals(1600, Journal.recover(f).records().size());
        }
    }

    @Test
    void aFailedWriteChangesNothingAndTheServiceStopsTakingWrites() throws Exception {
        Path f = Durability.tempJournal();
        try (Durability.Node n = new Durability.Node(f)) {
            n.service.addStock("A", 10);
            Order o = n.service.placeOrder("k1", "c1", Fixtures.lines("A", 2));
            Durability.Snapshot before = n.snapshot();

            n.journal.failNextWrite();
            OrderException e = expectUnavailable(() -> n.service.pay(null, o.id(), "pay_1"));
            assertEquals(OrderException.Code.UNAVAILABLE, e.code());
            assertEquals(before, n.snapshot());                           // not paid, no event, stock untouched
            assertTrue(!n.journal.healthy());

            expectUnavailable(() -> n.service.placeOrder(null, "c2", Fixtures.lines("A", 1)));
            expectUnavailable(() -> n.service.cancel(o.id()));             // a legal change, refused for storage
            expectUnavailable(() -> n.service.addStock("A", 1));
            assertEquals(before, n.snapshot());                           // the refused place released its stock
            assertEquals(OrderStatus.CREATED, n.service.get(o.id()).status());   // reads still work
        }
        try (Durability.Node again = new Durability.Node(f)) {
            assertEquals(OrderStatus.CREATED, again.service.get("ord_1").status());
            assertEquals(1, again.service.orderCount());
        }
    }

    @Test
    void aFailedWriteDuringAKeyedPlaceIsNotRemembered() throws Exception {
        Path f = Durability.tempJournal();
        try (Durability.Node n = new Durability.Node(f)) {
            n.service.addStock("A", 10);
            n.journal.failNextWrite();
            expectUnavailable(() -> n.service.placeOrder("k1", "c1", Fixtures.lines("A", 2)));
            assertEquals(0, n.service.orderCount());
        }
        try (Durability.Node again = new Durability.Node(f)) {           // after a restart the same key just works
            Order o = again.service.placeOrder("k1", "c1", Fixtures.lines("A", 2));
            assertEquals(OrderStatus.CREATED, o.status());
            assertEquals(8L, again.inventory.level("A").available());
        }
    }

    private static OrderException expectUnavailable(Runnable r) {
        try {
            r.run();
        } catch (OrderException e) {
            assertEquals(OrderException.Code.UNAVAILABLE, e.code());
            return e;
        }
        throw new AssertionError("expected UNAVAILABLE");
    }

    @Test
    void openingCutsAHalfWrittenRecordAndSaysSo() throws Exception {
        Path f = Durability.tempJournal();
        write(f, 3);
        Files.write(f, "0000abcd {\"t\":\"half".getBytes(), java.nio.file.StandardOpenOption.APPEND);
        Journal.Opened o = Journal.open(f, Journal.Sync.GROUP_COMMIT);
        assertEquals(3, o.recovery().records().size());
        assertTrue(o.recovery().problem().startsWith("incomplete last record"), o.recovery().problem());
        o.journal().close();
        assertTrue(Journal.recover(f).clean());                          // the tail was cut on open
    }

    @Test
    void aRecordThatCannotBeAppliedStopsStartupInsteadOfGuessing() throws Exception {
        Path f = Durability.tempJournal();
        Files.write(f, Journal.encode("{\"t\":\"move\",\"id\":\"ord_9\",\"to\":\"PAID\",\"seq\":1}"));
        assertThrows(IllegalStateException.class, () -> new Durability.Node(f));
    }
}
