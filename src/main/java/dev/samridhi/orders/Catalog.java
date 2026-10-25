package dev.samridhi.orders;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Product prices in the smallest currency unit (paise / cents): money is never a double. */
public final class Catalog {
    private final Map<String, Long> priceCents = new ConcurrentHashMap<>();

    public Catalog add(String sku, long priceCents) {
        if (sku == null || sku.isBlank()) throw new IllegalArgumentException("blank sku");
        if (priceCents < 0) throw new IllegalArgumentException("negative price");
        this.priceCents.put(sku, priceCents);
        return this;
    }

    public boolean contains(String sku) {
        return priceCents.containsKey(sku);
    }

    public long price(String sku) {
        Long p = priceCents.get(sku);
        if (p == null) throw new OrderException(OrderException.Code.VALIDATION, "unknown sku: " + sku);
        return p;
    }
}
