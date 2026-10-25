package dev.samridhi.orders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader and writer, so the service needs nothing but the JDK.
 * Objects become {@code LinkedHashMap}, arrays {@code List}, integers {@code Long}, other numbers {@code Double}.
 * Rejects trailing garbage, unterminated strings, bad escapes and nesting deeper than {@value #MAX_DEPTH}
 * (a deeply nested body would otherwise overflow the stack: a cheap denial-of-service).
 */
public final class Json {
    public static final int MAX_DEPTH = 64;

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        if (text == null) throw error("empty body", 0);
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) throw error("unexpected trailing characters", p.i);
        return v;
    }

    private static OrderException error(String msg, int at) {
        return new OrderException(OrderException.Code.VALIDATION, "invalid JSON: " + msg + " at position " + at);
    }

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw error("nesting too deep", i);
        if (i >= s.length()) throw error("unexpected end", i);
        char c = s.charAt(i);
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') return string();
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        throw error("unexpected character '" + c + "'", i);
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw error("expected a key", i);
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value(depth + 1));
            ws();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array(int depth) {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
        while (true) {
            ws();
            a.add(value(depth + 1));
            ws();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            expect(']');
            return a;
        }
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) throw error("expected '" + c + "'", i);
        i++;
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;                                                               // opening quote
        while (true) {
            if (i >= s.length()) throw error("unterminated string", i);
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw error("control character in string", i - 1);
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw error("unterminated escape", i);
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("short \\u escape", i);
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad \\u escape", i);
                    }
                    i += 4;
                }
                default -> throw error("bad escape \\" + e, i - 1);
            }
        }
    }

    private Object number() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(start, i);
        try {
            if (t.matches("-?(0|[1-9]\\d*)")) return Long.parseLong(t);
            if (t.matches("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")) return Double.parseDouble(t);
        } catch (NumberFormatException ex) {
            // too large for a long: fall through
        }
        throw error("bad number " + t, start);
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(v, b);
        return b.toString();
    }

    private static void write(Object v, StringBuilder b) {
        if (v == null) b.append("null");
        else if (v instanceof String str) quote(str, b);
        else if (v instanceof Number || v instanceof Boolean) b.append(v);
        else if (v instanceof Enum<?> en) quote(en.name(), b);
        else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), b);
                b.append(':');
                write(e.getValue(), b);
            }
            b.append('}');
        } else if (v instanceof Iterable<?> it) {
            b.append('[');
            boolean first = true;
            for (Object x : it) {
                if (!first) b.append(',');
                first = false;
                write(x, b);
            }
            b.append(']');
        } else throw new IllegalArgumentException("cannot write " + v.getClass());
    }

    private static void quote(String str, StringBuilder b) {
        b.append('"');
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
