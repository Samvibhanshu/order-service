# order-service

[![build-and-test](https://github.com/Samvibhanshu/order-service/actions/workflows/ci.yml/badge.svg)](https://github.com/Samvibhanshu/order-service/actions/workflows/ci.yml)

An e-commerce order backend in plain Java 17: place an order, pay, ship, deliver or cancel, over a JSON HTTP API.
It's small on purpose, and it's built around the four things that go wrong in real order systems:

1. **Overselling.** 64 customers press "Buy" on the last 50 units at the same instant.
2. **Double charging.** A payment request times out, the client retries, and the payment runs twice.
3. **Illegal states.** An unpaid order ships, or a shipped order gets cancelled and its stock "returned".
4. **Crashes.** The server dies mid-request, and an order the customer was told about is gone after the restart.

No frameworks, no database, no dependencies: just the JDK, so every line is visible and explainable. Durability
comes from a write-ahead log (`Journal`) that the service replays on start.

```bash
./build.sh                                   # compile with -Xlint:all and run all 44 tests (about 30 s)
java -cp out/main dev.samridhi.orders.Main   # API on http://127.0.0.1:8080, journal in data/orders.journal
java -cp out/main:out/test dev.samridhi.orders.JournalBench   # the durability numbers below (Windows: out/main;out/test)
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
| An acknowledged change survives a crash | Written to the journal and fsynced before the client gets an answer; replayed on start | The server is killed with SIGKILL mid-traffic 4 times; about 1,100 acknowledged orders, none lost or changed |
| A retry after a crash still doesn't double-order | The idempotency key is written in the same journal record as the order it created | After each restart every acknowledged place is retried with its key and returns the same order |
| A half-written record can't corrupt the state | Every record has a CRC-32; recovery keeps the longest valid prefix and cuts the rest | The journal is cut at every byte of its last records, and at 150 random points: each time the state is one that really existed |

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

## Durability: the write-ahead log

```
request ─► validate ─► reserve stock ─► [ journal.append(record) ─► apply in memory ] ─► fsync ─► answer
                                         └──── inside the per-order atomic step ───┘    shared by concurrent requests
```

- **Write, then apply.** Each change is appended to `data/orders.journal` inside the same atomic step that applies it,
  and the step is not applied if the write fails. So the journal's order is the real order of changes, and memory
  never holds a change the journal doesn't.
- **fsync before the answer, shared between requests (group commit).** The fsync happens outside the lock: while one
  thread waits for the disk, others keep appending, and the next fsync covers all of them. On the build machine
  (2 cores, fsync about 0.2 ms) that doubles throughput at 16 threads; it matters more on slower disks.
- **One record per change, keys included.** An order and the idempotency key that created it are one record, so after a
  crash they are both there or both gone. A remembered business error (`OUT_OF_STOCK`) gets its own record, written
  before the client sees the error.
- **Readable records with checksums.** One line per record: CRC-32, a space, the JSON. A crash can leave a torn last
  line; recovery keeps the longest valid prefix (what RocksDB calls point-in-time recovery) and **truncates the file
  before appending**, otherwise the next recovery would stop at the old garbage and lose everything written after it.
- **Fail-stop.** If a write or an fsync fails (disk full, I/O error), the service refuses every later change with
  `503`. After a failed fsync the OS may have dropped the data, so retrying could report success for writes that are
  gone. Reads keep working; a restart recovers whatever reached the disk.
- **Replay checks itself.** Every record carries the event sequence number it produced; replay re-derives it, and a
  record that cannot apply (a payment for an order that doesn't exist) stops startup instead of guessing.

**Measured** (`JournalBench`, 2-core build machine, local SSD):

| | 1 thread | 16 threads |
|---|---|---|
| In memory (no durability) | 44,300 orders/s | 103,700 orders/s |
| fsync every record | 6,200 orders/s | 4,100 orders/s |
| Group commit | 6,400 orders/s | 8,200 orders/s |

Restarting from a journal of 600,000 records (68 MB) takes about 5 seconds.

## Design decisions

- **Money is a `long` in paise/cents.** Floating point can't represent 0.1 exactly; totals use `Math.multiplyExact`/`addExact`, so overflow is an error, not a wrong bill.
- **Price before reserve.** The total is computed before stock is reserved, so a pricing error can never leave units held by an order that doesn't exist.
- **Business errors are remembered, crashes are not.** If an order failed as OUT_OF_STOCK, retrying the same key returns OUT_OF_STOCK again (one key, one answer). An unexpected exception forgets the key so the client's retry can actually run.
- **Immutable orders.** Each change creates a new `Order` snapshot with `version + 1`; readers never see a half-updated order.
- **Bounded input.** Request bodies are read up to 64 KB, JSON nesting is capped at 64 levels (a deeply nested body would otherwise overflow the stack), and quantities and line counts are limited.
- **Hand-written JSON parser.** It keeps the project dependency-free. It's strict and is tested with 2,000 random write-then-parse round trips and a list of malformed inputs.

## Do the tests catch real bugs?

I planted 9 bugs in the business logic, one at a time, and re-ran the tests: unsorted lock order (deadlock), a stock check that ignored the
quantity, non-atomic idempotency (`get` then `put`), no fingerprint check, a fingerprint that depended on line order,
a release that could run twice, no JSON nesting limit, cancel without releasing stock, and allowing a shipped order to be cancelled.
All 9 are caught. Two of them were first missed, because `OrderService` happens to pass SKUs already sorted and never
releases twice; `InventoryTests` now checks those guarantees on `Inventory` directly.

Then 6 in the durability layer: writes buffered in memory and flushed every 50 records, an idempotency key not stored
with its order, a torn tail not cut before appending, remembered errors not replayed, checksums not checked, and a
refused order keeping its stock reserved. All 6 are caught.

**Two bugs the new tests found in code that already existed:**

- **Every HTTP response took about 40 ms.** The JDK's built-in server leaves Nagle's algorithm on and writes the
  headers and the body separately, so the body waited for the client's delayed ACK. Setting `TCP_NODELAY` brought the
  mean to about 2 ms (`HttpApiTests.responsesAreNotHeldBackByNagleAndDelayedAck`). The crash test, which makes
  thousands of requests, was what made it visible.
- **The demo stock ran out in a second**, so the crash test was mostly exercising `OUT_OF_STOCK`. The demo catalog
  now starts with more units.

## What would change in production

| Here | In production |
|---|---|
| One process: a journal file plus in-memory maps and per-SKU locks | A database: a conditional update (`UPDATE stock SET available = available - ? WHERE sku = ? AND available >= ?`) or a DynamoDB conditional write does the same check-and-subtract across many servers; [order-service-aws](https://github.com/Samvibhanshu/order-service-aws) is this service on DynamoDB |
| The journal grows forever and is replayed in full on start | Periodic snapshots, so a restart loads the latest snapshot and replays only the records after it |
| One copy of the journal | Replication to other machines before acknowledging (a single disk failure loses data here) |
| Idempotency keys kept forever | A table with a TTL (for example, 24 hours) |
| Events kept in memory and rebuilt from the journal | An outbox table written in the same transaction as the order, relayed to a queue (SQS/Kafka) |
| Reservations never expire | Unpaid orders auto-cancel after N minutes to free stock |

What the crash test does and does not show: `SIGKILL` kills the process, not the machine, so data already handed to
the operating system survives it even without an fsync. The fsyncs are what protect against a power cut or a kernel
crash, which a test cannot easily produce; that part rests on the design and on the planted "forgotten flush" bug above.

## Layout

```
src/main/java/dev/samridhi/orders/
  OrderStatus     transition table          Inventory         per-SKU locks, sorted lock order, reservations
  Order           immutable snapshot        IdempotencyStore  putIfAbsent + stored results, durable answers
  OrderService    the workflow, replay      EventLog          append-only outbox
  Journal         write-ahead log           Main              demo server (durable by default)
  HttpApi, Json   HTTP + JSON (JDK only)
src/test/java/dev/samridhi/orders/          44 tests incl. concurrency races, a real HTTP server and kill -9 crashes;
                                            JournalBench (throughput and restart time)
```
