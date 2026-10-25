package dev.samridhi.orders;

import static dev.samridhi.orders.testkit.Assert.assertEquals;
import static dev.samridhi.orders.testkit.Assert.assertTrue;

import dev.samridhi.orders.testkit.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/** Talks to a real server on a free port, the way a client would. */
public class HttpApiTests {
    private record Reply(int status, Map<String, Object> body) {}

    private static final class Server implements AutoCloseable {
        final Fixtures.World w = new Fixtures.World().stock("A", 3).stock("B", 10);
        final HttpApi api = new HttpApi(w.service, w.inventory, w.events);
        final HttpClient client = HttpClient.newHttpClient();
        final int port;

        Server() throws Exception {
            port = api.start(0);
        }

        Reply call(String method, String path, String body, String idemKey) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (idemKey != null) b.header("Idempotency-Key", idemKey);
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) Json.parse(r.body());
            return new Reply(r.statusCode(), m);
        }

        @Override
        public void close() {
            api.stop();
        }
    }

    @Test
    void fullOrderLifecycleOverHttp() throws Exception {
        try (Server s = new Server()) {
            Reply placed = s.call("POST", "/orders", "{\"customerId\":\"c1\",\"lines\":[{\"sku\":\"A\",\"quantity\":2}]}", "k1");
            assertEquals(201, placed.status());
            String id = (String) placed.body().get("id");
            assertEquals(200L, placed.body().get("totalCents"));
            assertEquals(200, s.call("POST", "/orders/" + id + "/pay", "{\"paymentRef\":\"pay_1\"}", "pk").status());
            assertEquals("SHIPPED", s.call("POST", "/orders/" + id + "/ship", null, null).body().get("status"));
            assertEquals("DELIVERED", s.call("POST", "/orders/" + id + "/deliver", null, null).body().get("status"));
            assertEquals("DELIVERED", s.call("GET", "/orders/" + id, null, null).body().get("status"));
            Reply inv = s.call("GET", "/inventory/A", null, null);
            assertEquals(List.of(1L, 0L, 2L), List.of(inv.body().get("available"), inv.body().get("reserved"), inv.body().get("sold")));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evs = (List<Map<String, Object>>) s.call("GET", "/events?after=0", null, null).body().get("events");
            assertEquals(List.of("OrderPlaced", "OrderPaid", "OrderShipped", "OrderDelivered"), evs.stream().map(e -> e.get("type")).toList());
            @SuppressWarnings("unchecked")
            List<Object> later = (List<Object>) s.call("GET", "/events?after=3", null, null).body().get("events");
            assertEquals(1, later.size());
        }
    }

    @Test
    void errorsMapToTheRightStatusCodes() throws Exception {
        try (Server s = new Server()) {
            assertEquals(400, s.call("POST", "/orders", "{not json", null).status());
            assertEquals(400, s.call("POST", "/orders", "[1,2]", null).status());
            assertEquals(400, s.call("POST", "/orders", "{\"customerId\":\"c\",\"lines\":[{\"sku\":\"A\",\"quantity\":\"2\"}]}", null).status());
            assertEquals(400, s.call("POST", "/orders", "{\"customerId\":\"c\",\"lines\":[{\"sku\":\"NOPE\",\"quantity\":1}]}", null).status());
            Reply oos = s.call("POST", "/orders", "{\"customerId\":\"c\",\"lines\":[{\"sku\":\"A\",\"quantity\":9}]}", null);
            assertEquals(409, oos.status());
            assertEquals("OUT_OF_STOCK", oos.body().get("error"));
            assertEquals(404, s.call("GET", "/orders/ord_999", null, null).status());
            assertEquals(404, s.call("GET", "/nowhere", null, null).status());
            assertEquals(400, s.call("GET", "/orders", null, null).status());                  // wrong method
            String id = (String) s.call("POST", "/orders", "{\"customerId\":\"c\",\"lines\":[{\"sku\":\"B\",\"quantity\":1}]}", "k").body().get("id");
            assertEquals(409, s.call("POST", "/orders/" + id + "/ship", null, null).status());   // not paid
            assertEquals(422, s.call("POST", "/orders", "{\"customerId\":\"c\",\"lines\":[{\"sku\":\"B\",\"quantity\":2}]}", "k").status());
            assertEquals(413, s.call("POST", "/orders", "{\"customerId\":\"" + "x".repeat(70_000) + "\"}", null).status());
        }
    }

    @Test
    void idempotentRetryOverHttpReturnsTheSameOrder() throws Exception {
        try (Server s = new Server()) {
            String body = "{\"customerId\":\"c1\",\"lines\":[{\"sku\":\"B\",\"quantity\":4}]}";
            Reply a = s.call("POST", "/orders", body, "retry-me");
            Reply b = s.call("POST", "/orders", body, "retry-me");
            assertEquals(a, b);
            assertTrue(s.w.service.orderCount() == 1, "one order despite two requests");
        }
    }
}
