package dev.samridhi.orders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What durability costs, and what group commit buys back.
 * {@code java -cp out/main:out/test dev.samridhi.orders.JournalBench}   (on Windows use out/main;out/test)
 */
public final class JournalBench {
    private JournalBench() {}

    public static void main(String[] args) throws Exception {
        System.out.println("place-order throughput (each order also fsynced before it is answered, except in memory)");
        System.out.printf("%-26s %14s %14s%n", "", "1 thread", "16 threads");
        for (String mode : new String[]{"in memory", "fsync every record", "group commit"}) {
            System.out.printf("%-26s", mode);
            for (int threads : new int[]{1, 16}) System.out.printf(" %10.0f /s ", throughput(mode, threads, 6_000));
            System.out.println();
        }
        System.out.println();
        System.out.println("restart time (read + check + replay the journal)");
        for (int orders : new int[]{20_000, 200_000}) recovery(orders);
    }

    private static double throughput(String mode, int threads, int total) throws Exception {
        Path f = Durability.tempJournal();
        Catalog catalog = new Catalog().add("A", 100).add("B", 100);
        Inventory inventory = new Inventory();
        EventLog events = new EventLog();
        OrderService svc;
        Journal journal = null;
        if (mode.equals("in memory")) {
            svc = new OrderService(catalog, inventory, events);
        } else {
            OrderService.Durable d = OrderService.open(catalog, inventory, events, f,
                    mode.equals("group commit") ? Journal.Sync.GROUP_COMMIT : Journal.Sync.EVERY_RECORD);
            svc = d.service();
            journal = d.journal();
        }
        svc.addStock("A", 10_000_000);
        svc.addStock("B", 10_000_000);
        AtomicInteger left = new AtomicInteger(total);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                while (left.getAndDecrement() > 0) svc.placeOrder(null, "c", List.of(new OrderLine("A", 1), new OrderLine("B", 2)));
            }));
        }
        for (Future<?> x : fs) x.get();
        double perSecond = total / ((System.nanoTime() - t0) / 1e9);
        pool.shutdown();
        if (journal != null) {
            if (threads == 16) System.out.printf("[%.1f records per fsync] ", (double) journal.records() / Math.max(1, journal.fsyncs()));
            journal.close();
        }
        return perSecond;
    }

    private static void recovery(int orders) throws Exception {
        Path f = Durability.tempJournal();
        long records;
        try (Durability.Node n = new Durability.Node(f)) {
            n.service.addStock("A", 10_000_000);
            for (int i = 0; i < orders; i++) {
                Order o = n.service.placeOrder("k" + i, "c" + (i % 100), Fixtures.lines("A", 1 + i % 3));
                n.service.pay("p" + i, o.id(), "pay_" + i);
                if (i % 2 == 0) n.service.ship(o.id()); else n.service.cancel(o.id());
            }
            records = n.journal.records();
        }
        long t0 = System.nanoTime();
        try (Durability.Node n = new Durability.Node(f)) {
            double ms = (System.nanoTime() - t0) / 1e6;
            System.out.printf("  %,9d records (%,d MB): %,6.0f ms, %,.0f records/s%n", records, Files.size(f) >> 20, ms, records / (ms / 1000));
            if (n.service.orderCount() != orders) throw new AssertionError("lost orders");
        }
    }
}
