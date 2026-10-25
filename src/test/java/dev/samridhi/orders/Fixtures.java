package dev.samridhi.orders;

import java.util.List;

final class Fixtures {
    private Fixtures() {}

    static final class World {
        final Catalog catalog = new Catalog().add("A", 100).add("B", 250).add("C", 999);
        final Inventory inventory = new Inventory();
        final EventLog events = new EventLog();
        final OrderService service = new OrderService(catalog, inventory, events);

        World stock(String sku, long units) {
            inventory.addStock(sku, units);
            return this;
        }
    }

    static List<OrderLine> lines(Object... skuQty) {
        java.util.ArrayList<OrderLine> out = new java.util.ArrayList<>();
        for (int i = 0; i < skuQty.length; i += 2) out.add(new OrderLine((String) skuQty[i], ((Number) skuQty[i + 1]).longValue()));
        return out;
    }
}
