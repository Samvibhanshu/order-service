package dev.samridhi.orders;

/** Starts the service with a small demo catalog.  Usage: {@code java -cp out/main dev.samridhi.orders.Main [port]} */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Catalog catalog = new Catalog().add("BOOK-1", 49_900).add("MUG-1", 29_900).add("LAMP-1", 149_900);
        Inventory inventory = new Inventory();
        inventory.addStock("BOOK-1", 100);
        inventory.addStock("MUG-1", 25);
        inventory.addStock("LAMP-1", 5);
        EventLog events = new EventLog();
        HttpApi api = new HttpApi(new OrderService(catalog, inventory, events), inventory, events);
        System.out.println("order-service listening on http://127.0.0.1:" + api.start(port));
    }
}
