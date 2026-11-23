package tr.acikbulucuai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Google Gemini REST istemcisi (generateContent). */
final class Gemini {
    static volatile String BASE = "https://generativelanguage.googleapis.com/v1beta";

    static final class ApiException extends IOException {
        final int status;
        ApiException(int status, String msg) { super(msg); this.status = status; }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static void checkModel(String model) {
        if (model == null || !model.matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("Geçersiz model adı.");
    }

    /** turns: {rol("user"|"model"), metin}. Modelin ürettiği metni döndürür. */
    static String generate(String key, String model, String system, List<String[]> turns, int timeoutSec)
            throws IOException, InterruptedException {
        checkModel(model);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
        List<Object> contents = new ArrayList<>();
        for (String[] t : turns) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("role", t[0]);
            c.put("parts", List.of(Map.of("text", t[1])));
            contents.add(c);
        }
        body.put("contents", contents);
        Map<String, Object> gen = new LinkedHashMap<>();
        gen.put("temperature", 0.2);
        gen.put("maxOutputTokens", 8192);
        gen.put("responseMimeType", "application/json");
        body.put("generationConfig", gen);

        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/models/" + model + ":generateContent"))
                .timeout(Duration.ofSeconds(Math.max(10, timeoutSec)))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new ApiException(r.statusCode(), errorText(r.body()));

        Map<String, Object> m = Json.obj(Json.parse(r.body()));
        List<Object> cands = m == null ? null : Json.arr(m.get("candidates"));
        if (cands == null || cands.isEmpty()) {
            Map<String, Object> pf = m == null ? null : Json.obj(m.get("promptFeedback"));
            String why = pf == null ? null : Json.str(pf.get("blockReason"));
            throw new IOException("Model cevap vermedi" + (why != null ? " (engellendi: " + why + ")" : "."));
        }
        Map<String, Object> content = Json.obj(Json.obj(cands.get(0)).get("content"));
        List<Object> parts = content == null ? null : Json.arr(content.get("parts"));
        StringBuilder out = new StringBuilder();
        if (parts != null) {
            for (Object p : parts) {
                Map<String, Object> pm = Json.obj(p);
                if (pm == null || Boolean.TRUE.equals(pm.get("thought"))) continue;
                String t = Json.str(pm.get("text"));
                if (t != null) out.append(t);
            }
        }
        if (out.length() == 0) throw new IOException("Model boş cevap döndürdü.");
        return out.toString();
    }

    /** generateContent destekleyen model adları. */
    static List<String> listModels(String key, int timeoutSec) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/models?pageSize=200"))
                .timeout(Duration.ofSeconds(Math.max(10, timeoutSec)))
                .header("x-goog-api-key", key).GET().build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new ApiException(r.statusCode(), errorText(r.body()));
        Map<String, Object> m = Json.obj(Json.parse(r.body()));
        List<String> names = new ArrayList<>();
        List<Object> ms = m == null ? null : Json.arr(m.get("models"));
        if (ms != null) for (Object o : ms) {
            Map<String, Object> mm = Json.obj(o);
            if (mm == null) continue;
            List<Object> meth = Json.arr(mm.get("supportedGenerationMethods"));
            String n = Json.str(mm.get("name"));
            if (n != null && meth != null && meth.contains("generateContent"))
                names.add(n.startsWith("models/") ? n.substring(7) : n);
        }
        return names;
    }

    private static String errorText(String body) {
        try {
            Map<String, Object> m = Json.obj(Json.parse(body));
            Map<String, Object> e = m == null ? null : Json.obj(m.get("error"));
            String msg = e == null ? null : Json.str(e.get("message"));
            if (msg != null) return msg.length() > 300 ? msg.substring(0, 300) : msg;
        } catch (RuntimeException ignored) { }
        return body == null ? "" : (body.length() > 200 ? body.substring(0, 200) : body);
    }
}
