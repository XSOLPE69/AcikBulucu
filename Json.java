package tr.acikbulucuai;

import java.util.*;

/** Küçük, bağımlılıksız JSON okuyucu/yazıcı. */
final class Json {
    private final String s;
    private int i;
    private int depth;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("JSON: boş");
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw j.err("fazladan veri");
        return v;
    }

    private IllegalArgumentException err(String m) { return new IllegalArgumentException("JSON: " + m + " @" + i); }
    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private Object value() {
        if (i >= s.length()) throw err("beklenmedik son");
        if (++depth > 100) throw err("çok derin");
        try {
            switch (s.charAt(i)) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': lit("true"); return Boolean.TRUE;
                case 'f': lit("false"); return Boolean.FALSE;
                case 'n': lit("null"); return null;
                default: return number();
            }
        } finally { depth--; }
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw err("geçersiz değer");
        i += w.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("anahtar bekleniyor");
            String k = string(); ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err(": bekleniyor");
            i++; ws();
            m.put(k, value()); ws();
            if (i >= s.length()) throw err("beklenmedik son");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err(", veya } bekleniyor");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value()); ws();
            if (i >= s.length()) throw err("beklenmedik son");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw err(", veya ] bekleniyor");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw err("string kapanmadı");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw err("kaçış");
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("\\u");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw err("geçersiz kaçış");
            }
        }
    }

    private Object number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw err("beklenmedik karakter");
        try { return Double.parseDouble(s.substring(st, i)); }
        catch (NumberFormatException e) { throw err("sayı"); }
    }

    // ---- yazma ----
    static String write(Object o) { StringBuilder b = new StringBuilder(); w(b, o); return b.toString(); }

    @SuppressWarnings("unchecked")
    private static void w(StringBuilder b, Object o) {
        if (o == null) b.append("null");
        else if (o instanceof String) quote(b, (String) o);
        else if (o instanceof Boolean) b.append(o);
        else if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d); else b.append(d);
        } else if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, e.getKey()); b.append(':'); w(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof Iterable) {
            b.append('[');
            boolean first = true;
            for (Object x : (Iterable<Object>) o) { if (!first) b.append(','); first = false; w(b, x); }
            b.append(']');
        } else quote(b, o.toString());
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        b.append('"');
    }

    // ---- yardımcılar ----
    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }
    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : null; }
    static String str(Object o) { return (o instanceof String || o instanceof Number || o instanceof Boolean) ? String.valueOf(o) : null; }
    static int integer(Object o, int def) { return o instanceof Number ? ((Number) o).intValue() : def; }
}
