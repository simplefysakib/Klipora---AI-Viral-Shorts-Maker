package com.viral.shortsmaker.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * 5.3 tests: a local HTTP server that plays Google's Gemini API and Groq's API (a plain socket server, no extra test
 * dependency). Each route ("GET /v1beta/models", "POST gemini", "GET /openai/v1/models", "POST groq-chat:<model>",
 * "POST groq-audio") answers with the planned status + body after an optional delay; every request is recorded.
 */
public final class FakeAiServer implements AutoCloseable {
    public static final class Reply {
        public final int code; public final String body; public final long delayMs;
        public Reply(int code, String body, long delayMs) { this.code = code; this.body = body; this.delayMs = delayMs; }
    }

    public static String geminiKeyInvalid() {
        return "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\","
                + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"API_KEY_INVALID\",\"domain\":\"googleapis.com\"}]}}";
    }
    public static String groqKeyInvalid() {
        return "{\"error\":{\"message\":\"Invalid API Key\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}";
    }
    public static String geminiAnswer(String text) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + org.json.JSONObject.quote(text) + "}]}}]}";
    }
    public static String groqAnswer(String text) {
        return "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":" + org.json.JSONObject.quote(text) + "}}]}";
    }

    private final java.net.ServerSocket server;
    public final Map<String, Reply> plan = new ConcurrentHashMap<>();
    public final List<String> hits = new CopyOnWriteArrayList<>();
    public final List<String> bodies = new CopyOnWriteArrayList<>();
    public final List<String> auth = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.ExecutorService pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "fake-ai"); t.setDaemon(true); return t; });

    public FakeAiServer() throws Exception {
        server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        pool.execute(() -> {
            while (!server.isClosed()) {
                try { java.net.Socket s = server.accept(); pool.execute(() -> handle(s)); } catch (Exception e) { return; }
            }
        });
    }

    public String base() { return "http://127.0.0.1:" + server.getLocalPort(); }

    public void on(String route, int code, String body) { plan.put(route, new Reply(code, body, 0)); }
    public void on(String route, int code, String body, long delayMs) { plan.put(route, new Reply(code, body, delayMs)); }

    public int count(String route) { int n = 0; for (String h : hits) if (h.equals(route)) n++; return n; }

    static String route(String method, String path, String body) {
        if (path.startsWith("/v1beta/models") && path.contains(":generateContent")) return "POST gemini";
        if (path.startsWith("/v1beta/models")) return "GET /v1beta/models";
        if (path.equals("/openai/v1/models")) return "GET /openai/v1/models";
        if (path.equals("/openai/v1/chat/completions")) {
            String model = "?";
            try { model = new org.json.JSONObject(body).optString("model"); } catch (Exception ignored) {}
            return "POST groq-chat:" + model;
        }
        if (path.startsWith("/openai/v1/audio")) return "POST groq-audio";
        return method + " " + path;
    }

    private static String line(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) { if (c == '\n') break; if (c != '\r') b.write(c); }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private void handle(java.net.Socket s) {
        try (java.net.Socket sock = s) {
            InputStream in = new java.io.BufferedInputStream(sock.getInputStream());
            String req = line(in);
            if (req == null) return;
            String[] rp = req.split(" ");
            String method = rp[0], target = rp.length > 1 ? rp[1] : "/";
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            int len = 0; String a = null, g = null, h;
            boolean chunked = false;
            while ((h = line(in)) != null && !h.isEmpty()) {
                int k = h.indexOf(':');
                if (k < 0) continue;
                String name = h.substring(0, k).trim().toLowerCase(java.util.Locale.US), val = h.substring(k + 1).trim();
                if (name.equals("content-length")) len = Integer.parseInt(val);
                if (name.equals("authorization")) a = val;
                if (name.equals("x-goog-api-key")) g = val;
                if (name.equals("transfer-encoding") && val.contains("chunked")) chunked = true;
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (chunked) {
                while (true) {
                    String sz = line(in);
                    if (sz == null) break;
                    int n = Integer.parseInt(sz.trim().split(";")[0], 16);
                    if (n == 0) { line(in); break; }
                    byte[] buf = new byte[n]; int got = 0;
                    while (got < n) { int r = in.read(buf, got, n - got); if (r < 0) break; got += r; }
                    b.write(buf, 0, got);
                    line(in);
                }
            } else {
                byte[] buf = new byte[len]; int got = 0;
                while (got < len) { int r = in.read(buf, got, len - got); if (r < 0) break; got += r; }
                b.write(buf, 0, got);
            }
            String body = b.toString("UTF-8");
            String r = route(method, path, body);
            hits.add(r);
            bodies.add(r + " " + body);
            auth.add(r + " " + (a != null ? a : g));
            Reply p = plan.get(r);
            if (p == null) p = new Reply(404, "{\"error\":{\"message\":\"not found\"}}", 0);
            if (p.delayMs > 0) { try { Thread.sleep(p.delayMs); } catch (InterruptedException ignored) {} }
            byte[] out = p.body.getBytes(StandardCharsets.UTF_8);
            OutputStream os = sock.getOutputStream();
            os.write(("HTTP/1.1 " + p.code + " X\r\nContent-Type: application/json\r\nContent-Length: " + out.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(out);
            os.flush();
        } catch (Exception ignored) {   // the client gave up (time-out test)
        }
    }

    @Override public void close() { try { server.close(); } catch (Exception ignored) {} pool.shutdownNow(); }
}
