package dev.samridhi.orders;

import static dev.samridhi.orders.testkit.Assert.assertEquals;
import static dev.samridhi.orders.testkit.Assert.assertTrue;

import dev.samridhi.orders.testkit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The real thing: the server runs as a separate JVM, clients send orders over HTTP, and the server is killed with
 * SIGKILL (no shutdown hooks, no flushing) at a random moment. After a restart on the same journal, nothing a client
 * was told succeeded may be missing, and the stock must still add up. Repeated over several kills.
 */
public class CrashTests {
    private static final Map<String, Long> SEEDED = Map.of("BOOK-1", 1000L, "MUG-1", 250L, "LAMP-1", 5L);
    private static final String[] SKUS = {"BOOK-1", "MUG-1", "LAMP-1"};

    /** What the clients know: per order, the last status they were told, and a change in flight when the server died. */
    private static final class Ledger {
        final Map<String, String> acked = new ConcurrentHashMap<>();            // order id -> last acknowledged status
        final Map<String, String> inFlight = new ConcurrentHashMap<>();         // order id -> status being requested
        final Map<String, String> keyToOrder = new ConcurrentHashMap<>();       // idempotency key -> acknowledged order id
        final Map<String, String> keyBody = new ConcurrentHashMap<>();          // idempotency key -> request body
    }

    private static final class Server {
        final Process process;
        final int port;

        Server(Path journal) throws Exception {
            String java = ProcessHandle.current().info().command().orElse("java");
            process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    "dev.samridhi.orders.Main", "0", journal.toString()).redirectErrorStream(true).start();
            BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            Pattern listening = Pattern.compile("listening on http://127.0.0.1:(\\d+)");
            int p = -1;
            String line;
            while ((line = out.readLine()) != null) {
                Matcher m = listening.matcher(line);
                if (m.find()) {
                    p = Integer.parseInt(m.group(1));
                    break;
                }
            }
            if (p < 0) throw new IllegalStateException("server did not start");
            port = p;
            Thread drain = new Thread(() -> {
                try {
                    while (out.readLine() != null) { /* keep the pipe empty */ }
                } catch (Exception ignored) {
                    // process gone
                }
            });
            drain.setDaemon(true);
            drain.start();
        }

        void kill() throws Exception {
            process.destroyForcibly();                                   // SIGKILL on Linux and macOS
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static HttpResponse<String> send(HttpClient c, int port, String method, String path, String body, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
        if (key != null) b.header("Idempotency-Key", key);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void killingTheServerMidTrafficLosesNoAcknowledgedChange() throws Exception {
        Path journal = Durability.tempJournal();
        Ledger ledger = new Ledger();
        Random r = new Random(7);
        int totalAcked = 0;
        for (int round = 0; round < 4; round++) {
            Server s = new Server(journal);
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            verify(c, s.port, ledger);                                   // after every restart (round 0: empty)
            AtomicBoolean dead = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(8);
            for (int t = 0; t < 8; t++) {
                final int thread = t;
                final int rnd = round;
                new Thread(() -> {
                    try {
                        traffic(c, s.port, ledger, new Random(rnd * 100L + thread), "r" + rnd + "t" + thread, dead);
                    } finally {
                        done.countDown();
                    }
                }).start();
            }
            Thread.sleep(400 + r.nextInt(900));
            dead.set(true);
            s.kill();
            assertTrue(done.await(30, TimeUnit.SECONDS), "clients stopped");
            totalAcked = ledger.acked.size();
        }
        Server s = new Server(journal);
        HttpClient c = HttpClient.newHttpClient();
        try {
            verify(c, s.port, ledger);
        } finally {
            s.kill();
        }
        assertTrue(totalAcked > 50, "only " + totalAcked + " orders were acknowledged; the test did too little");
        System.out.println("        (" + totalAcked + " acknowledged orders over 4 kills; " + ledger.inFlight.size() + " changes were in flight)");
    }

    /** One client: place an order, then walk it through a random part of its lifecycle, until the server dies. */
    private static void traffic(HttpClient c, int port, Ledger ledger, Random r, String name, AtomicBoolean dead) {
        int n = 0;
        while (!dead.get()) {
            String key = name + "-" + (n++);
            String sku = SKUS[r.nextInt(2)];                             // mostly the well-stocked SKUs
            String body = "{\"customerId\":\"" + name + "\",\"lines\":[{\"sku\":\"" + sku + "\",\"quantity\":1}]}";
            String id;
            try {
                ledger.keyBody.put(key, body);
                HttpResponse<String> res = send(c, port, "POST", "/orders", body, key);
                if (res.statusCode() != 201) continue;                   // out of stock is fine
                id = field(res.body(), "id");
                ledger.keyToOrder.put(key, id);
                ledger.acked.put(id, "CREATED");
            } catch (Exception serverGone) {
                return;
            }
            String[] path = r.nextBoolean() ? new String[]{"pay", "ship", "deliver"} : new String[]{"pay", "cancel"};
            int steps = 1 + r.nextInt(path.length);
            for (int i = 0; i < steps && !dead.get(); i++) {
                String target = switch (path[i]) {
                    case "pay" -> "PAID";
                    case "ship" -> "SHIPPED";
                    case "deliver" -> "DELIVERED";
                    default -> "CANCELLED";
                };
                ledger.inFlight.put(id, target);
                try {
                    String b = path[i].equals("pay") ? "{\"paymentRef\":\"pay_" + id + "\"}" : null;
                    HttpResponse<String> res = send(c, port, "POST", "/orders/" + id + "/" + path[i], b,
                            path[i].equals("pay") ? "pay-" + id : null);
                    if (res.statusCode() != 200) throw new AssertionError(path[i] + " " + id + " -> " + res.statusCode() + " " + res.body());
                    ledger.acked.put(id, target);
                    ledger.inFlight.remove(id);
                } catch (AssertionError e) {
                    throw e;
                } catch (Exception serverGone) {
                    return;                                              // left in inFlight: may or may not have happened
                }
            }
        }
    }

    private static void verify(HttpClient c, int port, Ledger ledger) throws Exception {
        // 1. every acknowledged order exists, in the acknowledged state or the one that was in flight
        for (Map.Entry<String, String> e : ledger.acked.entrySet()) {
            HttpResponse<String> res = send(c, port, "GET", "/orders/" + e.getKey(), null, null);
            assertEquals(200, res.statusCode());
            String status = field(res.body(), "status");
            String flying = ledger.inFlight.get(e.getKey());
            assertTrue(status.equals(e.getValue()) || status.equals(flying),
                    e.getKey() + " is " + status + " but the client was told " + e.getValue() + (flying == null ? "" : " (in flight: " + flying + ")"));
            if (status.equals(flying)) {                                 // it did happen: from now on that is the truth
                ledger.acked.put(e.getKey(), status);
                ledger.inFlight.remove(e.getKey());
            }
        }
        // 2. retrying any acknowledged place with its key returns the same order, never a new one
        for (Map.Entry<String, String> e : ledger.keyToOrder.entrySet()) {
            HttpResponse<String> res = send(c, port, "POST", "/orders", ledger.keyBody.get(e.getKey()), e.getKey());
            assertEquals(e.getValue(), field(res.body(), "id"));
        }
        // 3. stock still adds up, and matches the orders that exist
        Map<String, Long> reserved = new LinkedHashMap<>(), sold = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        long after = 0;
        while (true) {                                                   // discover every order from the event log
            String body = send(c, port, "GET", "/events?after=" + after + "&limit=1000", null, null).body();
            Matcher m = Pattern.compile("\"seq\":(\\d+),\"type\":\"OrderPlaced\",\"orderId\":\"([^\"]+)\"").matcher(body);
            while (m.find()) ids.add(m.group(2));
            Matcher seqs = Pattern.compile("\"seq\":(\\d+)").matcher(body);
            long last = after;
            while (seqs.find()) {
                long seq = Long.parseLong(seqs.group(1));
                assertEquals(last + 1, seq);                             // no gaps in the event log
                last = seq;
            }
            if (last == after) break;
            after = last;
        }
        for (String id : ids) {
            String body = send(c, port, "GET", "/orders/" + id, null, null).body();
            String status = field(body, "status");
            Matcher l = Pattern.compile("\"sku\":\"([^\"]+)\",\"quantity\":(\\d+)|\"quantity\":(\\d+),\"sku\":\"([^\"]+)\"").matcher(body);
            while (l.find()) {
                String sku = l.group(1) != null ? l.group(1) : l.group(4);
                long q = Long.parseLong(l.group(2) != null ? l.group(2) : l.group(3));
                if (status.equals("CREATED") || status.equals("PAID")) reserved.merge(sku, q, Long::sum);
                if (status.equals("SHIPPED") || status.equals("DELIVERED")) sold.merge(sku, q, Long::sum);
            }
        }
        for (String sku : SKUS) {
            String body = send(c, port, "GET", "/inventory/" + sku, null, null).body();
            long available = Long.parseLong(field(body, "available")), res = Long.parseLong(field(body, "reserved")),
                    sld = Long.parseLong(field(body, "sold"));
            assertEquals(SEEDED.get(sku), available + res + sld);
            assertEquals(reserved.getOrDefault(sku, 0L), res);
            assertEquals(sold.getOrDefault(sku, 0L), sld);
        }
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\"([^\"]*)\"|(-?\\d+))").matcher(json);
        if (!m.find()) throw new AssertionError("no " + name + " in " + json);
        return m.group(2) != null ? m.group(2) : m.group(3);
    }
}
