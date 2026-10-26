package dev.samridhi.orders;

import java.nio.file.Path;

/**
 * Starts the service with a small demo catalog, durable by default.
 *
 * <pre>
 *   java -cp out/main dev.samridhi.orders.Main [port] [journal-file]      defaults: 8080, data/orders.journal
 *   java -cp out/main dev.samridhi.orders.Main [port] --in-memory         nothing is written to disk
 * </pre>
 * On start the journal is replayed, so orders, stock, events and idempotency answers survive a restart or a crash.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String where = args.length > 1 ? args[1] : "data/orders.journal";
        Catalog catalog = new Catalog().add("BOOK-1", 49_900).add("MUG-1", 29_900).add("LAMP-1", 149_900);
        Inventory inventory = new Inventory();
        EventLog events = new EventLog();
        OrderService service;
        boolean fresh;
        if (where.equals("--in-memory")) {
            service = new OrderService(catalog, inventory, events);
            fresh = true;
            System.out.println("in-memory mode: nothing survives a restart");
        } else {
            OrderService.Durable d = OrderService.open(catalog, inventory, events, Path.of(where), Journal.Sync.GROUP_COMMIT);
            service = d.service();
            fresh = d.recovery().records().isEmpty();
            System.out.println("journal " + Path.of(where).toAbsolutePath() + ": replayed " + d.recovery().records().size()
                    + " records in " + d.replayMillis() + " ms"
                    + (d.recovery().clean() ? "" : "; cut " + d.recovery().discardedBytes() + " bytes of torn tail ("
                    + d.recovery().problem() + ")"));
        }
        if (fresh) {
            service.addStock("BOOK-1", 1000);
            service.addStock("MUG-1", 250);
            service.addStock("LAMP-1", 5);
        }
        HttpApi api = new HttpApi(service, inventory, events);
        System.out.println("order-service listening on http://127.0.0.1:" + api.start(port));
    }
}
