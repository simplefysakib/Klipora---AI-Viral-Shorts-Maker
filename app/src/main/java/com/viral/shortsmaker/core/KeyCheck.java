package com.viral.shortsmaker.core;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Live check of the user's Gemini / Groq API keys with one tiny request each (the key's models list), a short time
 * limit (~5 s), and a small per-key memory: a good answer is kept for 10 minutes (repeated taps on Generate stay
 * instant), a rejected key is remembered until the key changes (or 10 minutes pass), a network failure for 30 s.
 * Used when the keys are saved (onboarding / Settings), before a job starts (Generate) and by the AI step's fallback
 * (Gemini rejected -> Groq, Groq rejected -> Gemini).
 *
 * <p>A check NEVER blocks the user when the network is down or Google/Groq answer something unexpected: only a clear
 * "this key is not valid" answer (400 API_KEY_INVALID / 401 / 403) counts as INVALID.
 */
public final class KeyCheck {
    private KeyCheck() {}

    public enum Service {
        GEMINI("Gemini"), GROQ("Groq");
        public final String label;
        Service(String l) { label = l; }
    }

    public enum Result { OK, INVALID, UNREACHABLE }

    public static final class Verdict {
        public final Result result;
        public final int code;          // HTTP status, 0 = no answer
        public final String message;    // the provider's own words (or the network error), never null
        public final boolean cached;
        Verdict(Result r, int code, String message, boolean cached) { this.result = r; this.code = code; this.message = message == null ? "" : message; this.cached = cached; }
        public boolean ok() { return result == Result.OK; }
        public boolean invalid() { return result == Result.INVALID; }
        Verdict asCached() { return new Verdict(result, code, message, true); }
        @Override public String toString() { return result + (code > 0 ? " " + code : "") + (message.isEmpty() ? "" : " (" + message + ")"); }
    }

    // test hooks: base URLs (a local fake server in tests) and the clock / limits
    static volatile String GEMINI_BASE = "https://generativelanguage.googleapis.com";
    static volatile String GROQ_BASE = "https://api.groq.com";
    static volatile long TIMEOUT_MS = 5000;
    public static final long GOOD_TTL_MS = 10 * 60_000L;
    static final long BAD_TTL_MS = 10 * 60_000L;
    static final long UNREACHABLE_TTL_MS = 30_000L;

    private static final class Entry { final Verdict v; final long until; Entry(Verdict v, long until) { this.v = v; this.until = until; } }
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();

    static String id(Service s, String key) {
        String k = key == null ? "" : key.trim();
        return s.name() + ":" + Integer.toHexString(k.hashCode()) + ":" + k.length() + ":" + (k.length() > 8 ? k.substring(k.length() - 4) : "");
    }

    /** the remembered verdict for this key (null = none / expired) */
    public static Verdict cached(Service s, String key) {
        Entry e = CACHE.get(id(s, key));
        if (e == null) return null;
        if (System.currentTimeMillis() > e.until) { CACHE.remove(id(s, key)); return null; }
        return e.v.asCached();
    }

    static void remember(Service s, String key, Verdict v) {
        long ttl = v.result == Result.OK ? GOOD_TTL_MS : v.result == Result.INVALID ? BAD_TTL_MS : UNREACHABLE_TTL_MS;
        CACHE.put(id(s, key), new Entry(v, System.currentTimeMillis() + ttl));
    }

    /** "we couldn't check" (no connection / time-out) */
    public static Verdict unreachable(String why) { return new Verdict(Result.UNREACHABLE, 0, why, false); }

    /** the AI step learned (mid-job) that the provider rejects this key */
    public static void markInvalid(Service s, String key, int code, String message) { remember(s, key, new Verdict(Result.INVALID, code, message, false)); }

    /** true when this key was rejected recently (a check or a real request) */
    public static boolean knownInvalid(Service s, String key) { Verdict v = cached(s, key); return v != null && v.invalid(); }

    public static void forget() { CACHE.clear(); }

    /** test hook: point the checks (and the AI calls) at a local fake server; null = the real hosts */
    public static void setBasesForTest(String gemini, String groq, long timeoutMs) {
        GEMINI_BASE = gemini == null ? "https://generativelanguage.googleapis.com" : gemini;
        GROQ_BASE = groq == null ? "https://api.groq.com" : groq;
        TIMEOUT_MS = timeoutMs > 0 ? timeoutMs : 5000;
    }

    /** checks the key now, or answers from memory (good: 10 min; rejected: 10 min; no connection: 30 s) */
    public static Verdict check(Service s, String key) { return check(s, key, false); }

    public static Verdict check(Service s, String key, boolean fresh) {
        if (!fresh) { Verdict c = cached(s, key); if (c != null) return c; }
        Verdict v = s == Service.GEMINI ? requestGemini(key) : requestGroq(key);
        remember(s, key, v);
        return v;
    }

    private static OkHttpClient http() {
        OkHttpClient base;
        try { base = Net.fast(); } catch (Throwable t) { base = new OkHttpClient(); }
        return base.newBuilder().callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build();
    }

    static Verdict requestGemini(String key) {
        Request req = new Request.Builder().url(GEMINI_BASE + "/v1beta/models?pageSize=1")
                .header("x-goog-api-key", key == null ? "" : key.trim()).get().build();
        return run(Service.GEMINI, req);
    }

    static Verdict requestGroq(String key) {
        Request req = new Request.Builder().url(GROQ_BASE + "/openai/v1/models")
                .header("Authorization", "Bearer " + (key == null ? "" : key.trim())).get().build();
        return run(Service.GROQ, req);
    }

    private static Verdict run(Service s, Request req) {
        try (Response r = http().newCall(req).execute()) {
            String body = r.body() != null ? r.body().string() : "";
            return classify(s, r.code(), body);
        } catch (Exception e) {
            return new Verdict(Result.UNREACHABLE, 0, AiClient.describe(e), false);
        }
    }

    /**
     * What an answer means for the key. INVALID only for a clear key rejection: Gemini 400 API_KEY_INVALID (or an "API
     * key" message), 401, 403 (except a region block); Groq 401 / 403 (invalid_api_key, blocked account). Anything else
     * (429 busy, 5xx, a region block, an odd answer) is UNREACHABLE: we couldn't tell, so nobody is blocked.
     */
    public static Verdict classify(Service s, int code, String body) {
        String msg = AiClient.errorMessage(body == null ? "" : body);
        String all = ((body == null ? "" : body) + " " + msg).toLowerCase(Locale.US);
        if (code >= 200 && code < 300) return new Verdict(Result.OK, code, "", false);
        boolean region = all.contains("location is not supported") || all.contains("not available in your country") || all.contains("unsupported_country");
        if (region) return new Verdict(Result.UNREACHABLE, code, msg, false);
        if (s == Service.GEMINI) {
            if (code == 400 && (all.contains("api_key_invalid") || all.contains("api key"))) return new Verdict(Result.INVALID, code, msg, false);
            if (code == 401 || code == 403) return new Verdict(Result.INVALID, code, msg, false);
        } else {
            if (code == 401 || code == 403) return new Verdict(Result.INVALID, code, msg, false);
            if (code == 400 && all.contains("invalid_api_key")) return new Verdict(Result.INVALID, code, msg, false);
        }
        return new Verdict(Result.UNREACHABLE, code, msg, false);
    }

    // ------------------------------------------------------------------ Generate pre-flight

    /** what the Generate button does after the checks */
    public enum Preflight {
        /** start the job (both keys fine, or we couldn't check: never block on a network problem) */
        START,
        /** popup: the Gemini key is not valid (the Groq key is fine or unknown) */
        GEMINI_INVALID,
        /** popup: the Groq key is not valid (it does the transcript) */
        GROQ_INVALID,
        /** popup: both keys are not valid (the Gemini popup, naming the Groq key too) */
        BOTH_INVALID
    }

    /**
     * The decision (documented choice): any key that the provider clearly rejects shows its popup and the job does not
     * start (Cancel = don't start). A bad Groq key blocks because Groq makes the transcript; a bad Gemini key blocks too
     * even though the AI step could fall back to Groq: the user asked to be told at once, and Gemini picks the best clips.
     * A key we could not check (no connection, time-out, busy, odd answer) never blocks: the job starts as normal and the
     * mid-job fallback still covers a key that turns out bad.
     */
    public static Preflight decide(Verdict gemini, Verdict groq) {
        boolean g = gemini != null && gemini.invalid(), q = groq != null && groq.invalid();
        if (g && q) return Preflight.BOTH_INVALID;
        if (g) return Preflight.GEMINI_INVALID;
        if (q) return Preflight.GROQ_INVALID;
        return Preflight.START;
    }

    /** both keys checked AT THE SAME TIME (worst case ~5 s, cached answers instantly); call off the main thread */
    public static Preflight preflight(String geminiKey, String groqKey) {
        Verdict[] out = new Verdict[2];
        Thread t = new Thread(() -> out[1] = check(Service.GROQ, groqKey), "key-check-groq");
        t.setDaemon(true);
        t.start();
        out[0] = check(Service.GEMINI, geminiKey);
        try { t.join(TIMEOUT_MS + 1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        return decide(out[0], out[1]);
    }

    /** true when both keys are remembered as good (Generate goes on without any wait) */
    public static boolean bothKnownGood(String geminiKey, String groqKey) {
        Verdict a = cached(Service.GEMINI, geminiKey), b = cached(Service.GROQ, groqKey);
        return a != null && a.ok() && b != null && b.ok();
    }

    // ------------------------------------------------------------------ user-facing words

    /** "Key not valid" text for a key field (Settings / onboarding), or null when the field needs no message */
    public static String fieldMessage(Verdict v) {
        if (v == null) return null;
        if (v.invalid()) return "Key not valid. Create a new key and paste it here.";
        return null;
    }

    /** the save-time summary line (toast): saved + what the check found */
    public static String saveSummary(Verdict gemini, Verdict groq) {
        boolean offline = (gemini != null && gemini.result == Result.UNREACHABLE) || (groq != null && groq.result == Result.UNREACHABLE);
        boolean good = gemini != null && gemini.ok() && groq != null && groq.ok();
        if (good) return "Keys saved and checked";
        if (offline) return "Keys saved. Couldn't check the keys (no connection)";
        return "Keys saved";
    }

    /** an error line for the user: a doubled period ("key.. Update") becomes one; an ellipsis ("...") stays */
    public static String tidy(String s) {
        if (s == null) return null;
        return s.replaceAll("(?<!\\.)\\.\\.(?!\\.)", ".");
    }

    /** a sentence that ends with exactly one period: "Please pass a valid API key." + "." never becomes ".." */
    public static String sentence(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1).trim();
        return t.isEmpty() ? "" : t + ".";
    }
}
