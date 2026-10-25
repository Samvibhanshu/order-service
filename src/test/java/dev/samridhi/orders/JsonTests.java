package dev.samridhi.orders;

import static dev.samridhi.orders.testkit.Assert.assertEquals;
import static dev.samridhi.orders.testkit.Assert.assertThrows;

import dev.samridhi.orders.testkit.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class JsonTests {
    @Test
    void parsesAllValueKinds() {
        Object v = Json.parse(" {\"a\": [1, -2, 3.5, true, false, null], \"b\": {\"c\": \"x\\\"y\\n\\u00e9\"}, \"d\": {}} ");
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a", java.util.Arrays.asList(1L, -2L, 3.5, true, false, null));
        expected.put("b", Map.of("c", "x\"y\né"));
        expected.put("d", Map.of());
        assertEquals(expected, v);
    }

    @Test
    void rejectsMalformedInput() {
        String[] bad = {"", "{", "{\"a\":}", "{\"a\" 1}", "[1,]", "[1 2]", "\"open", "\"bad \\q escape\"", "01", "1.", "-",
            "{\"a\":1} trailing", "tru", "{a:1}", "\"tab\there\"", "99999999999999999999"};
        for (String b : bad) assertThrows(OrderException.class, () -> Json.parse(b));
    }

    @Test
    void deepNestingIsRefusedInsteadOfOverflowingTheStack() {
        String deep = "[".repeat(100_000) + "]".repeat(100_000);
        assertThrows(OrderException.class, () -> Json.parse(deep));
        String ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH);
        Json.parse(ok);
    }

    @Test
    void writeThenParseIsTheIdentityOnRandomDocuments() {
        Random rnd = new Random(7);
        for (int t = 0; t < 2000; t++) {
            Object doc = random(rnd, 0);
            assertEquals(doc, Json.parse(Json.write(doc)));
        }
    }

    private static Object random(Random rnd, int depth) {
        int kind = depth > 3 ? rnd.nextInt(4) : rnd.nextInt(6);
        switch (kind) {
            case 0: return rnd.nextLong();
            case 1: {
                StringBuilder b = new StringBuilder();
                for (int i = rnd.nextInt(8); i > 0; i--) b.append((char) (rnd.nextBoolean() ? 32 + rnd.nextInt(95) : rnd.nextInt(0x3000)));
                return b.toString();
            }
            case 2: return rnd.nextBoolean();
            case 3: return null;
            case 4: {
                List<Object> a = new java.util.ArrayList<>();
                for (int i = rnd.nextInt(4); i > 0; i--) a.add(random(rnd, depth + 1));
                return a;
            }
            default: {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = rnd.nextInt(4); i > 0; i--) m.put("k" + rnd.nextInt(100), random(rnd, depth + 1));
                return m;
            }
        }
    }
}
