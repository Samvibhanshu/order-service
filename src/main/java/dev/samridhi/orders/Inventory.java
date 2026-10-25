package dev.samridhi.orders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Stock per SKU with all-or-nothing reservations that are safe under concurrency.
 *
 * <p><b>No overselling.</b> Every SKU has its own lock, and "check stock, then subtract" happens while holding it.
 * Without the lock two threads could both read "1 left" and both sell it.
 *
 * <p><b>No deadlock.</b> An order for {A, B} and another for {B, A} each need two locks. If one took A then B and the
 * other B then A, each could hold one lock and wait forever for the other. Locks are therefore always taken in
 * sorted SKU order, so no cycle of waiting threads can form.
 *
 * <p><b>Invariant</b> (checked by the tests after thousands of concurrent operations), per SKU:
 * {@code available + reserved + sold == total ever added}, and none of them is ever negative.
 */
public final class Inventory {
    private static final class Stock {
        final ReentrantLock lock = new ReentrantLock();
        long available, reserved, sold;
    }

    private final ConcurrentHashMap<String, Stock> stock = new ConcurrentHashMap<>();
    /** reservationId -> units held per SKU. Removing an entry is how a release/commit claims it exactly once. */
    private final ConcurrentHashMap<String, Map<String, Long>> reservations = new ConcurrentHashMap<>();

    public void addStock(String sku, long units) {
        if (units <= 0) throw new IllegalArgumentException("units must be positive");
        Stock s = stock.computeIfAbsent(sku, k -> new Stock());
        s.lock.lock();
        try {
            s.available += units;
        } finally {
            s.lock.unlock();
        }
    }

    /**
     * Reserves every line or nothing. Returns false (and changes nothing) if any SKU has too little stock.
     * Quantities for the same SKU must already be merged by the caller.
     */
    public boolean reserve(String reservationId, Map<String, Long> unitsBySku) {
        if (reservations.containsKey(reservationId)) throw new IllegalStateException("duplicate reservation " + reservationId);
        List<Stock> locked = lockInOrder(unitsBySku);
        try {
            for (Map.Entry<String, Long> e : new TreeMap<>(unitsBySku).entrySet()) {
                Stock s = stock.get(e.getKey());
                if (s == null || s.available < e.getValue()) return false;      // nothing changed yet
            }
            for (Map.Entry<String, Long> e : unitsBySku.entrySet()) {
                Stock s = stock.get(e.getKey());
                s.available -= e.getValue();
                s.reserved += e.getValue();
            }
            reservations.put(reservationId, Map.copyOf(unitsBySku));
            return true;
        } finally {
            unlockAll(locked);
        }
    }

    /** Returns reserved units to the shelf (order cancelled). Calling it twice is harmless. */
    public void release(String reservationId) {
        settle(reservationId, false);
    }

    /** Turns reserved units into sold units (order shipped). Calling it twice is harmless. */
    public void commit(String reservationId) {
        settle(reservationId, true);
    }

    private void settle(String reservationId, boolean sell) {
        Map<String, Long> held = reservations.remove(reservationId);     // only one caller can win this
        if (held == null) return;
        List<Stock> locked = lockInOrder(held);
        try {
            for (Map.Entry<String, Long> e : held.entrySet()) {
                Stock s = stock.get(e.getKey());
                s.reserved -= e.getValue();
                if (sell) s.sold += e.getValue();
                else s.available += e.getValue();
            }
        } finally {
            unlockAll(locked);
        }
    }

    private List<Stock> lockInOrder(Map<String, Long> unitsBySku) {
        List<Stock> locked = new ArrayList<>();
        for (String sku : new TreeMap<>(unitsBySku).keySet()) {              // sorted order: the deadlock fix
            Stock s = stock.computeIfAbsent(sku, k -> new Stock());
            s.lock.lock();
            locked.add(s);
        }
        return locked;
    }

    private static void unlockAll(List<Stock> locked) {
        for (int i = locked.size() - 1; i >= 0; i--) locked.get(i).lock.unlock();
    }

    public record Level(long available, long reserved, long sold) {
        public long total() {
            return available + reserved + sold;
        }
    }

    public Level level(String sku) {
        Stock s = stock.get(sku);
        if (s == null) return new Level(0, 0, 0);
        s.lock.lock();
        try {
            return new Level(s.available, s.reserved, s.sold);
        } finally {
            s.lock.unlock();
        }
    }
}
