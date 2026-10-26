package dev.samridhi.orders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Helpers for the durability tests: a service on a journal file, a snapshot of its state, a random workload. */
final class Durability {
    private Durability() {}

    static final String[] SKUS = {"A", "B", "C"};

    static Path tempJournal() {
        try {
            return Files.createTempDirectory("orders-journal").resolve("orders.journal");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One running "server": its own catalog, stock, events and service on the given journal file. */
    static final class Node implements AutoCloseable {
        final Catalog catalog = new Catalog().add("A", 100).add("B", 250).add("C", 999);
        final Inventory inventory = new Inventory();
        final EventLog events = new EventLog();
        final OrderService service;
        final Journal journal;
        final Journal.Recovery recovery;

        Node(Path file) {
            this(file, Journal.Sync.GROUP_COMMIT);
        }

        Node(Path file, Journal.Sync sync) {
            OrderService.Durable d = OrderService.open(catalog, inventory, events, file, sync);
            service = d.service();
            journal = d.journal();
            recovery = d.recovery();
        }

        Snapshot snapshot() {
            Map<String, Inventory.Level> levels = new LinkedHashMap<>();
            for (String s : SKUS) levels.put(s, inventory.level(s));
            return new Snapshot(service.allOrders(), levels, events.after(0, Integer.MAX_VALUE));
        }

        @Override
        public void close() {
            journal.close();
        }
    }

    /** Everything a client could observe, compared field by field (all records). */
    record Snapshot(List<Order> orders, Map<String, Inventory.Level> stock, List<EventLog.Event> events) {}

    /** Stock identity: per SKU, available + reserved + sold equals what was added; reserved and sold match the orders. */
    static void checkInvariants(Snapshot s, Map<String, Long> added) {
        Map<String, Long> reserved = new LinkedHashMap<>(), sold = new LinkedHashMap<>();
        for (Order o : s.orders()) {
            Map<String, Long> target = switch (o.status()) {
                case CREATED, PAID -> reserved;
                case SHIPPED, DELIVERED -> sold;
                case CANCELLED -> null;
            };
            if (target != null) for (OrderLine l : o.lines()) target.merge(l.sku(), l.quantity(), Long::sum);
        }
        for (String sku : SKUS) {
            Inventory.Level l = s.stock().get(sku);
            check(l.total() == added.getOrDefault(sku, 0L), sku + " total " + l.total() + " != added " + added.get(sku));
            check(l.reserved() == reserved.getOrDefault(sku, 0L), sku + " reserved " + l.reserved() + " != " + reserved.get(sku));
            check(l.sold() == sold.getOrDefault(sku, 0L), sku + " sold " + l.sold() + " != " + sold.get(sku));
            check(l.available() >= 0, sku + " available is negative");
        }
        for (int i = 0; i < s.events().size(); i++) check(s.events().get(i).seq() == i + 1, "event sequence has a gap at " + i);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    /**
     * Runs {@code ops} random operations: places (some with idempotency keys, some repeated), payments (some keyed),
     * ships, deliveries, cancels and restocks, including ones that fail. Returns the stock added per SKU.
     * {@code afterEach} sees the operation's index after it finished (succeeded or failed).
     */
    static Map<String, Long> workload(OrderService svc, long seed, int ops, Map<String, Long> added, java.util.function.IntConsumer afterEach) {
        Random r = new Random(seed);
        List<String> ids = new ArrayList<>();
        List<String[]> keyed = new ArrayList<>();                        // key, customer, sku, qty: to replay retries
        for (int i = 0; i < ops; i++) {
            try {
                int pick = r.nextInt(100);
                if (pick < 8) {
                    String sku = SKUS[r.nextInt(SKUS.length)];
                    long units = 1 + r.nextInt(6);
                    svc.addStock(sku, units);
                    added.merge(sku, units, Long::sum);
                } else if (pick < 45 || ids.isEmpty()) {
                    String sku = SKUS[r.nextInt(SKUS.length)];
                    long qty = 1 + r.nextInt(3);
                    String key = r.nextInt(3) == 0 ? null : "k" + seed + "-" + i;
                    String customer = "c" + r.nextInt(20);
                    if (key != null && !keyed.isEmpty() && r.nextInt(4) == 0) {   // a retry of an earlier keyed place
                        String[] k = keyed.get(r.nextInt(keyed.size()));
                        key = k[0]; customer = k[1]; sku = k[2]; qty = Long.parseLong(k[3]);
                    } else if (key != null) {
                        keyed.add(new String[]{key, customer, sku, Long.toString(qty)});
                    }
                    Order o = svc.placeOrder(key, customer, List.of(new OrderLine(sku, qty), new OrderLine(SKUS[r.nextInt(3)], 1)));
                    if (!ids.contains(o.id())) ids.add(o.id());
                } else {
                    String id = ids.get(r.nextInt(ids.size()));
                    switch (r.nextInt(5)) {
                        case 0, 1 -> svc.pay(r.nextBoolean() ? "p-" + id : null, id, "pay_" + id);
                        case 2 -> svc.ship(id);
                        case 3 -> svc.deliver(id);
                        default -> svc.cancel(id);
                    }
                }
            } catch (OrderException expected) {
                // out of stock, invalid state, mismatched key: part of the workload
            }
            if (afterEach != null) afterEach.accept(i);
        }
        return added;
    }
}
