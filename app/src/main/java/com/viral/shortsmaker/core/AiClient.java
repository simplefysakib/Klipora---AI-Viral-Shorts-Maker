package com.viral.shortsmaker.core;

import android.content.Context;

import com.viral.shortsmaker.PromptManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Groq Whisper + Gemini calls, made synchronously on the pipeline thread with real backoff. */
public final class AiClient {
    public static final class Candidate {
        public double start, end;
        public int startLine = -1, endLine = -1;
        public int score;
        public String reason = "";
        public String title = "", hook = "";
        /** v7 sub-scores 0-100 (-1 = not given) */
        public int hookScore = -1, standaloneScore = -1, valueScore = -1, payoffScore = -1;
        /** v11: clip type ("qa", "story", "opinion", "fact", "funny", "other"), the transcript line where the question
         *  starts (-1 = none) and whether a viewer needs earlier context to follow it */
        public String type = "";
        public int questionLine = -1;
        public boolean needsContext = false;
        /** v14: the AI's vote on whether the whole video is a podcast/interview (null = not given) */
        public Boolean podcastVote = null;
        public boolean aiProfanity = false;   // v12: the AI says the clip has gaali (the app also checks the words itself)
        /** v18: the clip's last sentence as the AI copied it, why the thought is complete there, whole topic finished? */
        public String endText = "", endReason = "";
        public Boolean topicComplete = null;
        public boolean isQa() { return "qa".equals(type); }
    }

    public static class ApiException extends Exception {
        public ApiException(String m) { super(m); }
    }

    /**
     * The provider rejected the user's API key (Gemini 400 API_KEY_INVALID / 401 / 403, Groq 401 / 403). The message keeps
     * the words "API key" (the job's fatal-error checks and the race's stop rule read them).
     */
    public static final class KeyRejected extends ApiException {
        public final KeyCheck.Service service;
        public final int code;
        public final String providerMessage;
        public KeyRejected(KeyCheck.Service s, int code, String providerMessage, String m) { super(m); this.service = s; this.code = code; this.providerMessage = providerMessage == null ? "" : providerMessage; }
    }

    /** the user-facing text for a rejected Gemini key: one period between sentences, never ".." */
    static KeyRejected geminiRejected(int code, String msg) {
        String m = KeyCheck.sentence(msg);
        return new KeyRejected(KeyCheck.Service.GEMINI, code, msg, "Gemini rejected your API key" + (m.isEmpty() ? " (" + code + ")." : ": " + m) + " Update it in Settings.");
    }

    static KeyRejected groqRejected(int code, String msg) {
        return new KeyRejected(KeyCheck.Service.GROQ, code, msg, "Groq rejected your API key (" + code + "). Update it in Settings.");
    }

    /** one line in the log and in the job's result when the other provider did a step because a key was rejected */
    public static final String GEMINI_FALLBACK_NOTE = "Your Gemini key isn't valid — used Groq instead. Update it in Settings for best results.";
    public static final String GROQ_FALLBACK_NOTE = "Your Groq key isn't valid — used Gemini instead. Update it in Settings for best results.";

    /** Groq answered 413: this piece is too big (size or audio length) for the key's plan. */
    public static final class TooLargeException extends ApiException {
        public TooLargeException(String m) { super(m); }
    }

    private static final String[] GEMINI_PREFERRED = {
            "gemini-2.5-flash-lite", "gemini-flash-lite-latest", "gemini-2.0-flash-lite", "gemini-2.0-flash",
            "gemini-2.5-flash", "gemini-flash-latest"
    };

    private final Context ctx;
    private final CancelToken ct;

    public AiClient(Context c, CancelToken ct) { this.ctx = c.getApplicationContext(); this.ct = ct; }

    private void sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { ct.check(); try { Thread.sleep(200); } catch (InterruptedException ignored) {} }
    }

    static String mime(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        if (n.endsWith(".webm")) return "audio/webm";
        if (n.endsWith(".ogg") || n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".flac")) return "audio/flac";
        return "audio/mp4";
    }

    static String errorMessage(String body) {
        try { return new JSONObject(body).getJSONObject("error").optString("message", ""); } catch (Exception e) { return ""; }
    }

    // ------------------------------------------------------------------ Groq Whisper

    /** Transcribe with segment + WORD timestamps (needed for line-synced captions). */
    /**
     * v8: language check on a short sample: whisper-large-v3 (better at telling languages apart than turbo), plain
     * transcription, no language given. Returns Whisper's language name ("hindi") or null.
     */
    public String detectLanguage(File sample) throws Exception {
        String key = Prefs.groqKey(ctx);
        if (key == null || key.isEmpty()) return null;
        MultipartBody b = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", sample.getName(), RequestBody.create(sample, MediaType.parse(mime(sample))))
                .addFormDataPart("model", "whisper-large-v3")
                .addFormDataPart("temperature", "0")
                .addFormDataPart("response_format", "verbose_json").build();
        Request req = new Request.Builder().url(KeyCheck.GROQ_BASE + "/openai/v1/audio/transcriptions")
                .header("Authorization", "Bearer " + key).post(b).build();
        okhttp3.Call call = Net.ai().newCall(req);
        if (ct != null) { ct.calls.add(call); if (ct.cancelled) call.cancel(); }
        try (Response r = call.execute()) {
            String body = r.body() != null ? r.body().string() : "";
            Quota.groq(ctx, r);
            if (!r.isSuccessful()) return null;
            return new JSONObject(body).optString("language", null);
        } finally { if (ct != null) ct.calls.remove(call); }
    }

    public Transcript transcribe(File audio, String language) throws Exception { return transcribe(audio, language, null); }

    /** Request body that reports bytes written (real upload progress). */
    static RequestBody counting(RequestBody inner, java.util.function.LongConsumer cb) {
        if (cb == null) return inner;
        return new RequestBody() {
            @Override public MediaType contentType() { return inner.contentType(); }
            @Override public long contentLength() throws java.io.IOException { return inner.contentLength(); }
            @Override public void writeTo(okio.BufferedSink sink) throws java.io.IOException {
                final long[] done = {0};
                okio.BufferedSink counted = okio.Okio.buffer(new okio.ForwardingSink(sink) {
                    @Override public void write(okio.Buffer source, long byteCount) throws java.io.IOException {
                        super.write(source, byteCount);
                        done[0] += byteCount;
                        cb.accept(done[0]);
                    }
                });
                inner.writeTo(counted);
                counted.flush();
            }
        };
    }

    public Transcript transcribe(File audio, String language, java.util.function.LongConsumer uploaded) throws Exception {
        return transcribe(audio, language, uploaded, 0);
    }

    public Transcript transcribe(File audio, String language, java.util.function.LongConsumer uploaded, double temperature) throws Exception {
        // v9: Hindi goes to whisper-large-v3 first (turbo tends to turn Hindi speech into English text)
        // code 31: every language other than English too (Bengali songs: turbo is weakest outside English)
        boolean nonEnglish = language != null && !language.isEmpty() && !"en".equals(language);
        String[] models = nonEnglish ? new String[]{"whisper-large-v3", "whisper-large-v3-turbo"}
                : new String[]{"whisper-large-v3-turbo", "whisper-large-v3"};
        return transcribe(audio, language, uploaded, temperature, models, null);
    }

    /**
     * v9: one short's own audio, for its captions: whisper-large-v3 (most accurate), language locked, word + segment
     * timestamps. {@code prompt} (optional) nudges the script, e.g. a Devanagari sentence for Hindi.
     */
    public Transcript transcribeClip(File audio, String language, String prompt) throws Exception {
        return transcribe(audio, language, null, 0, new String[]{"whisper-large-v3"}, prompt);
    }

    public Transcript transcribe(File audio, String language, java.util.function.LongConsumer uploaded, double temperature,
                                 String[] models, String prompt) throws Exception {
        String key = Prefs.groqKey(ctx);
        Exception last = null;
        for (int m = 0; m < models.length; m++) {
            for (int attempt = 0; attempt < 3; attempt++) {
                ct.check();
                MultipartBody.Builder b = new MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("file", audio.getName(), counting(RequestBody.create(audio, MediaType.parse(mime(audio))), uploaded))
                        .addFormDataPart("model", models[m])
                        .addFormDataPart("temperature", temperature > 0 ? String.format(Locale.US, "%.1f", temperature) : "0")
                        .addFormDataPart("response_format", "verbose_json")
                        .addFormDataPart("timestamp_granularities[]", "segment")
                        .addFormDataPart("timestamp_granularities[]", "word");
                if (language != null && !language.isEmpty()) b.addFormDataPart("language", language);
                if (prompt != null && !prompt.isEmpty()) b.addFormDataPart("prompt", prompt);
                Request req = new Request.Builder().url(KeyCheck.GROQ_BASE + "/openai/v1/audio/transcriptions")
                        .header("Authorization", "Bearer " + key).post(b.build()).build();
                okhttp3.Call call = Net.ai().newCall(req);
                if (ct != null) { ct.calls.add(call); if (ct.cancelled) call.cancel(); }
                try (Response r = call.execute()) {
                    String body = r.body() != null ? r.body().string() : "";
                    Quota.groq(ctx, r);
                    if (r.isSuccessful()) {
                        Transcript t = Transcript.parseVerboseJson(body, 0);
                        AppLog.i("Transcript: " + t.segments.size() + " lines, " + t.words.size() + " timed words (" + models[m] + ", " + t.language + ")");
                        return t;
                    }
                    int code = r.code();
                    String msg = errorMessage(body);
                    String lmsg = msg.toLowerCase(Locale.US);
                    if (lmsg.contains("delinquent") || lmsg.contains("overdue payment")) throw new ApiException("Groq has blocked this key's account (billing). Create a new free key at console.groq.com and update it in Settings.");
                    if (code == 403 && (lmsg.contains("region") || lmsg.contains("country") || lmsg.contains("location") || lmsg.contains("not available")))
                        throw new ApiException("Groq isn't available on this network. Try another network (or a VPN) and retry.");
                    if (code == 401 || code == 403) { KeyCheck.markInvalid(KeyCheck.Service.GROQ, key, code, msg); throw groqRejected(code, msg); }
                    if (code == 413) throw new TooLargeException("Audio part too large for Groq (" + String.format(Locale.US, "%.1f", audio.length() / 1048576.0) + " MB)" + (msg.isEmpty() ? "" : ": " + msg));
                    last = new ApiException("Groq " + code + (msg.isEmpty() ? "" : ": " + msg));
                    if (code == 429 || code >= 500) {
                        long wait = retryAfter(r, 4000L * (attempt + 1));
                        boolean audioHour = code == 429 && msg.toLowerCase(Locale.US).contains("audio");
                        if (audioHour && wait > 10000) throw new GroqExhausted("Groq's free audio limit for this hour is used up (resets in about "
                                + Math.max(1, wait / 60000) + " min)", wait);
                        if (code == 429 && wait > 65000) {
                            String lm = msg.toLowerCase(Locale.US);
                            throw new ApiException("Groq's free limit for your key is used up" + (lm.contains("audio") ? " (audio minutes per hour)" : "")
                                    + ". Try again in about " + Math.max(1, wait / 60000) + " min, or use a shorter video.");
                        }
                        AppLog.i("Groq busy (" + code + "), waiting " + (wait / 1000) + "s…");
                        sleep(Math.min(wait, 30000));
                        continue;
                    }
                    break; // 400 etc: try next model
                } catch (ApiException e) {
                    throw e;
                } catch (CancelToken.Cancelled c) {
                    throw c;
                } catch (Exception e) {
                    last = e instanceof java.net.UnknownHostException ? new ApiException("No internet connection. Check your network and retry.") : e;
                    if (ct.cancelled) throw new CancelToken.Cancelled();
                    // v17: this upload was stopped by the app itself (job ending / another part failed): not a network
                    // error, and not retried. A real timeout (SocketTimeoutException) is retried below without re-cutting.
                    if (Thread.currentThread().isInterrupted() || (e instanceof java.io.InterruptedIOException && !(e instanceof java.net.SocketTimeoutException)))
                        throw new Stopped();
                    AppLog.e("Groq network error: " + describe(e) + (attempt < 2 ? " — sending this part again" : ""));
                    sleep(2500L * (attempt + 1));
                } finally {
                    if (ct != null) ct.calls.remove(call);
                }
            }
        }
        throw new ApiException("Transcription failed: " + (last == null ? "unknown" : last.getMessage()));
    }

    /** v7: never log "null" — class name and cause when an exception has no message */
    public static String describe(Throwable e) {
        if (e == null) return "unknown error";
        String m = e.getMessage();
        if (m != null && !m.isEmpty()) return m;
        Throwable c = e.getCause();
        return e.getClass().getSimpleName() + (c != null && c != e ? " (" + describe(c) + ")" : "");
    }

    /** v7: Groq's hourly audio quota is used up — the caller switches the remaining parts to Gemini */
    public static class GroqExhausted extends ApiException {
        public final long waitMs;
        GroqExhausted(String m, long waitMs) { super(m); this.waitMs = waitMs; }
    }

    /** v7 audio for the Gemini fallback (inline data on the next request) */
    private volatile String inlineB64, inlineMime;

    /**
     * v7: Gemini transcription fallback (free audio input). Returns sentence segments with timestamps; word timings
     * are spread over each segment by length (good enough for captions; Groq gives exact word times).
     */
    public Transcript transcribeGemini(File oggOpus, String language) throws Exception { return transcribeGemini(oggOpus, language, 600); }

    /**
     * v17: one short piece (<= ~2.5 min) per request, compact {"s","e","t"} segments enforced by a schema, a high
     * output limit, no thinking, 120 s per request — and the answer is SALVAGED object by object when it is cut off
     * (2.7: "Unterminated object at character 8603" failed the job). {@code lenSec} = this audio's length.
     */
    public Transcript transcribeGemini(File oggOpus, String language, double lenSec) throws Exception {
        byte[] data = IoUtil.readAll(oggOpus);
        inlineB64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        inlineMime = "audio/ogg";
        schema = transcriptSchema();
        audio = true;
        light = true;
        try {
            String hint = language == null || language.isEmpty() ? "" : " The speech is mostly in language code '" + language + "'.";
            String prompt = "Transcribe this " + Math.round(lenSec) + "-second audio exactly, word for word, in the language spoken (Hindi in Devanagari script, English in English, any other language in its own script)." + hint
                    + " Return ONLY a JSON array of segments {\"s\": start seconds, \"e\": end seconds, \"t\": \"text\"} measured from the start of this audio, "
                    + "one segment per sentence (max ~10 s each), in time order, covering all speech to the end. Skip music and silence. No other keys.";
            String raw = geminiHedged(prompt, true, 0.0, 45000, x -> x.indexOf('{') >= 0);
            Transcript t = Transcript.parseGeminiSegments(raw, language, lenSec);
            if (t.segments.isEmpty()) throw new ApiException("Gemini returned an empty transcript");
            if (t.language.isEmpty()) t.language = CaptionScript.isDevanagariText(t.words) ? "hi" : "";
            AppLog.i(String.format(Locale.US, "Transcript via Gemini: %d lines, up to %.0f s of %.0f s%s", t.segments.size(), t.lastEnd(), lenSec,
                    t.salvaged ? " (answer was cut off: complete lines kept)" : ""));
            return t;
        } finally { inlineB64 = null; inlineMime = null; schema = null; audio = false; light = false; }
    }

    /** v17: true while this client transcribes audio with Gemini (bigger output, longer time limit, no thinking) */
    private volatile boolean audio;
    static final int AUDIO_REQ_TIMEOUT_S = 120;
    static final int AUDIO_MAX_OUTPUT_TOKENS = 32768;

    static JSONObject transcriptSchema() {
        try {
            JSONObject item = new JSONObject().put("type", "OBJECT")
                    .put("properties", new JSONObject().put("s", new JSONObject().put("type", "NUMBER")).put("e", new JSONObject().put("type", "NUMBER"))
                            .put("t", new JSONObject().put("type", "STRING")))
                    .put("required", new JSONArray().put("s").put("e").put("t"))
                    .put("propertyOrdering", new JSONArray().put("s").put("e").put("t"));
            return new JSONObject().put("type", "ARRAY").put("items", item);
        } catch (Exception e) { return null; }
    }

    static long retryAfter(Response r, long dflt) {
        try {
            String h = r.header("retry-after");
            if (h != null) return (long) (Double.parseDouble(h) * 1000) + 500;
        } catch (Exception ignored) {}
        return dflt;
    }

    // ------------------------------------------------------------------ Gemini

    private List<String> modelOrder() {
        List<String> l = new ArrayList<>();
        String cached = Prefs.sp(ctx).getString("gemini_models_v4", null);
        long at = Prefs.sp(ctx).getLong("gemini_models_v4_at", 0);
        if (cached == null || System.currentTimeMillis() - at > 24L * 3600 * 1000) {
            try {
                String listed = listModels();
                if (listed != null) { cached = listed; }
            } catch (ApiException e) {
                if (e.getMessage() != null && e.getMessage().contains("API key")) throw new RuntimeException(e.getMessage(), e);
            } catch (Exception e) {
                AppLog.i("Model list unavailable (" + e.getMessage() + "), using defaults");
            }
        }
        List<String> ranked = new ArrayList<>();
        if (cached != null && !cached.isEmpty()) for (String m : cached.split(",")) if (!m.isEmpty() && !ranked.contains(m)) ranked.add(m);
        if (ranked.isEmpty()) for (String m : GEMINI_PREFERRED) ranked.add(m);
        // models this key can't use right now (removed for new users / daily free quota used up) go last
        java.util.Set<String> skip = new java.util.HashSet<>();
        for (String m : ranked) if (isBenched(m)) skip.add(m);
        // v8: best 2 Flash-Lite models FIRST (on Sakib's phone flash-lite answered in 9.7 s while every Flash model was
        // out of its 20-a-day free quota or busy), then the best 3 Flash models (separate free quotas)
        int flash = 0, lite = 0;
        for (String m : ranked) if (!skip.contains(m) && m.contains("lite") && lite < 2) { l.add(m); lite++; }
        for (String m : ranked) if (!skip.contains(m) && !m.contains("lite") && flash < 3) { l.add(m); flash++; }
        for (String m : ranked) if (!l.contains(m) && !skip.contains(m) && l.size() < 6) l.add(m);
        if (l.isEmpty()) for (String m : ranked) if (l.size() < 2) l.add(m);   // everything benched: try anyway rather than fail blind
        return l;
    }

    /** v8: "Please retry in 20h50m39.35s" -> ms (6 h when Google gives no time) */
    static long dailyResetMs(String msg) {
        if (msg == null) return 6L * 3600 * 1000;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("retry in\\s*(?:(\\d+)h)?\\s*(?:(\\d+)m(?!s))?\\s*(?:([\\d.]+)s)?").matcher(msg);
        if (m.find() && (m.group(1) != null || m.group(2) != null || m.group(3) != null)) {
            double s = (m.group(1) != null ? Long.parseLong(m.group(1)) * 3600 : 0) + (m.group(2) != null ? Long.parseLong(m.group(2)) * 60 : 0)
                    + (m.group(3) != null ? Double.parseDouble(m.group(3)) : 0);
            if (s > 0) return (long) (s * 1000) + 60000;
        }
        return 6L * 3600 * 1000;
    }

    /** the model named in Google's quota text ("model: gemini-3.8-flash"), or null */
    static String quotaModel(String msg) {
        if (msg == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("model:\\s*([a-z0-9.\\-]+)").matcher(msg);
        return m.find() ? m.group(1).replaceAll("[.\\-]+$", "") : null;
    }

    private boolean isBenched(String model) {
        return Prefs.sp(ctx).getLong("gemini_bench_" + model, 0) > System.currentTimeMillis();
    }

    private void bench(String model, long ms) {
        Prefs.sp(ctx).edit().putLong("gemini_bench_" + model, System.currentTimeMillis() + ms).apply();
    }

    /** Lists the key's models once (cached 24 h) and ranks the usable ones: best clip-picking quality first. */
    String listModels() throws Exception {
        Request req = new Request.Builder().url(KeyCheck.GEMINI_BASE + "/v1beta/models?pageSize=200")
                .header("x-goog-api-key", Prefs.geminiKey(ctx)).get().build();
        try (Response r = Net.client().newCall(req).execute()) {
            String body = r.body() != null ? r.body().string() : "";
            if (!r.isSuccessful()) {
                if (r.code() == 400 || r.code() == 401 || r.code() == 403)
                    { String em = errorMessage(body); KeyCheck.markInvalid(KeyCheck.Service.GEMINI, Prefs.geminiKey(ctx), r.code(), em); throw geminiRejected(r.code(), em); }
                throw new ApiException("HTTP " + r.code());
            }
            List<String> ranked = rankModels(body);
            if (ranked.isEmpty()) return null;
            String joined = android.text.TextUtils.join(",", ranked);
            Prefs.sp(ctx).edit().putString("gemini_models_v4", joined).putLong("gemini_models_v4_at", System.currentTimeMillis()).apply();
            AppLog.i("AI model: " + ranked.get(0));
            return joined;
        }
    }

    /** kept for Settings / older callers */
    public String discoverModel() throws Exception {
        String l = listModels();
        return l == null ? null : l.split(",")[0];
    }

    static String pickBest(String json) {
        List<String> r = rankModels(json);
        return r.isEmpty() ? null : r.get(0);
    }

    /**
     * Stable text models that support generateContent, ranked: Flash before Flash-Lite (better at judging hooks and
     * topic boundaries, still fast), newer version first, "-latest" aliases right after their family. Previews,
     * experimental, image/audio/tts/live/embedding models are excluded.
     */
    static List<String> rankModels(String json) {
        List<String> av = new ArrayList<>();
        try {
            JSONArray models = new JSONObject(json).optJSONArray("models");
            if (models != null) for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.getJSONObject(i);
                String name = m.optString("name", "").replace("models/", "");
                JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                boolean gen = false;
                if (methods != null) for (int j = 0; j < methods.length(); j++) if ("generateContent".equals(methods.optString(j))) gen = true;
                String n = name.toLowerCase(Locale.US);
                if (!gen || !n.startsWith("gemini") || !n.contains("flash")) continue;
                if (n.contains("preview") || n.contains("exp") || n.contains("embedding") || n.contains("image") || n.contains("tts")
                        || n.contains("live") || n.contains("audio") || n.contains("thinking") || n.contains("8b") || n.matches(".*-\\d{3}$")) continue;
                av.add(name);
            }
        } catch (Exception ignored) {}
        Collections.sort(av, (x, y) -> Double.compare(modelScore(y), modelScore(x)));
        return av;
    }

    public static List<String> rankModelsForTest(String json) { return rankModels(json); }
    public static List<Candidate> parseForTest(String raw, double duration) throws Exception { return parseCandidates(raw, duration); }
    public static List<Candidate> parseForTest(String raw, double duration, Transcript tr) throws Exception { return parseCandidates(raw, duration, tr); }

    static double modelScore(String name) {
        String n = name.toLowerCase(Locale.US);
        double v = 0;
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("gemini-(\\d+(?:\\.\\d+)?)").matcher(n);
        if (mt.find()) { try { v = Double.parseDouble(mt.group(1)); } catch (Exception ignored) {} }
        else if (n.contains("latest")) v = 2.55;   // alias of the newest stable family
        boolean lite = n.contains("lite");
        return v * 10 + (lite ? 0 : 4);
    }

    /** Raw text answer from Gemini: the best model this key lists, with backoff on 429/5xx and a quiet fallback. */
    public String gemini(String prompt, boolean json, double temperature) throws Exception {
        return geminiWith(order(), prompt, json, temperature, null, null);
    }

    /** v6: text conversions (captions language) need no reasoning: minimal thinking */
    private volatile boolean light;
    /** v7: Gemini structured-output schema for the clip picker (null = plain JSON mode) */
    private volatile JSONObject schema;

    private List<String> order() throws ApiException {
        try { return modelOrder(); } catch (RuntimeException e) {
            if (e.getCause() instanceof ApiException) throw (ApiException) e.getCause();   // keeps KeyRejected (the fallback reads it)
            throw new ApiException(e.getMessage());
        }
    }

    /** Lists the key's models now (if the 24 h cache is stale) and opens the connection, so the AI step starts at once. */
    public void warmUp() {
        try { modelOrder(); } catch (Throwable ignored) {}
    }

    private static final class Stopped extends ApiException { Stopped() { super("stopped"); } }

    /** the generateContent request body (shared by the serial walk and the race; same fields as before) */
    private JSONObject buildBody(String model, String prompt, boolean json, double temperature, boolean thinking, JSONObject sch) throws Exception {
        return buildBody(model, prompt, json, temperature, thinking, sch, light);
    }

    private JSONObject buildBody(String model, String prompt, boolean json, double temperature, boolean thinking, JSONObject sch, boolean light) throws Exception {
        JSONObject body = new JSONObject();
        JSONArray parts = new JSONArray().put(new JSONObject().put("text", prompt));
        String ib = inlineB64, im = inlineMime;
        if (ib != null) parts.put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", im).put("data", ib)));
        body.put("contents", new JSONArray().put(new JSONObject().put("parts", parts)));
        JSONArray safety = new JSONArray();
        for (String cat : new String[]{"HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"})
            safety.put(new JSONObject().put("category", cat).put("threshold", "BLOCK_NONE"));
        body.put("safetySettings", safety);
        JSONObject gc = new JSONObject().put("temperature", temperature);
        if (json) gc.put("responseMimeType", "application/json");
        if (sch != null) gc.put("responseSchema", sch);
        // a small thinking budget measurably improves where clips start/end; kept small for speed
        if (thinking) gc.put("thinkingConfig", model.startsWith("gemini-3")
                ? new JSONObject().put("thinkingLevel", light ? "minimal" : "low") : new JSONObject().put("thinkingBudget", light ? 0 : 1024));
        if (audio) gc.put("maxOutputTokens", AUDIO_MAX_OUTPUT_TOKENS);
        body.put("generationConfig", gc);
        return body;
    }

    /**
     * Walks the given model order. v6: a per-minute 429 moves straight on to the next model (separate free quota)
     * instead of sleeping on the same one; only the last model waits and retries.
     */
    private String geminiWith(List<String> order, String prompt, boolean json, double temperature,
                              java.util.concurrent.atomic.AtomicBoolean stop, java.util.Set<okhttp3.Call> calls) throws Exception {
        Exception last = null;
        for (int i = 0; i < order.size(); i++) {
            String model = order.get(i);
            boolean thinking = (model.contains("2.5-flash") && !model.contains("lite")) || model.startsWith("gemini-3");
            JSONObject sch = json ? schema : null;
            for (int attempt = 0; attempt < 3; attempt++) {
                ct.check();
                if (stop != null && stop.get()) throw new Stopped();
                JSONObject body = buildBody(model, prompt, json, temperature, thinking, sch);
                Request req = new Request.Builder()
                        .url(KeyCheck.GEMINI_BASE + "/v1beta/models/" + model + ":generateContent")
                        .header("x-goog-api-key", Prefs.geminiKey(ctx))
                        .post(RequestBody.create(body.toString(), MediaType.parse("application/json"))).build();
                okhttp3.Call call = aiHttp().newCall(req);
                call.timeout().timeout(audio ? AUDIO_REQ_TIMEOUT_S : REQ_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS);
                long tReq = System.currentTimeMillis();
                if (calls != null) calls.add(call);
                try (Response r = call.execute()) {
                    String resp = r.body() != null ? r.body().string() : "";
                    // v7: every attempt's model, HTTP status and time go to the logs (and so into bug reports)
                    Quota.gemini(ctx, model, r.code());
                    AppLog.i(String.format(Locale.US, "AI %s → HTTP %d in %.1f s (%d KB prompt)", model, r.code(), (System.currentTimeMillis() - tReq) / 1000.0, prompt.length() / 1024));
                    if (r.isSuccessful()) {
                        JSONObject o = new JSONObject(resp);
                        JSONArray cands = o.optJSONArray("candidates");
                        if (cands == null || cands.length() == 0) throw new ApiException("Gemini returned no answer (blocked or empty)");
                        JSONObject content = cands.getJSONObject(0).optJSONObject("content");
                        JSONArray ps = content == null ? null : content.optJSONArray("parts");
                        StringBuilder sb = new StringBuilder();
                        if (ps != null) for (int k = 0; k < ps.length(); k++) {
                            JSONObject pk = ps.getJSONObject(k);
                            if (!pk.optBoolean("thought", false)) sb.append(pk.optString("text", ""));
                        }
                        if (sb.length() == 0) throw new ApiException("Gemini returned an empty answer");
                        if (i > 0) AppLog.i("AI answered with " + model);
                        return sb.toString();
                    }
                    int code = r.code();
                    String msg = errorMessage(resp);
                    last = new ApiException("Gemini " + code + (msg.isEmpty() ? "" : ": " + msg));
                    if (msg.toLowerCase(Locale.US).contains("location is not supported") || msg.toLowerCase(Locale.US).contains("not available in your country"))
                        throw new ApiException("Gemini isn't available in your region. Use a key from a supported region (or a VPN) and retry.");
                    if ((code == 400 && msg.toLowerCase(Locale.US).contains("api key")) || code == 401 || code == 403)
                        { KeyCheck.markInvalid(KeyCheck.Service.GEMINI, Prefs.geminiKey(ctx), code, msg); throw geminiRejected(code, msg); }
                    if (code == 400 && thinking && msg.toLowerCase(Locale.US).contains("think")) { thinking = false; continue; }
                    if (code == 400 && sch != null && msg.toLowerCase(Locale.US).contains("schema")) { sch = null; continue; }
                    if (code == 429 && (msg.contains("per day") || msg.contains("PerDay") || msg.toLowerCase(Locale.US).contains("daily"))) {
                        // v8: skip this model (no call at all) until the reset time Google states ("Please retry in 20h50m39s")
                        long until = dailyResetMs(msg);
                        bench(model, until);
                        String named = quotaModel(msg);
                        if (named != null && !named.equals(model)) bench(named, until);
                        AppLog.i("AI " + model + ": free daily limit used up, skipping it for " + (until / 3600000) + " h " + (until / 60000 % 60) + " min");
                        break;
                    }
                    if (code == 429 && i < order.size() - 1) break;   // rate-limited for this minute: next model now, no waiting
                    if (code >= 500 && i < order.size() - 1) { bench(model, 120000L); break; }   // v7: 503 "high demand": next model now
                    if (code == 429 || code >= 500) {
                        if (attempt < 2) { sleep(Math.min(20000, retryAfter(r, 2500L * (attempt + 1)))); continue; }
                    }
                    if (code == 404) bench(model, 7L * 24 * 3600 * 1000);
                    break; // 404 / other: next model
                } catch (ApiException e) {
                    if (e.getMessage() != null && e.getMessage().contains("API key")) throw e;
                    last = e;
                    break;
                } catch (CancelToken.Cancelled c) {
                    throw c;
                } catch (Exception e) {
                    if (stop != null && stop.get()) throw new Stopped();
                    last = e;
                    sleep(1500L * (attempt + 1));
                } finally {
                    if (calls != null) calls.remove(call);
                }
            }
            if (stop != null && stop.get()) throw new Stopped();
            AppLog.i("AI model " + model + " unavailable (" + (last == null ? "?" : last.getMessage()) + ")");
        }
        String m = last == null ? "no model available" : last.getMessage();
        if (m != null && m.contains("429")) throw new ApiException("Gemini's free limit is used up for now. Wait a minute and tap Retry.");
        throw new ApiException("AI scoring failed: " + m);
    }

    /** test hook: an interceptor that answers Gemini requests instead of the network (null in the app) */
    static volatile okhttp3.Interceptor testInterceptor;

    private static okhttp3.OkHttpClient aiHttp() {
        okhttp3.Interceptor t = testInterceptor;
        return t == null ? Net.ai() : Net.ai().newBuilder().addInterceptor(t).build();
    }

    /**
     * 5.1: every text request is a RACE from the first moment. Two models are asked at the same time (the first two of
     * the key's ranked order: the Flash-Lite pair, separate free quotas); the first VALID answer wins and the other calls
     * are cancelled. A model that fails (503 high demand, 429, 5xx, timeout, invalid answer) hands its lane to the next
     * model at once - no waiting. If nobody has answered after a few seconds (scaled by prompt size) one more model
     * joins. When every model failed, the ones that failed for a passing reason (busy, timeout, per-minute limit) get one
     * more try. {@code hedgeMs} is kept for callers; the audio path (big inline audio) keeps the old hedged walk.
     * (5.0 asked ONE model and waited 15 s before asking a second: on Sakib's phone the first model stayed silent for
     * 15 s, the second then answered 503, and the step took 20+ s; the ending check waited 15 s for a 1.6 s answer.)
     */
    public String geminiHedged(String prompt, boolean json, double temperature, long hedgeMs,
                               java.util.function.Predicate<String> valid) throws Exception {
        if (audio) return geminiHedgedSerial(prompt, json, temperature, hedgeMs, valid);
        return geminiRace(prompt, json, temperature, valid, raceTimeoutS(prompt.length()), 0, RACE_WIDTH);
    }

    /** lanes started at once */
    static int RACE_WIDTH = 2;
    /** one more model joins when nobody answered after this long (+ RACE_EXTRA_PER_KB_MS per KB of prompt) */
    static long RACE_EXTRA_MS = 6000;
    static long RACE_EXTRA_PER_KB_MS = 80;
    /** short pause before the second try of models that failed for a passing reason */
    static long RACE_RETRY_PAUSE_MS = 1500;

    /** per-call limit in the race: 20 s + 1 s per 4 KB of prompt, at most REQ_TIMEOUT_S (a 22 KB prompt: 25 s) */
    static int raceTimeoutS(int promptChars) {
        return (int) Math.min(REQ_TIMEOUT_S, 20 + promptChars / 4096);
    }

    static long raceExtraMs(int promptChars) {
        return RACE_EXTRA_MS + (promptChars / 1024) * RACE_EXTRA_PER_KB_MS;
    }

    /** one lane's failure: the model, why, and whether a later try could work (busy / timeout / per-minute limit) */
    static final class LaneFail extends Exception {
        final String model; final boolean passing; final Exception why;
        LaneFail(String model, boolean passing, Exception why) { super(why.getMessage()); this.model = model; this.passing = passing; this.why = why; }
    }

    /** a failure no other model can fix (API key / region): the race stops and the message reaches the user */
    private static boolean fatal(Throwable c) {
        if (!(c instanceof ApiException) || c.getMessage() == null) return false;
        String m = c.getMessage();
        return m.contains("API key") || m.contains("region") || m.contains("country");
    }

    /**
     * The race. {@code deadlineMs} > 0 = a hard cap for the whole race (the ending check: an optional refinement), after
     * which it gives up with an ApiException.
     */
    String geminiRace(String prompt, boolean json, double temperature, java.util.function.Predicate<String> valid,
                      int timeoutS, long deadlineMs) throws Exception {
        return geminiRace(prompt, json, temperature, valid, timeoutS, deadlineMs, RACE_WIDTH);
    }

    /** {@code width} = models asked at the very start (the caption conversions use 1: several run at once per job) */
    String geminiRace(String prompt, boolean json, double temperature, java.util.function.Predicate<String> valid,
                      int timeoutS, long deadlineMs, int width) throws Exception {
        final List<String> models = order();
        final java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(models);
        final List<String> passing = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.Set<okhttp3.Call> calls = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final JSONObject sch = json ? schema : null;
        final boolean lightNow = light;
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "ai-race"); t.setDaemon(true); return t; });
        java.util.concurrent.CompletionService<String> cs = new java.util.concurrent.ExecutorCompletionService<>(ex);
        long t0 = System.currentTimeMillis();
        long end = deadlineMs > 0 ? t0 + deadlineMs : Long.MAX_VALUE;
        long extraAt = t0 + (deadlineMs > 0 ? Math.min(raceExtraMs(prompt.length()), (long) (deadlineMs * 0.4)) : raceExtraMs(prompt.length()));
        boolean extraUsed = false, retried = false;
        int running = 0;
        Exception last = null;
        List<String> started = new ArrayList<>();
        try {
            for (int i = 0; i < width && !queue.isEmpty(); i++) {
                String m = queue.poll();
                started.add(m);
                cs.submit(() -> lane(m, prompt, json, temperature, valid, sch, lightNow, timeoutS, stop, calls));
                running++;
            }
            AppLog.i(started.size() > 1 ? "AI: asking " + android.text.TextUtils.join(" and ", started) + " at the same time (first good answer wins)"
                    : "AI: asking " + (started.isEmpty() ? "?" : started.get(0)));
            while (true) {
                ct.check();
                long now = System.currentTimeMillis();
                if (now >= end) throw new ApiException(String.format(Locale.US, "no answer within %.0f s", deadlineMs / 1000.0));
                if (running == 0) {
                    if (queue.isEmpty() && !retried && !passing.isEmpty()) {
                        retried = true;
                        AppLog.i("AI: every model was busy, trying " + android.text.TextUtils.join(", ", passing) + " once more");
                        sleep(Math.min(RACE_RETRY_PAUSE_MS, Math.max(0, end - System.currentTimeMillis())));
                        queue.addAll(passing);
                        passing.clear();
                        for (int i = 0; i < width && !queue.isEmpty(); i++) {
                            String m = queue.poll();
                            cs.submit(() -> lane(m, prompt, json, temperature, valid, sch, lightNow, timeoutS, stop, calls));
                            running++;
                        }
                    }
                    if (running == 0) break;
                }
                long wait = Math.max(1, Math.min(200, Math.min(end, extraUsed ? Long.MAX_VALUE : extraAt) - System.currentTimeMillis()));
                java.util.concurrent.Future<String> f = cs.poll(wait, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null) {
                    if (!extraUsed && System.currentTimeMillis() >= extraAt) {
                        extraUsed = true;
                        String m = queue.poll();
                        if (m != null) {
                            AppLog.i(String.format(Locale.US, "AI: no answer after %.0f s, %s joins the race", (System.currentTimeMillis() - t0) / 1000.0, m));
                            cs.submit(() -> lane(m, prompt, json, temperature, valid, sch, lightNow, timeoutS, stop, calls));
                            running++;
                        }
                    }
                    continue;
                }
                running--;
                try {
                    String s = f.get();
                    return s;
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable c = ee.getCause();
                    if (c instanceof CancelToken.Cancelled) throw (CancelToken.Cancelled) c;
                    if (fatal(c)) throw (ApiException) c;
                    if (c instanceof LaneFail) {
                        LaneFail lf = (LaneFail) c;
                        last = lf.why;
                        if (lf.passing && !retried) passing.add(lf.model);
                    } else if (!(c instanceof Stopped)) last = c instanceof Exception ? (Exception) c : new Exception(c);
                    String m = queue.poll();
                    if (m != null && System.currentTimeMillis() < end) {
                        cs.submit(() -> lane(m, prompt, json, temperature, valid, sch, lightNow, timeoutS, stop, calls));
                        running++;
                    }
                }
            }
        } finally {
            stop.set(true);
            for (okhttp3.Call c : calls) { try { c.cancel(); } catch (Exception ignored) {} }
            ex.shutdownNow();
        }
        String m = last == null ? "no model available" : last.getMessage();
        if (m != null && m.contains("429")) throw new ApiException("Gemini's free limit is used up for now. Wait a minute and tap Retry.");
        if (last instanceof ApiException && m != null && m.startsWith("The AI answer")) throw last;
        throw new ApiException("AI scoring failed: " + m);
    }

    /**
     * One model, one answer (the race's lane): same request, same error handling as the serial walk (daily-limit and
     * 404 benching, thinking / schema fallbacks on 400, key / region errors), but never sleeps: a busy model fails at
     * once so its lane moves on. Returns only a VALID answer.
     */
    private String lane(String model, String prompt, boolean json, double temperature, java.util.function.Predicate<String> valid,
                        JSONObject schIn, boolean lightNow, int timeoutS,
                        java.util.concurrent.atomic.AtomicBoolean stop, java.util.Set<okhttp3.Call> calls) throws Exception {
        boolean thinking = (model.contains("2.5-flash") && !model.contains("lite")) || model.startsWith("gemini-3");
        JSONObject sch = schIn;
        for (int attempt = 0; attempt < 3; attempt++) {
            ct.check();
            if (stop.get()) throw new Stopped();
            JSONObject body = buildBody(model, prompt, json, temperature, thinking, sch, lightNow);
            Request req = new Request.Builder()
                    .url(KeyCheck.GEMINI_BASE + "/v1beta/models/" + model + ":generateContent")
                    .header("x-goog-api-key", Prefs.geminiKey(ctx))
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json"))).build();
            okhttp3.Call call = aiHttp().newCall(req);
            call.timeout().timeout(timeoutS, java.util.concurrent.TimeUnit.SECONDS);
            long tReq = System.currentTimeMillis();
            calls.add(call);
            if (stop.get()) { call.cancel(); throw new Stopped(); }
            try (Response r = call.execute()) {
                String resp = r.body() != null ? r.body().string() : "";
                if (stop.get()) throw new Stopped();
                Quota.gemini(ctx, model, r.code());
                AppLog.i(String.format(Locale.US, "AI %s → HTTP %d in %.1f s (%d KB prompt)", model, r.code(), (System.currentTimeMillis() - tReq) / 1000.0, prompt.length() / 1024));
                if (r.isSuccessful()) {
                    String text = answerText(resp);
                    if (valid != null && !valid.test(text)) {
                        AppLog.i("AI " + model + ": answer incomplete, the race goes on");
                        throw new LaneFail(model, false, new ApiException("The AI answer was incomplete. Tap Retry."));
                    }
                    return text;
                }
                int code = r.code();
                String msg = errorMessage(resp);
                ApiException err = new ApiException("Gemini " + code + (msg.isEmpty() ? "" : ": " + msg));
                String lm = msg.toLowerCase(Locale.US);
                if (lm.contains("location is not supported") || lm.contains("not available in your country"))
                    throw new ApiException("Gemini isn't available in your region. Use a key from a supported region (or a VPN) and retry.");
                if ((code == 400 && lm.contains("api key")) || code == 401 || code == 403)
                    { KeyCheck.markInvalid(KeyCheck.Service.GEMINI, Prefs.geminiKey(ctx), code, msg); throw geminiRejected(code, msg); }
                if (code == 400 && thinking && lm.contains("think")) { thinking = false; continue; }
                if (code == 400 && sch != null && lm.contains("schema")) { sch = null; continue; }
                if (code == 429 && (msg.contains("per day") || msg.contains("PerDay") || lm.contains("daily"))) {
                    long until = dailyResetMs(msg);
                    bench(model, until);
                    String named = quotaModel(msg);
                    if (named != null && !named.equals(model)) bench(named, until);
                    AppLog.i("AI " + model + ": free daily limit used up, skipping it for " + (until / 3600000) + " h " + (until / 60000 % 60) + " min");
                    throw new LaneFail(model, false, err);
                }
                if (code >= 500) {
                    bench(model, 120000L);
                    AppLog.i("AI model " + model + " unavailable (" + err.getMessage() + "), next model now");
                    throw new LaneFail(model, true, err);
                }
                if (code == 429) { AppLog.i("AI " + model + ": busy for this minute, next model now"); throw new LaneFail(model, true, err); }
                if (code == 404) bench(model, 7L * 24 * 3600 * 1000);
                AppLog.i("AI model " + model + " unavailable (" + err.getMessage() + ")");
                throw new LaneFail(model, false, err);
            } catch (LaneFail | Stopped | CancelToken.Cancelled e) {
                throw e;
            } catch (ApiException e) {
                if (fatal(e)) throw e;
                throw new LaneFail(model, false, e);
            } catch (Exception e) {
                if (stop.get()) throw new Stopped();
                boolean timeout = e instanceof java.io.InterruptedIOException;
                AppLog.i(String.format(Locale.US, "AI %s: %s after %.1f s, next model now", model,
                        timeout ? "no answer" : "connection failed (" + e.getMessage() + ")", (System.currentTimeMillis() - tReq) / 1000.0));
                throw new LaneFail(model, true, new ApiException(timeout ? "no answer from " + model + " within " + timeoutS + " s" : String.valueOf(e.getMessage())));
            } finally {
                calls.remove(call);
            }
        }
        throw new LaneFail(model, false, new ApiException("Gemini 400"));
    }

    /** the answer's text parts (thoughts skipped) */
    static String answerText(String resp) throws Exception {
        JSONObject o = new JSONObject(resp);
        JSONArray cands = o.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) throw new ApiException("Gemini returned no answer (blocked or empty)");
        JSONObject content = cands.getJSONObject(0).optJSONObject("content");
        JSONArray ps = content == null ? null : content.optJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        if (ps != null) for (int k = 0; k < ps.length(); k++) {
            JSONObject pk = ps.getJSONObject(k);
            if (!pk.optBoolean("thought", false)) sb.append(pk.optString("text", ""));
        }
        if (sb.length() == 0) throw new ApiException("Gemini returned an empty answer");
        return sb.toString();
    }

    /** 5.0's hedged walk, kept for the audio transcription path (big inline audio is not sent twice at once) */
    String geminiHedgedSerial(String prompt, boolean json, double temperature, long hedgeMs,
                               java.util.function.Predicate<String> valid) throws Exception {
        final List<String> a = order();
        final List<String> b = new ArrayList<>();
        if (a.size() > 1) { b.addAll(a.subList(1, a.size())); b.add(a.get(0)); }
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.Set<okhttp3.Call> calls = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "ai-hedge"); t.setDaemon(true); return t; });
        java.util.concurrent.CompletionService<String> cs = new java.util.concurrent.ExecutorCompletionService<>(ex);
        java.util.concurrent.Callable<String> first = () -> check(geminiWith(a, prompt, json, temperature, stop, calls), valid);
        java.util.concurrent.Callable<String> second = () -> check(geminiWith(b, prompt, json, temperature, stop, calls), valid);
        Exception last = null;
        try {
            cs.submit(first);
            int running = 1;
            boolean hedged = b.isEmpty();
            long hedgeAt = System.currentTimeMillis() + hedgeMs;
            while (running > 0) {
                ct.check();
                long wait = hedged ? 400 : Math.max(1, Math.min(400, hedgeAt - System.currentTimeMillis()));
                java.util.concurrent.Future<String> f = cs.poll(wait, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null) {
                    if (!hedged && System.currentTimeMillis() >= hedgeAt) {
                        hedged = true; running++;
                        AppLog.i("AI is taking long, asking " + b.get(0) + " in parallel");
                        cs.submit(second);
                    }
                    continue;
                }
                running--;
                try {
                    return f.get();
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable c = ee.getCause();
                    if (c instanceof CancelToken.Cancelled) throw (CancelToken.Cancelled) c;
                    if (c instanceof ApiException && c.getMessage() != null && (c.getMessage().contains("API key") || c.getMessage().contains("country"))) throw (ApiException) c;
                    if (!(c instanceof Stopped)) last = c instanceof Exception ? (Exception) c : new Exception(c);
                    if (!hedged) { hedged = true; running++; cs.submit(second); }
                }
            }
        } finally {
            stop.set(true);
            for (okhttp3.Call c : calls) { try { c.cancel(); } catch (Exception ignored) {} }
            ex.shutdownNow();
        }
        if (last instanceof ApiException) throw last;
        throw new ApiException("AI scoring failed: " + (last == null ? "no answer" : last.getMessage()));
    }

    private static String check(String raw, java.util.function.Predicate<String> valid) throws ApiException {
        if (valid != null && !valid.test(raw)) throw new ApiException("The AI answer was incomplete. Tap Retry.");
        return raw;
    }

    /** after this long without an answer a second model is asked in parallel */
    static long HEDGE_MS = 15000;
    /** v7: hard per-request limit (v6: 100 s) — a stuck call never holds the AI step for minutes */
    static int REQ_TIMEOUT_S = 40;
    /** v7: transcripts longer than this (compact chars) are split and asked in parallel */
    static int CHUNK_CHARS = 90000;

    /** Viral moment picking (prompt and scoring rules from v1). */
    public List<Candidate> findViralClips(PromptManager.Category cat, String title, Transcript t, int count) throws Exception {
        return findViralClips(cat, title, t, count, 0);
    }

    public List<Candidate> findViralClips(PromptManager.Category cat, String title, Transcript t, int count, double duration) throws Exception {
        schema = clipSchema();
        try { return findViralClipsInner(cat, title, t, count, duration); } finally { schema = null; }
    }

    /**
     * code 29: a second, focused look when the first answer's moments overlapped (fewer clean shorts than asked): only the
     * transcript lines outside the shorts already chosen are shown, and the answer's line numbers are mapped back to the
     * whole transcript, so the clip picker works on it exactly as on the first answer.
     */
    public List<Candidate> findMoreClips(PromptManager.Category cat, String title, Transcript t, List<double[]> taken, int count, double duration) throws Exception {
        Transcript sub = new Transcript();
        sub.language = t.language;
        List<Integer> map = outsideLines(t, taken, sub);
        if (sub.segments.size() < 3) return new ArrayList<>();
        schema = clipSchema();
        List<Candidate> out;
        try { out = findViralClipsInner(cat, title, sub, count, duration); } finally { schema = null; }
        for (Candidate c : out) remapLines(c, map, t.segments.size());
        return out;
    }

    /** the transcript lines (and their words) that lie outside every taken range; returns sub line -> whole line */
    static List<Integer> outsideLines(Transcript t, List<double[]> taken, Transcript sub) {
        List<Integer> map = new ArrayList<>();
        for (int i = 0; i < t.segments.size(); i++) {
            Transcript.Segment g = t.segments.get(i);
            boolean in = false;
            for (double[] r : taken) if (Math.min(g.end, r[1]) - Math.max(g.start, r[0]) > 0.5 * Math.max(0.1, g.end - g.start)) { in = true; break; }
            if (in) continue;
            sub.segments.add(g);
            map.add(i);
        }
        for (com.viral.shortsmaker.captions.Word w : t.words) {
            boolean in = false;
            for (double[] r : taken) if (w.start >= r[0] && w.end <= r[1]) { in = true; break; }
            if (!in) sub.words.add(w);
        }
        return map;
    }

    static void remapLines(Candidate c, List<Integer> map, int total) {
        c.startLine = c.startLine >= 0 && c.startLine < map.size() ? map.get(c.startLine) : -1;
        c.endLine = c.endLine >= 0 && c.endLine < map.size() ? map.get(c.endLine) : -1;
        c.questionLine = c.questionLine >= 0 && c.questionLine < map.size() ? map.get(c.questionLine) : -1;
        if (c.startLine >= total) c.startLine = -1;
        if (c.endLine >= total) c.endLine = -1;
    }

    private List<Candidate> findViralClipsInner(PromptManager.Category cat, String title, Transcript t, int count, double duration) throws Exception {
        String all = t.forPromptCompact(240000);
        List<String> parts = splitLines(all, CHUNK_CHARS);
        if (parts.size() <= 1) return findInPart(cat, title, all, t, count, duration);
        // v7: long video → 2-3 parts asked at the same time, answers merged and ranked
        AppLog.i("Long transcript: asking the AI about " + parts.size() + " parts in parallel");
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(parts.size());
        List<java.util.concurrent.Future<List<Candidate>>> fs = new ArrayList<>();
        int per = Math.max(2, (int) Math.ceil((double) count / parts.size()) + 1);
        for (String p : parts) fs.add(ex.submit(() -> findInPart(cat, title, p, t, per, duration)));
        List<Candidate> out = new ArrayList<>();
        Exception last = null;
        try {
            for (java.util.concurrent.Future<List<Candidate>> f : fs) {
                try { out.addAll(f.get()); }
                catch (java.util.concurrent.ExecutionException ee) {
                    Throwable c = ee.getCause();
                    if (c instanceof CancelToken.Cancelled) throw (CancelToken.Cancelled) c;
                    last = c instanceof Exception ? (Exception) c : new Exception(c);
                    AppLog.e("AI part failed: " + c.getMessage());
                }
            }
        } finally { ex.shutdownNow(); }
        if (out.isEmpty()) throw last != null ? last : new ApiException("No strong moments found in this video");
        Collections.sort(out, (x, y) -> Integer.compare(y.score, x.score));
        return out;
    }

    /** splits numbered transcript lines into contiguous blocks of at most ~maxChars (max 3 blocks) */
    static List<String> splitLines(String all, int maxChars) {
        List<String> out = new ArrayList<>();
        int k = (int) Math.min(3, Math.ceil(all.length() / (double) Math.max(1, maxChars)));
        if (k <= 1) { out.add(all); return out; }
        int target = all.length() / k, from = 0;
        for (int i = 0; i < k - 1; i++) {
            int cut = all.indexOf('\n', from + target);
            if (cut < 0) break;
            out.add(all.substring(from, cut + 1));
            from = cut + 1;
        }
        if (from < all.length()) out.add(all.substring(from));
        return out;
    }

    private List<Candidate> findInPart(PromptManager.Category cat, String title, String lines, Transcript t, int count, double duration) throws Exception {
        String prompt = PromptManager.buildViralPrompt(cat, title, lines, count, duration);
        // v6: compact transcript, hedged second model after 25 s. v7: hook line + 4 sub-scores, enforced by a JSON schema
        java.util.function.Predicate<String> ok = s -> {
            try { return !parseCandidates(s, duration, t).isEmpty(); } catch (Exception e) { return false; }
        };
        // 5.3: a rejected Gemini key no longer ends the job here: Groq's text model picks the clips (same prompt + JSON)
        String raw = withKeyFallback(prompt, 0.3, ok, raceTimeoutS(prompt.length()) + 20, 0,
                () -> geminiHedged(prompt, true, 0.3, HEDGE_MS, ok));
        List<Candidate> out = parseCandidates(raw, duration, t);
        if (out.isEmpty()) throw new ApiException("No strong moments found in this video");
        return out;
    }

    // ------------------------------------------------------------------ 5.3 key fallback (Gemini <-> Groq)

    /** where the fallback note goes (the job's warnings, shown with the result); null = the log only */
    private volatile List<String> notices;

    /** the job's warnings list: a fallback adds its one line there (once per job) */
    public AiClient notices(List<String> sink) { this.notices = sink; return this; }

    private void note(String line) {
        List<String> n = notices;
        if (n == null) { AppLog.i(line); return; }
        boolean first;
        synchronized (n) { first = !n.contains(line); if (first) n.add(line); }
        if (first) AppLog.i(line);
    }

    /** Groq's text models for the clip pick when the Gemini key is rejected: strongest first, one hands over to the next */
    static final String[] GROQ_TEXT_MODELS = {"openai/gpt-oss-120b", "llama-3.3-70b-versatile"};

    /** a key that can still be used: right format and not rejected recently */
    private boolean usable(KeyCheck.Service s) {
        String k = s == KeyCheck.Service.GEMINI ? Prefs.geminiKey(ctx) : Prefs.groqKey(ctx);
        boolean format = s == KeyCheck.Service.GEMINI ? Prefs.isValidGeminiKey(k) : Prefs.isValidGroqKey(k);
        return format && !KeyCheck.knownInvalid(s, k);
    }

    /**
     * 5.3: runs a text step on Gemini (the race, unchanged) and, when Gemini REJECTS THE KEY (400 API_KEY_INVALID / 401 /
     * 403), does the same step with Groq's text model: same prompt, same JSON contract and the same validity check. The
     * job's result then says so in one line. A key already known as rejected skips Gemini at once. Fails only when no
     * working key is left, naming the bad key(s). Any other Gemini failure is unchanged (no fallback).
     */
    String withKeyFallback(String prompt, double temperature, java.util.function.Predicate<String> valid, int groqTimeoutS, long deadlineMs,
                           java.util.concurrent.Callable<String> gemini) throws Exception {
        KeyRejected gemBad = null;
        if (!usable(KeyCheck.Service.GEMINI) && Prefs.isValidGeminiKey(Prefs.geminiKey(ctx)) && usable(KeyCheck.Service.GROQ)) {
            KeyCheck.Verdict v = KeyCheck.cached(KeyCheck.Service.GEMINI, Prefs.geminiKey(ctx));
            gemBad = geminiRejected(v == null ? 400 : v.code, v == null ? "" : v.message);
        } else {
            try { return gemini.call(); }
            catch (KeyRejected kr) {
                if (kr.service != KeyCheck.Service.GEMINI) throw kr;
                gemBad = kr;
            }
        }
        if (!usable(KeyCheck.Service.GROQ)) {
            String gk = Prefs.groqKey(ctx);
            if (Prefs.isValidGroqKey(gk) && KeyCheck.knownInvalid(KeyCheck.Service.GROQ, gk)) throw bothRejected(gemBad);
            throw gemBad;   // no Groq key to fall back on: the Gemini key message, as before
        }
        AppLog.i("AI: Gemini rejected your key, asking Groq instead");
        try {
            String out = groqText(prompt, temperature, valid, groqTimeoutS, deadlineMs);
            note(GEMINI_FALLBACK_NOTE);
            return out;
        } catch (KeyRejected kr2) {
            throw bothRejected(gemBad);
        } catch (CancelToken.Cancelled c) {
            throw c;
        } catch (Exception other) {
            String m = KeyCheck.sentence(gemBad.providerMessage);
            throw new KeyRejected(KeyCheck.Service.GEMINI, gemBad.code, gemBad.providerMessage, "Your Gemini API key isn't valid"
                    + (m.isEmpty() ? "" : " (Google: " + m.substring(0, m.length() - 1) + ")") + " and Groq couldn't do this step either ("
                    + KeyCheck.sentence(AiClient.describe(other)).replaceAll("\\.$", "") + "). Update your Gemini key in Settings.");
        }
    }

    static KeyRejected bothRejected(KeyRejected gem) {
        return new KeyRejected(KeyCheck.Service.GEMINI, gem == null ? 400 : gem.code, gem == null ? "" : gem.providerMessage,
                "Both API keys were rejected: your Gemini key and your Groq key are not valid. Update both in Settings.");
    }

    /**
     * One text answer from Groq's chat API (OpenAI-compatible), for the steps Gemini normally does. The prompt is the same;
     * Gemini's JSON schema is not available here, so the schema is added to the prompt as text and the answer must pass
     * the same validity check. A model that is busy / missing / slow / invalid hands over to the next one at once.
     */
    String groqText(String prompt, double temperature, java.util.function.Predicate<String> valid, int timeoutS, long deadlineMs) throws Exception {
        String key = Prefs.groqKey(ctx);
        JSONObject sch = schema;
        String full = sch == null ? prompt : prompt + "\n\nReturn ONLY JSON that matches this schema (no other text): " + sch.toString();
        long end = deadlineMs > 0 ? System.currentTimeMillis() + deadlineMs : Long.MAX_VALUE;
        Exception last = null;
        for (String model : GROQ_TEXT_MODELS) {
            ct.check();
            long left = end - System.currentTimeMillis();
            if (left <= 0) break;
            JSONObject body = new JSONObject().put("model", model).put("temperature", temperature)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", full)))
                    .put("max_completion_tokens", 8192);
            if (model.startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low");
            Request req = new Request.Builder().url(KeyCheck.GROQ_BASE + "/openai/v1/chat/completions")
                    .header("Authorization", "Bearer " + key)
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json"))).build();
            okhttp3.Call call = aiHttp().newCall(req);
            long capMs = Math.min(timeoutS * 1000L, left);
            call.timeout().timeout(Math.max(1, capMs), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (ct != null) { ct.calls.add(call); if (ct.cancelled) call.cancel(); }
            long tReq = System.currentTimeMillis();
            try (Response r = call.execute()) {
                String resp = r.body() != null ? r.body().string() : "";
                AppLog.i(String.format(Locale.US, "AI (Groq) %s \u2192 HTTP %d in %.1f s (%d KB prompt)", model, r.code(), (System.currentTimeMillis() - tReq) / 1000.0, prompt.length() / 1024));
                if (r.isSuccessful()) {
                    String text = groqAnswer(resp);
                    if (text.isEmpty() || (valid != null && !valid.test(text))) { last = new ApiException("The AI answer was incomplete. Tap Retry."); continue; }
                    return text;
                }
                String msg = errorMessage(resp);
                if (r.code() == 401 || r.code() == 403) { KeyCheck.markInvalid(KeyCheck.Service.GROQ, key, r.code(), msg); throw groqRejected(r.code(), msg); }
                last = new ApiException("Groq " + r.code() + (msg.isEmpty() ? "" : ": " + msg));
            } catch (KeyRejected | CancelToken.Cancelled e) {
                throw e;
            } catch (Exception e) {
                if (ct != null && ct.cancelled) throw new CancelToken.Cancelled();
                last = e instanceof java.io.InterruptedIOException ? new ApiException("no answer from " + model + " within " + (capMs / 1000) + " s") : e;
                AppLog.i("AI (Groq) " + model + ": " + AiClient.describe(last) + ", next model");
            } finally {
                if (ct != null) ct.calls.remove(call);
            }
        }
        throw last instanceof ApiException ? (ApiException) last : new ApiException("Groq: " + (last == null ? "no answer" : AiClient.describe(last)));
    }

    /** the chat answer's text, code fences removed */
    static String groqAnswer(String resp) {
        try {
            JSONArray ch = new JSONObject(resp).optJSONArray("choices");
            if (ch == null || ch.length() == 0) return "";
            JSONObject msg = ch.getJSONObject(0).optJSONObject("message");
            String t = msg == null ? "" : msg.optString("content", "");
            t = t.trim();
            if (t.startsWith("```")) {
                int nl = t.indexOf('\n');
                t = nl < 0 ? "" : t.substring(nl + 1);
                int fence = t.lastIndexOf("```");
                if (fence >= 0) t = t.substring(0, fence);
            }
            return t.trim();
        } catch (Exception e) { return ""; }
    }

    // ------------------------------------------------------------------ v18 ending pass

    /** v18: the AI's chosen ending for one clip */
    public static final class EndPick {
        public int clip = -1, phrase = -1;
        public boolean complete = true;
        public String reason = "";
    }

    static final double END_LOOK = 115;   // seconds of transcript shown after each clip's start

    /**
     * v18 (2.9): a second, focused AI request that only decides WHERE each picked clip ends. The transcript after each
     * clip's start is shown as short numbered phrases (split at punctuation and pauses, so an end can fall inside a long
     * Whisper segment) with the time since the clip's start, and the 90-second limit marked.
     */
    public static String buildEndPrompt(List<Project.Clip> clips, Transcript t, double duration) {
        StringBuilder b = new StringBuilder();
        b.append("You are the editor deciding where each short-form clip (YouTube Shorts / Reels) cut from a podcast should END. ")
         .append("The start of every clip is already fixed. For each clip, read what is said after its start and choose the phrase where the clip's ")
         .append("topic, answer or story is COMPLETE: the sentence that lands the point, punchline, reveal, lesson or emotional peak.\n")
         .append("RULES:\n")
         .append("1. The chosen phrase must finish a complete sentence and thought. Never end on a half sentence, a connector (aur, toh, lekin, ki, ")
         .append("kyunki, and, but, so, because), a list that is still going, a question whose answer has not come yet, or just before the payoff.\n")
         .append("2. PAYOFF CHECK: read the 2-4 phrases right AFTER your choice. If they finish the story (what happened, the result, the reveal, ")
         .append("'so I did X'), answer or explain what was just set up, or deliver the punchline, move the end to include them. A clip that sets up ")
         .append("a problem, a question or a claim must include its answer/explanation when it comes before the limit. Viewers hate a short that stops ")
         .append("right before the payoff.\n")
         .append("3. Do NOT end on a sentence that opens a NEW point ('we also have to worry about X', 'the second thing is', 'aur ek baat'): end on the ")
         .append("sentence before it, or include the new point's explanation if it belongs to the same topic and fits.\n")
         .append("4. Best end: the speaker wraps up and the conversation moves on (a pause, the other person reacts or asks something new, a new topic). ")
         .append("Find where the NEXT topic or question begins and end right before it. When unsure between two ends, choose the LATER one (still before the limit): ")
         .append("an unfinished short is much worse than a slightly long one.\n")
         .append("5. The clip must end BEFORE the line marked '90 SECOND LIMIT'. If the topic continues past it, choose the LAST phrase before ")
         .append("the limit where a point is fully made (a natural mini-conclusion) and set complete=false.\n")
         .append("6. The clip must end AFTER the line marked 'MINIMUM' (shorter is not a finished short) unless the whole story is truly complete ")
         .append("before it (then complete=true). Most good shorts end between the 'TARGET 45 SECONDS' line and the limit: if the story or answer ")
         .append("is still going at 20-40 s, keep going to where it really ends.\n")
         .append("Return ONLY a JSON array, one object per clip: {\"clip\": <clip number>, \"end_phrase\": <phrase number>, ")
         .append("\"complete\": true|false, \"reason\": \"<max 8 words>\"}\n\n");
        for (int ci = 0; ci < clips.size(); ci++) {
            Project.Clip c = clips.get(ci);
            double to = Math.min(duration > 0 ? duration : Double.MAX_VALUE, c.start + END_LOOK);
            List<Object[]> ph = ClipAligner.phrases(t.segments, t.words, c.start, to);
            b.append("=== CLIP ").append(ci).append(" — starts with: \"").append(c.hook == null ? "" : c.hook.replace("\"", "'")).append("\"");
            if (c.title != null && !c.title.isEmpty()) b.append(" (title: ").append(c.title.replace("\"", "'")).append(")");
            b.append(" — the app's current end is phrase ").append(phraseAt(ph, c.end)).append(" ===\n");
            boolean marked = false, min = false, tgt = false;
            double floor = c.minLen > 0 ? c.minLen : ClipAligner.MIN_LEN;
            for (int k = 0; k < ph.size(); k++) {
                double pe = (Double) ph.get(k)[1] - c.start;
                if (!min && pe >= floor) { b.append(String.format(Locale.US, "---- MINIMUM %.0f SECONDS ----\n", floor)); min = true; }
                if (!tgt && pe >= ClipAligner.TARGET_LEN && floor < ClipAligner.TARGET_LEN) { b.append("---- TARGET 45 SECONDS ----\n"); tgt = true; }
                if (!marked && pe > ClipAligner.MAX_LEN) { b.append("---- 90 SECOND LIMIT ----\n"); marked = true; }
                b.append(k).append(String.format(Locale.US, " [+%.0fs] ", pe)).append((String) ph.get(k)[2]).append('\n');
            }
            b.append('\n');
        }
        return b.toString();
    }

    static int phraseAt(List<Object[]> ph, double end) {
        int best = 0; double bd = 1e9;
        for (int k = 0; k < ph.size(); k++) { double d = Math.abs((Double) ph.get(k)[1] - end); if (d < bd) { bd = d; best = k; } }
        return best;
    }

    /** parses the ending pass's answer (tolerates code fences / extra text / one object instead of an array) */
    public static List<EndPick> parseEnds(String raw) {
        List<EndPick> out = new ArrayList<>();
        if (raw == null) return out;
        int a = raw.indexOf('['), b = raw.lastIndexOf(']');
        JSONArray arr = null;
        try {
            if (a >= 0 && b > a) arr = new JSONArray(raw.substring(a, b + 1));
            else { int x = raw.indexOf('{'), y = raw.lastIndexOf('}'); if (x >= 0 && y > x) arr = new JSONArray().put(new JSONObject(raw.substring(x, y + 1))); }
        } catch (Exception e) { return out; }
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            EndPick p = new EndPick();
            p.clip = o.optInt("clip", -1);
            p.phrase = o.optInt("end_phrase", o.optInt("phrase", -1));
            p.complete = o.optBoolean("complete", true);
            p.reason = o.optString("reason", "").trim();
            if (p.clip >= 0 && p.phrase >= 0) out.add(p);
        }
        return out;
    }

    /**
     * v18: applies the AI's endings: each chosen phrase end is validated on the transcript (a complete sentence + a pause,
     * the 90 s limit, never mid-word) by ClipAligner.endFor. Returns how many ends moved. Overlaps with another clip are
     * not allowed (that clip keeps its previous end).
     */
    public static int applyEnds(List<Project.Clip> clips, Transcript t, double duration, List<EndPick> picks) {
        int moved = 0;
        for (EndPick p : picks) {
            if (p.clip < 0 || p.clip >= clips.size()) continue;
            Project.Clip c = clips.get(p.clip);
            double to = Math.min(duration > 0 ? duration : Double.MAX_VALUE, c.start + END_LOOK);
            List<Object[]> ph = ClipAligner.phrases(t.segments, t.words, c.start, to);
            if (p.phrase >= ph.size()) continue;
            double want = (Double) ph.get(p.phrase)[1];
            if (want - c.start < 5) continue;    // nonsense: a few seconds long (endFor stretches short ones to >= 20 s)
            double minL = c.minLen > 0 ? c.minLen : ClipAligner.MIN_LEN;
            double e = ClipAligner.endFor(t.segments, t.words, c.start, want, duration, minL, p.complete);
            if (e - c.start < 8) continue;
            // v19: the ending pass never shortens a clip below the floor (35 s podcast) unless the story ends there
            if (!p.complete && e - c.start < minL - 0.5 && c.end - c.start >= minL - 0.5) continue;
            boolean overlap = false;
            for (Project.Clip o : clips) if (o != c && Math.min(e, o.end) - Math.max(c.start, o.start) > 1.0) overlap = true;
            if (overlap) continue;
            if (Math.abs(e - c.end) > 0.05) moved++;
            c.end = e;
            c.endReason = p.reason;
            c.topicComplete = p.complete;
        }
        return moved;
    }

    /** the AI ending check is optional: after this long the transcript's sentence ends stay (5.0 could wait 15 s+) */
    static long END_CAP_MS = 7000;
    static int END_CALL_TIMEOUT_S = 7;

    /** v18: runs the ending pass on the AI (raced like the clip finder, capped); false = the AI did not answer (rule-based ends stay) */
    public boolean refineEnds(List<Project.Clip> clips, Transcript t, double duration) {
        if (clips.isEmpty()) return false;
        try {
            String prompt = buildEndPrompt(clips, t, duration);
            long t0 = System.currentTimeMillis();
            // 5.1: raced like the clip finder and capped: the ends are already set from the transcript, this only refines them
            String raw = withKeyFallback(prompt, 0.2, s -> !parseEnds(s).isEmpty(), END_CALL_TIMEOUT_S, END_CAP_MS,
                    () -> geminiRace(prompt, true, 0.2, s -> !parseEnds(s).isEmpty(), END_CALL_TIMEOUT_S, END_CAP_MS));
            List<EndPick> picks = parseEnds(raw);
            int moved = applyEnds(clips, t, duration, picks);
            AppLog.i(String.format(Locale.US, "Clip endings: AI checked %d clip%s in %.1f s, %d end%s moved to where the topic finishes",
                    clips.size(), clips.size() == 1 ? "" : "s", (System.currentTimeMillis() - t0) / 1000.0, moved, moved == 1 ? "" : "s"));
            return !picks.isEmpty();
        } catch (CancelToken.Cancelled c) { throw c; }
        catch (Exception e) {
            AppLog.i("Clip endings: AI check skipped (" + e.getMessage() + "), using the transcript's sentence ends");
            return false;
        }
    }

    /** v7: the clip picker's answer shape (Gemini responseSchema, OpenAPI subset) */
    static JSONObject clipSchema() {
        try {
            String[] order = {"type", "question_line", "start_line", "end_line", "start", "end", "hook_text", "end_text", "end_reason", "topic_complete", "title", "needs_context",
                    "narration", "scene_plan", "ending", "estimated_duration", "content_category", "source_language",
                    "hook_score", "standalone_score", "value_score", "payoff_score", "viral_score", "reason", "has_profanity", "video_is_podcast"};
            JSONObject props = new JSONObject();
            for (String k : order) {
                String type = k.endsWith("_line") || k.endsWith("_score") || k.equals("estimated_duration") ? "INTEGER" : (k.equals("start") || k.equals("end") ? "NUMBER"
                        : k.equals("needs_context") || k.equals("has_profanity") || k.equals("video_is_podcast") || k.equals("topic_complete") ? "BOOLEAN" : "STRING");
                JSONObject p = new JSONObject().put("type", type);
                if (k.equals("type")) p.put("enum", new JSONArray().put("qa").put("story").put("opinion").put("fact").put("funny").put("other"));
                props.put(k, p);
            }
            JSONArray ord = new JSONArray(), req = new JSONArray();
            for (String k : order) { ord.put(k); if (!k.equals("reason") && !k.equals("has_profanity") && !k.equals("video_is_podcast") && !k.equals("end_reason") && !k.equals("topic_complete")) req.put(k); }
            JSONObject item = new JSONObject().put("type", "OBJECT").put("properties", props).put("required", req).put("propertyOrdering", ord);
            return new JSONObject().put("type", "ARRAY").put("items", item);
        } catch (Exception e) { return null; }
    }

    /** v7 overall score from the sub-scores (hook weighs most); falls back to the AI's own total */
    static int composite(int hook, int standalone, int value, int payoff, int aiTotal) {
        if (hook < 0 || standalone < 0 || value < 0 || payoff < 0) return aiTotal;
        double s = 0.35 * hook + 0.20 * standalone + 0.25 * value + 0.20 * payoff;
        return (int) Math.max(1, Math.min(100, Math.round(s)));
    }

    /** v14 test hook */
    public static List<Candidate> parseCandidatesForTest(String raw, double duration) throws Exception { return parseCandidates(raw, duration, null); }

    /** Parses and validates the AI's JSON (tolerates code fences/extra text; drops ranges outside the video). */
    static List<Candidate> parseCandidates(String raw, double duration) throws Exception { return parseCandidates(raw, duration, null); }

    /** v6: start/end may be omitted by the AI; they are then taken from the transcript lines it named. */
    static List<Candidate> parseCandidates(String raw, double duration, Transcript tr) throws Exception {
        int a = raw.indexOf('['), b = raw.lastIndexOf(']');
        if (a >= 0 && b > a) raw = raw.substring(a, b + 1);
        JSONArray arr;
        try { arr = new JSONArray(raw); }
        catch (Exception e) { throw new ApiException("The AI answer was incomplete. Tap Retry."); }
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Candidate c = new Candidate();
            c.start = o.optDouble("start", -1);
            c.end = o.optDouble("end", -1);
            c.startLine = o.optInt("start_line", -1);
            c.endLine = o.optInt("end_line", -1);
            if (tr != null) {
                int ns = tr.segments.size();
                if ((c.start < 0 || Double.isNaN(c.start)) && c.startLine >= 0 && c.startLine < ns) c.start = tr.segments.get(c.startLine).start;
                if ((c.end <= 0 || Double.isNaN(c.end)) && c.endLine >= 0 && c.endLine < ns) c.end = tr.segments.get(c.endLine).end;
            }
            int ai = Math.max(1, Math.min(100, o.optInt("viral_score", 95 - i * 3)));
            c.hookScore = sub(o, "hook_score"); c.standaloneScore = sub(o, "standalone_score");
            c.valueScore = sub(o, "value_score"); c.payoffScore = sub(o, "payoff_score");
            c.score = composite(c.hookScore, c.standaloneScore, c.valueScore, c.payoffScore, ai);
            c.title = o.optString("title", "").trim();
            c.hook = o.optString("hook_text", o.optString("hook", "")).trim();
            c.endText = o.optString("end_text", "").trim();
            c.endReason = o.optString("end_reason", "").trim();
            if (o.has("topic_complete")) c.topicComplete = o.optBoolean("topic_complete", true);
            c.reason = o.optString("reason", "").trim();
            c.type = o.optString("type", "").trim().toLowerCase(Locale.US);
            c.questionLine = o.optInt("question_line", -1);
            c.needsContext = o.optBoolean("needs_context", false);
            c.aiProfanity = o.optBoolean("has_profanity", false);
            if (o.has("video_is_podcast")) c.podcastVote = o.optBoolean("video_is_podcast", false);
            if (Double.isNaN(c.start) || Double.isNaN(c.end)) continue;
            if (duration > 0) {
                if (c.start >= duration - 3) continue;
                if (c.end > duration) c.end = duration;
            }
            if (c.start >= 0 && c.end > c.start) out.add(c);
        }
        Collections.sort(out, (x, y) -> Integer.compare(y.score, x.score));
        return out;
    }

    private static int sub(JSONObject o, String k) {
        if (!o.has(k)) return -1;
        int v = o.optInt(k, -1);
        return v < 0 ? -1 : Math.min(100, v);
    }

    /** Hinglish captions (kept for older callers): romanise words 1:1. */
    public List<String> romanize(List<String> words) { return transliterate(words, CaptionScript.HINGLISH); }

    /**
     * v6: 1:1 word transliteration so word timings stay intact. target = CaptionScript.HINGLISH (Roman) or
     * CaptionScript.HINDI (Devanagari). Handles Devanagari, Urdu script and Roman Hinglish input. Null on mismatch.
     */
    public List<String> transliterate(List<String> words, String target) {
        try {
            JSONArray in = new JSONArray();
            for (String w : words) in.put(w);
            String rule = CaptionScript.HINDI.equals(target)
                    ? "Write each item of this JSON array of spoken Hindi/Hinglish/Urdu words in Hindi Devanagari script (e.g. \"kya\"->\"क्या\", "
                    + "\"hai\"->\"है\", \"bhai\"->\"भाई\", \"کیا\"->\"क्या\"). English words: write them in Devanagari as Hindi speakers do "
                    + "(\"video\"->\"वीडियो\", \"phone\"->\"फ़ोन\"). Keep punctuation and numbers. "
                    : "Transliterate each item of this JSON array of spoken words (Hindi/Urdu, or another Indian language such as Bengali, "
                    + "Punjabi, Tamil, Telugu: keep that language, only change the letters) into casual Roman letters as Indians type on WhatsApp "
                    + "(e.g. \"क्या\"->\"kya\", \"है\"->\"hai\", \"भाई\"->\"bhai\", \"کیا\"->\"kya\", \"ভালোবাসা\"->\"bhalobasha\", \"আমি\"->\"ami\"). "
                    + "Keep English words, punctuation and numbers unchanged. ";
            String prompt = rule + "Do not translate, merge, split or skip items. Return ONLY a JSON array of strings with EXACTLY "
                    + words.size() + " items in the same order.\n" + in;
            String raw = geminiLight(prompt);
            int a = raw.indexOf('['), b = raw.lastIndexOf(']');
            JSONArray arr = new JSONArray(raw.substring(a, b + 1));
            if (arr.length() != words.size()) { AppLog.e("Caption conversion returned " + arr.length() + " of " + words.size() + " words"); return null; }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i, words.get(i)));
            return out;
        } catch (CancelToken.Cancelled c) {
            throw c;
        } catch (Exception e) {
            AppLog.e("Caption script conversion failed, keeping original script: " + e.getMessage());
            return null;
        }
    }

    /** v6: sentence translation for captions in another language. Null on failure / count mismatch. */
    public List<String> translate(List<String> sentences, String language) {
        try {
            JSONArray in = new JSONArray();
            for (String s : sentences) in.put(s);
            String prompt = "Translate each item of this JSON array of spoken sentences (from a video, in order) into natural, simple " + language
                    + " as subtitles. Keep names, numbers and the speaker's tone; keep each translation short. One translation per item: do not merge, "
                    + "split or skip items. Return ONLY a JSON array of strings with EXACTLY " + sentences.size() + " items in the same order.\n" + in;
            String raw = geminiLight(prompt);
            int a = raw.indexOf('['), b = raw.lastIndexOf(']');
            JSONArray arr = new JSONArray(raw.substring(a, b + 1));
            if (arr.length() != sentences.size()) { AppLog.e("Translation returned " + arr.length() + " of " + sentences.size() + " lines"); return null; }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i, sentences.get(i)));
            return out;
        } catch (CancelToken.Cancelled c) {
            throw c;
        } catch (Exception e) {
            AppLog.e("Caption translation failed: " + e.getMessage());
            return null;
        }
    }

    /** simple text jobs: no/minimal thinking (fast), one model at a time with fast hand-over */
    private String geminiLight(String prompt) throws Exception {
        light = true;
        // 5.1: one model at a time (a job converts several shorts' captions at once: two each would hit the per-minute
        // free limit), but a busy / silent model hands over at once or after the stagger instead of 15 s
        java.util.function.Predicate<String> ok = s -> s.indexOf('[') >= 0 && s.lastIndexOf(']') > s.indexOf('[');
        try { return withKeyFallback(prompt, 0.1, ok, raceTimeoutS(prompt.length()) + 20, 0, () -> geminiRace(prompt, true, 0.1, ok, raceTimeoutS(prompt.length()), 0, 1)); }
        finally { light = false; }
    }
}
