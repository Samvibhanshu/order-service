# order-service

[![build-and-test](https://github.com/Samvibhanshu/order-service/actions/workflows/ci.yml/badge.svg)](https://github.com/Samvibhanshu/order-service/actions/workflows/ci.yml)

An e-commerce order backend in plain Java 17: place an order, pay, ship, deliver or cancel, over a JSON HTTP API.
It's small on purpose, and it's built around the three things that go wrong in real order systems:

1. **Overselling.** 64 customers press "Buy" on the last 50 units at the same instant.
2. **Double charging.** A payment request times out, the client retries, and the payment runs twice.
3. **Illegal states.** An unpaid order ships, or a shipped order gets cancelled and its stock "returned".

No frameworks, no database, no dependencies: just the JDK, so every line is visible and explainable.

```bash
./build.sh                                   # compile with -Xlint:all and run all 30 tests (under 10 s)
java -cp out/main dev.samridhi.orders.Main   # API on http://127.0.0.1:8080 with a demo catalog
```

## The guarantees, and how each is enforced

| Guarantee | Mechanism | Proven by |
|---|---|---|
| Never sell more than is in stock | One lock per SKU; "check stock, then subtract" happens while holding it | 64 threads race for 50 units, 20 rounds: exactly 50 orders succeed every time |
| Multi-item orders reserve all items or none | Check every line first, change nothing unless all pass | A failed order leaves every SKU untouched |
| Multi-item orders never deadlock | Locks are always taken in sorted SKU order, so no cycle of waiting threads can form | 16 threads reserve {A,B,C,D} and {D,C,B,A} 20,000 times each; with unsorted locking the test hangs |
| Stock is never created or lost | Per SKU: `available + reserved + sold` stays constant; release/commit claim a reservation by *removing* it, so they take effect exactly once | 24 threads run 9,600 random place/pay/ship/cancel workflows; the identity holds and reserved = what open orders hold |
| A retried request does not run twice | `Idempotency-Key`: `putIfAbsent` lets exactly one request run; duplicates wait for and return its result | 16 identical requests at once create exactly one order |
| A key can't be reused for a different request | The stored request fingerprint must match (line order doesn't matter) | 422 IDEMPOTENCY_MISMATCH |
| Only legal state changes happen | One transition table (`OrderStatus.next()`); each change is atomic per order (`ConcurrentHashMap.compute`) | All 25 from/to pairs checked; a racing ship and cancel always produce exactly one winner |
| Downstream systems see every change, in order | Event appended in the same atomic step as the state change (an outbox) | Event sequence matches the lifecycle; `GET /events?after=N` pages through it |

## Order lifecycle

```
CREATED --pay--> PAID --ship--> SHIPPED --deliver--> DELIVERED
   |               |
   +----cancel-----+----> CANCELLED   (stock released; refund=true if it was paid)
```

## API

| Method and path | Body | Success |
|---|---|---|
| `POST /orders` (+ optional `Idempotency-Key`) | `{"customerId":"c1","lines":[{"sku":"BOOK-1","quantity":2}]}` | 201 order |
| `GET /orders/{id}` | | 200 order |
| `POST /orders/{id}/pay` (+ optional `Idempotency-Key`) | `{"paymentRef":"pay_123"}` | 200 order |
| `POST /orders/{id}/ship`, `/deliver`, `/cancel` | | 200 order |
| `GET /inventory/{sku}` | | `{"available":..,"reserved":..,"sold":..}` |
| `GET /events?after=0&limit=100` | | `{"events":[...]}` |

Errors are `{"error": CODE, "message": ...}`: 400 VALIDATION, 404 NOT_FOUND, 409 INVALID_STATE / OUT_OF_STOCK,
422 IDEMPOTENCY_MISMATCH, 413 for bodies over 64 KB.

```bash
curl -s -X POST localhost:8080/orders -H 'Idempotency-Key: k1' \
     -d '{"customerId":"c1","lines":[{"sku":"BOOK-1","quantity":2}]}'
```

## Design decisions

- **Money is a `long` in paise/cents.** Floating point can't represent 0.1 exactly; totals use `Math.multiplyExact`/`addExact`, so overflow is an error, not a wrong bill.
- **Price before reserve.** The total is computed before stock is reserved, so a pricing error can never leave units held by an order that doesn't exist.
- **Business errors are remembered, crashes are not.** If an order failed as OUT_OF_STOCK, retrying the same key returns OUT_OF_STOCK again (one key, one answer). An unexpected exception forgets the key so the client's retry can actually run.
- **Immutable orders.** Each change creates a new `Order` snapshot with `version + 1`; readers never see a half-updated order.
- **Bounded input.** Request bodies are read up to 64 KB, JSON nesting is capped at 64 levels (a deeply nested body would otherwise overflow the stack), and quantities and line counts are limited.
- **Hand-written JSON parser.** It keeps the project dependency-free. It's strict and is tested with 2,000 random write-then-parse round trips and a list of malformed inputs.

## Do the tests catch real bugs?

I planted 9 bugs, one at a time, and re-ran the tests: unsorted lock order (deadlock), a stock check that ignored the
quantity, non-atomic idempotency (`get` then `put`), no fingerprint check, a fingerprint that depended on line order,
a release that could run twice, no JSON nesting limit, cancel without releasing stock, and allowing a shipped order to be cancelled.
All 9 are caught. Two of them were first missed, because `OrderService` happens to pass SKUs already sorted and never
releases twice; `InventoryTests` now checks those guarantees on `Inventory` directly.

## What would change in production

| Here | In production |
|---|---|
| In-memory maps and per-SKU locks | A database: a conditional update (`UPDATE stock SET available = available - ? WHERE sku = ? AND available >= ?`) or a DynamoDB conditional write does the same check-and-subtract atomically across many servers |
| Idempotency keys kept forever in memory | A table with a TTL (for example, 24 hours) |
| Event list in memory | An outbox table written in the same transaction as the order, relayed to a queue (SQS/Kafka) |
| Reservations never expire | Unpaid orders auto-cancel after N minutes to free stock |

## Layout

```
src/main/java/dev/samridhi/orders/
  OrderStatus     transition table          Inventory         per-SKU locks, sorted lock order, reservations
  Order           immutable snapshot        IdempotencyStore  putIfAbsent + stored results
  OrderService    the workflow              EventLog          append-only outbox
  HttpApi, Json   HTTP + JSON (JDK only)    Main              demo server
src/test/java/dev/samridhi/orders/          30 tests incl. concurrency races and a real HTTP server
```
