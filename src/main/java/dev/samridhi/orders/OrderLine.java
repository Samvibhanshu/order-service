package dev.samridhi.orders;

/** One line of an order: a product and how many units. */
public record OrderLine(String sku, long quantity) {}
