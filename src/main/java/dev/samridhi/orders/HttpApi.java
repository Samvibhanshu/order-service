package dev.samridhi.orders;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON over HTTP using the JDK's built-in server.
 *
 * <pre>
 * POST /orders                 {"customerId":"c1","lines":[{"sku":"BOOK-1","quantity":2}]}   201
 * GET  /orders/{id}                                                                      200
 * POST /orders/{id}/pay        {"paymentRef":"pay_123"}                                   200
 * POST /orders/{id}/ship | /deliver | /cancel                                            200
 * GET  /inventory/{sku}        {"available":..,"reserved":..,"sold":..}                  200
 * GET  /events?after=0&amp;limit=100                                                       200
 * </pre>
 * POST /orders and /pay accept an {@code Idempotency-Key} header. Errors are {@code {"error": CODE, "message": ...}}
 * with 400 (VALIDATION), 404, 409 (INVALID_STATE, OUT_OF_STOCK), 422 (IDEMPOTENCY_MISMATCH) or 413.
 */
public final class HttpApi {
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private static final Pattern ORDER = Pattern.compile("^/orders/([A-Za-z0-9_-]+)$");
    private static final Pattern ORDER_ACTION = Pattern.compile("^/orders/([A-Za-z0-9_-]+)/(pay|ship|deliver|cancel)$");
    private static final Pattern INVENTORY = Pattern.compile("^/inventory/([A-Za-z0-9_.-]+)$");

    private final OrderService service;
    private final Inventory inventory;
    private final EventLog events;
    private HttpServer server;
    private ExecutorService pool;

    public HttpApi(OrderService service, Inventory inventory, EventLog events) {
        this.service = service;
        this.inventory = inventory;
        this.events = events;
    }

    /** Starts on {@code port} (0 picks a free port) and returns the port actually used. */
    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 128);
        pool = Executors.newFixedThreadPool(16);
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    private record Response(int status, Object body) {}

    private void handle(HttpExchange ex) throws IOException {
        Response r;
        try {
            r = route(ex);
        } catch (OrderException e) {
            r = new Response(statusFor(e.code()), error(e.code().name(), e.getMessage()));
        } catch (BodyTooLarge e) {
            r = new Response(413, error("TOO_LARGE", "request body over " + MAX_BODY_BYTES + " bytes"));
        } catch (RuntimeException e) {
            r = new Response(500, error("INTERNAL", "unexpected error"));     // never leak stack traces to clients
        }
        byte[] out = Json.write(r.body()).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(r.status(), out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    static int statusFor(OrderException.Code code) {
        return switch (code) {
            case VALIDATION -> 400;
            case NOT_FOUND -> 404;
            case INVALID_STATE, OUT_OF_STOCK -> 409;
            case IDEMPOTENCY_MISMATCH -> 422;
        };
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("message", message);
        return m;
    }

    private Response route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        Matcher m;
        if (path.equals("/orders")) {
            requireMethod(method, "POST");
            Map<String, Object> body = jsonObject(ex);
            Order o = service.placeOrder(ex.getRequestHeaders().getFirst("Idempotency-Key"),
                    stringField(body, "customerId"), lines(body.get("lines")));
            return new Response(201, toJson(o));
        }
        if ((m = ORDER.matcher(path)).matches()) {
            requireMethod(method, "GET");
            return new Response(200, toJson(service.get(m.group(1))));
        }
        if ((m = ORDER_ACTION.matcher(path)).matches()) {
            requireMethod(method, "POST");
            String id = m.group(1);
            Order o = switch (m.group(2)) {
                case "pay" -> service.pay(ex.getRequestHeaders().getFirst("Idempotency-Key"), id,
                        stringField(jsonObject(ex), "paymentRef"));
                case "ship" -> service.ship(id);
                case "deliver" -> service.deliver(id);
                default -> service.cancel(id);
            };
            return new Response(200, toJson(o));
        }
        if ((m = INVENTORY.matcher(path)).matches()) {
            requireMethod(method, "GET");
            Inventory.Level l = inventory.level(m.group(1));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sku", m.group(1));
            out.put("available", l.available());
            out.put("reserved", l.reserved());
            out.put("sold", l.sold());
            return new Response(200, out);
        }
        if (path.equals("/events")) {
            requireMethod(method, "GET");
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            long after = parseLong(q.getOrDefault("after", "0"), "after");
            int limit = (int) Math.min(1000, Math.max(1, parseLong(q.getOrDefault("limit", "100"), "limit")));
            List<Object> list = new ArrayList<>();
            for (EventLog.Event e : events.after(after, limit)) {
                Map<String, Object> j = new LinkedHashMap<>();
                j.put("seq", e.seq());
                j.put("type", e.type());
                j.put("orderId", e.orderId());
                j.put("data", e.data());
                list.add(j);
            }
            return new Response(200, Map.of("events", list));
        }
        return new Response(404, error("NOT_FOUND", "no route " + path));
    }

    private static final class BodyTooLarge extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static void requireMethod(String actual, String expected) {
        if (!expected.equals(actual)) throw new OrderException(OrderException.Code.VALIDATION, "use " + expected);
    }

    private static Map<String, Object> jsonObject(HttpExchange ex) throws IOException {
        byte[] raw;
        try (InputStream in = ex.getRequestBody()) {
            raw = in.readNBytes(MAX_BODY_BYTES + 1);                     // bounded read: never buffer an unbounded body
        }
        if (raw.length > MAX_BODY_BYTES) throw new BodyTooLarge();
        Object v = Json.parse(new String(raw, StandardCharsets.UTF_8));
        if (!(v instanceof Map<?, ?> map)) throw new OrderException(OrderException.Code.VALIDATION, "body must be a JSON object");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) map;
        return m;
    }

    private static String stringField(Map<String, Object> body, String name) {
        Object v = body.get(name);
        if (!(v instanceof String s) || s.isBlank()) throw new OrderException(OrderException.Code.VALIDATION, name + " must be a non-empty string");
        return s;
    }

    private static List<OrderLine> lines(Object raw) {
        if (!(raw instanceof List<?> list)) throw new OrderException(OrderException.Code.VALIDATION, "lines must be an array");
        List<OrderLine> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> line)) throw new OrderException(OrderException.Code.VALIDATION, "each line must be an object");
            Object sku = line.get("sku"), qty = line.get("quantity");
            if (!(sku instanceof String s) || !(qty instanceof Long q)) {
                throw new OrderException(OrderException.Code.VALIDATION, "each line needs a string sku and an integer quantity");
            }
            out.add(new OrderLine(s, q));
        }
        return out;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new LinkedHashMap<>();
        if (raw == null) return q;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) q.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return q;
    }

    private static long parseLong(String s, String name) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new OrderException(OrderException.Code.VALIDATION, name + " must be an integer");
        }
    }

    static Map<String, Object> toJson(Order o) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("id", o.id());
        j.put("customerId", o.customerId());
        List<Object> lines = new ArrayList<>();
        for (OrderLine l : o.lines()) lines.add(Map.of("sku", l.sku(), "quantity", l.quantity()));
        j.put("lines", lines);
        j.put("totalCents", o.totalCents());
        j.put("status", o.status());
        j.put("paymentRef", o.paymentRef());
        j.put("version", o.version());
        return j;
    }
}
