package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 5.1: the AI step races models from the first moment (no 15 s wait), moves on at once on 503 / 429 / timeouts, keeps
 * the validity checks, cancels the losers, caps the optional ending check. A fake Gemini server (an interceptor) plays
 * the timings from Sakib's real logs of Nov 3.
 */
@RunWith(RobolectricTestRunner.class)
public class V33AiRaceTest {
    Context c;
    static final String LITE1 = "gemini-3.5-flash-lite", LITE2 = "gemini-3.1-flash-lite", FLASH1 = "gemini-3.8-flash", FLASH2 = "gemini-3.7-flash";
    static final String VALID = "[{\"start\":10,\"end\":50,\"start_line\":1,\"end_line\":5,\"hook_text\":\"a b c\",\"viral_score\":90}]";

    /** what one fake model does: answer after delayMs with code/text; hang = never answers until cancelled */
    static final class Plan {
        long delayMs; int code = 200; String text = VALID; String error = ""; boolean hang;
        static Plan ok(long ms, String text) { Plan p = new Plan(); p.delayMs = ms; p.text = text; return p; }
        static Plan ok(long ms) { return ok(ms, VALID); }
        static Plan err(long ms, int code, String msg) { Plan p = new Plan(); p.delayMs = ms; p.code = code; p.error = msg; return p; }
        static Plan hang() { Plan p = new Plan(); p.hang = true; p.delayMs = 120000; return p; }
    }

    final Map<String, List<Plan>> plans = new ConcurrentHashMap<>();
    final List<String> asked = new CopyOnWriteArrayList<>();
    final List<String> cancelled = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final AtomicInteger open = new AtomicInteger();

    void plan(String model, Plan... ps) { List<Plan> l = new CopyOnWriteArrayList<>(); Collections.addAll(l, ps); plans.put(model, l); }

    final Interceptor fake = chain -> {
        String path = chain.request().url().encodedPath();
        String model = path.substring(path.indexOf("/models/") + 8, path.indexOf(':'));
        asked.add(model);
        okio.Buffer buf = new okio.Buffer();
        if (chain.request().body() != null) chain.request().body().writeTo(buf);
        bodies.add(model + " " + buf.readUtf8());
        List<Plan> l = plans.get(model);
        Plan p = l == null || l.isEmpty() ? Plan.err(10, 404, "not found") : (l.size() > 1 ? l.remove(0) : l.get(0));
        open.incrementAndGet();
        try {
            long end = System.currentTimeMillis() + p.delayMs;
            while (System.currentTimeMillis() < end) {
                if (chain.call().isCanceled()) { cancelled.add(model); throw new IOException("Canceled"); }
                try { Thread.sleep(10); } catch (InterruptedException e) { cancelled.add(model); throw new IOException("Canceled"); }
            }
        } finally { open.decrementAndGet(); }
        String body;
        try {
            body = p.code == 200
                    ? new JSONObject().put("candidates", new JSONArray().put(new JSONObject().put("content", new JSONObject().put("parts",
                        new JSONArray().put(new JSONObject().put("text", p.text)))))).toString()
                    : new JSONObject().put("error", new JSONObject().put("code", p.code).put("message", p.error)).toString();
        } catch (Exception e) { throw new IOException(e); }
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(p.code).message("x")
                .body(ResponseBody.create(body, MediaType.parse("application/json"))).build();
    };

    long savedExtra, savedPause, savedPerKb, savedEndCap, savedHedge;
    int savedEndTimeout;

    @Before public void setUp() {
        c = ApplicationProvider.getApplicationContext();
        Prefs.sp(c).edit().clear().commit();
        Prefs.setKeys(c, "AIzaTESTKEY0000000000000000000000000000", "gsk_TESTKEY000000000000000000000000000000000000000000");
        // the key's ranked list (as listModels stores it): order() puts the 2 Flash-Lite first, then the Flash models
        Prefs.sp(c).edit().putString("gemini_models_v4", FLASH1 + "," + FLASH2 + "," + LITE1 + "," + LITE2)
                .putLong("gemini_models_v4_at", System.currentTimeMillis()).commit();
        AiClient.testInterceptor = fake;
        savedExtra = AiClient.RACE_EXTRA_MS; savedPause = AiClient.RACE_RETRY_PAUSE_MS; savedPerKb = AiClient.RACE_EXTRA_PER_KB_MS;
        savedEndCap = AiClient.END_CAP_MS; savedEndTimeout = AiClient.END_CALL_TIMEOUT_S; savedHedge = AiClient.HEDGE_MS;
    }

    @After public void tearDown() {
        AiClient.testInterceptor = null;
        AiClient.RACE_EXTRA_MS = savedExtra; AiClient.RACE_RETRY_PAUSE_MS = savedPause; AiClient.RACE_EXTRA_PER_KB_MS = savedPerKb;
        AiClient.END_CAP_MS = savedEndCap; AiClient.END_CALL_TIMEOUT_S = savedEndTimeout; AiClient.HEDGE_MS = savedHedge;
    }

    AiClient ai() { return new AiClient(c, new CancelToken()); }

    static boolean validJson(String s) {
        try { return !AiClient.parseCandidates(s, 600).isEmpty(); } catch (Exception e) { return false; }
    }

    void waitLosersCancelled() throws Exception {
        long end = System.currentTimeMillis() + 3000;
        while (open.get() > 0 && System.currentTimeMillis() < end) Thread.sleep(20);
        assertEquals("every losing call is cancelled", 0, open.get());
    }

    @Test public void twoModelsAskedAtOnce() throws Exception {
        plan(LITE1, Plan.ok(400)); plan(LITE2, Plan.ok(150));
        long t0 = System.currentTimeMillis();
        String r = ai().geminiHedged("prompt", true, 0.3, 15000, V33AiRaceTest::validJson);
        long ms = System.currentTimeMillis() - t0;
        assertEquals(VALID, r);
        assertTrue("answered by the faster one, no 15 s wait: " + ms, ms < 1500);
        assertTrue(asked.contains(LITE1) && asked.contains(LITE2));
        waitLosersCancelled();
        assertTrue("the slower call was cancelled", cancelled.contains(LITE1));
    }

    @Test public void silentModelAnd503MoveOnAtOnce() throws Exception {
        // Sakib, 23:00: the first model stayed silent, the second answered 503 "high demand"
        plan(LITE1, Plan.hang()); plan(LITE2, Plan.err(200, 503, "This model is currently experiencing high demand."));
        plan(FLASH1, Plan.ok(300));
        long t0 = System.currentTimeMillis();
        String r = ai().geminiHedged("prompt", true, 0.3, 15000, V33AiRaceTest::validJson);
        long ms = System.currentTimeMillis() - t0;
        assertEquals(VALID, r);
        assertTrue("503 hands the lane to the next model at once: " + ms, ms < 2000);
        assertEquals(FLASH1, asked.get(2));
        waitLosersCancelled();
        assertTrue(cancelled.contains(LITE1));
        // the busy model is benched briefly so the ending check right after doesn't ask it again
        assertTrue(Prefs.sp(c).getLong("gemini_bench_" + LITE2, 0) > System.currentTimeMillis());
    }

    @Test public void invalidAnswerDoesNotWin() throws Exception {
        plan(LITE1, Plan.ok(50, "[{\"start\":10,")); plan(LITE2, Plan.ok(400));
        String r = ai().geminiHedged("prompt", true, 0.3, 15000, V33AiRaceTest::validJson);
        assertEquals(VALID, r);
    }

    @Test public void invalidEverywhereKeepsTheRetryMessage() throws Exception {
        for (String m : new String[]{LITE1, LITE2, FLASH1, FLASH2}) plan(m, Plan.ok(20, "[{\"start\":"));
        try { ai().geminiHedged("prompt", true, 0.3, 15000, V33AiRaceTest::validJson); fail(); }
        catch (AiClient.ApiException e) { assertEquals("The AI answer was incomplete. Tap Retry.", e.getMessage()); }
        assertEquals("each model asked once (an invalid answer is not retried)", 4, asked.size());
    }

    @Test public void badKeyStopsTheRaceWithItsMessage() throws Exception {
        plan(LITE1, Plan.err(50, 400, "API key not valid. Please pass a valid API key.")); plan(LITE2, Plan.hang());
        long t0 = System.currentTimeMillis();
        try { ai().geminiHedged("prompt", true, 0.3, 15000, V33AiRaceTest::validJson); fail(); }
        catch (AiClient.ApiException e) { assertTrue(e.getMessage(), e.getMessage().contains("API key")); }
        assertTrue(System.currentTimeMillis() - t0 < 1500);
        waitLosersCancelled();
    }

    @Test public void dailyLimitBenchesAndMovesOn() throws Exception {
        plan(LITE1, Plan.err(30, 429, "Quota exceeded for metric: generate_content_free_tier_requests, limit: 500, model: gemini-3.5-flash-lite, "
                + "description: Generate content requests per day limit exceeded. Please retry in 14h22m1.2s."));
        plan(LITE2, Plan.err(30, 503, "high demand")); plan(FLASH1, Plan.ok(100));
        assertEquals(VALID, ai().geminiHedged("prompt", true, 0.3, 15000, V33AiRaceTest::validJson));
        assertTrue("benched until Google's reset time", Prefs.sp(c).getLong("gemini_bench_" + LITE1, 0) > System.currentTimeMillis() + 13 * 3600_000L);
    }

    @Test public void allBusyGetOneMoreTryThenAClearError() throws Exception {
        for (String m : new String[]{LITE1, LITE2, FLASH1, FLASH2}) plan(m, Plan.err(20, 503, "high demand"));
        AiClient.RACE_RETRY_PAUSE_MS = 50;
        try { ai().geminiHedged("prompt", true, 0.3, 15000, V33AiRaceTest::validJson); fail(); }
        catch (AiClient.ApiException e) { assertTrue(e.getMessage(), e.getMessage().startsWith("AI scoring failed: Gemini 503")); }
        assertEquals("4 models + one more try each", 8, asked.size());
    }

    @Test public void allBusyThenOneRecovers() throws Exception {
        plan(LITE1, Plan.err(20, 503, "high demand"), Plan.ok(50));
        plan(LITE2, Plan.err(20, 503, "high demand")); plan(FLASH1, Plan.err(20, 429, "Resource exhausted, per minute")); plan(FLASH2, Plan.err(20, 500, "internal"));
        AiClient.RACE_RETRY_PAUSE_MS = 50;
        assertEquals(VALID, ai().geminiHedged("prompt", true, 0.3, 15000, V33AiRaceTest::validJson));
    }

    @Test public void perMinuteLimitEverywhereSaysWait() throws Exception {
        for (String m : new String[]{LITE1, LITE2, FLASH1, FLASH2}) plan(m, Plan.err(20, 429, "Resource has been exhausted (e.g. check quota)."));
        AiClient.RACE_RETRY_PAUSE_MS = 50;
        try { ai().geminiHedged("prompt", true, 0.3, 15000, V33AiRaceTest::validJson); fail(); }
        catch (AiClient.ApiException e) { assertEquals("Gemini's free limit is used up for now. Wait a minute and tap Retry.", e.getMessage()); }
    }

    @Test public void timeoutMovesOnAndAThirdModelJoins() throws Exception {
        plan(LITE1, Plan.hang()); plan(LITE2, Plan.hang()); plan(FLASH1, Plan.ok(200)); plan(FLASH2, Plan.ok(5000));
        AiClient.RACE_EXTRA_MS = 300; AiClient.RACE_EXTRA_PER_KB_MS = 0;
        long t0 = System.currentTimeMillis();
        String r = ai().geminiRace("prompt", true, 0.3, V33AiRaceTest::validJson, 30, 0);
        long ms = System.currentTimeMillis() - t0;
        assertEquals(VALID, r);
        assertTrue("both silent: a third model joined after the stagger: " + ms, ms < 1500);
        waitLosersCancelled();
    }

    @Test public void perCallTimeoutIsShort() throws Exception {
        plan(LITE1, Plan.hang()); plan(LITE2, Plan.hang()); plan(FLASH1, Plan.ok(100));
        AiClient.RACE_EXTRA_MS = 60000;
        long t0 = System.currentTimeMillis();
        String r = ai().geminiRace("prompt", true, 0.3, V33AiRaceTest::validJson, 1, 0);
        long ms = System.currentTimeMillis() - t0;
        assertEquals(VALID, r);
        assertTrue("1 s per-call limit, then next model: " + ms, ms >= 900 && ms < 3000);
    }

    @Test public void endingCheckIsCapped() throws Exception {
        for (String m : new String[]{LITE1, LITE2, FLASH1, FLASH2}) plan(m, Plan.hang());
        long t0 = System.currentTimeMillis();
        try { ai().geminiRace("ends", true, 0.2, s -> true, 30, 1200); fail(); }
        catch (AiClient.ApiException e) { assertTrue(e.getMessage(), e.getMessage().contains("no answer within")); }
        long ms = System.currentTimeMillis() - t0;
        assertTrue("hard cap: " + ms, ms < 1800);
        waitLosersCancelled();
    }

    @Test public void raceTimeoutScalesWithPrompt() {
        assertEquals(20, AiClient.raceTimeoutS(1000));
        assertEquals(25, AiClient.raceTimeoutS(22 * 1024));   // Sakib's Hindi video
        assertEquals(40, AiClient.raceTimeoutS(82 * 1024));   // the 1 h 45 min podcast: the old 40 s limit
        assertEquals(40, AiClient.raceTimeoutS(400 * 1024));
    }

    /** the race sends exactly the request 5.0 sent (same schema, JSON mode, thinking level): pick quality is unchanged */
    @Test public void sameRequestAsBefore() throws Exception {
        plan(LITE1, Plan.ok(20)); plan(LITE2, Plan.ok(400));
        AiClient a = ai();
        java.lang.reflect.Field f = AiClient.class.getDeclaredField("schema");
        f.setAccessible(true);
        f.set(a, AiClient.clipSchema());
        a.geminiHedged("prompt", true, 0.3, 15000, V33AiRaceTest::validJson);
        String b = null;
        for (String s : bodies) if (s.startsWith(LITE1 + " ")) b = s.substring(LITE1.length() + 1);
        JSONObject gc = new JSONObject(b).getJSONObject("generationConfig");
        assertEquals("application/json", gc.getString("responseMimeType"));
        assertTrue(gc.has("responseSchema"));
        assertEquals(0.3, gc.getDouble("temperature"), 1e-9);
        assertEquals("low", gc.getJSONObject("thinkingConfig").getString("thinkingLevel"));
        assertEquals(4, new JSONObject(b).getJSONArray("safetySettings").length());
    }

    @Test public void userCancelEndsTheRace() throws Exception {
        for (String m : new String[]{LITE1, LITE2, FLASH1, FLASH2}) plan(m, Plan.hang());
        CancelToken ct = new CancelToken();
        new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) {} ct.cancelled = true; }).start();
        long t0 = System.currentTimeMillis();
        try { new AiClient(c, ct).geminiHedged("prompt", true, 0.3, 15000, s -> true); fail(); }
        catch (CancelToken.Cancelled expected) {}
        assertTrue(System.currentTimeMillis() - t0 < 1500);
        waitLosersCancelled();
    }

    @Test public void captionConversionAsksOneModelAtATime() throws Exception {
        plan(LITE1, Plan.ok(100, "[\"kya\",\"hai\"]")); plan(LITE2, Plan.ok(50, "[\"kya\",\"hai\"]"));
        List<String> out = ai().transliterate(java.util.Arrays.asList("क्या", "है"), CaptionScript.HINGLISH);
        assertEquals(java.util.Arrays.asList("kya", "hai"), out);
        assertEquals("one request per conversion (several shorts convert at once: per-minute limit)", 1, asked.size());
        // a busy model still hands over at once
        asked.clear();
        plan(LITE1, Plan.err(20, 503, "high demand")); plan(LITE2, Plan.ok(50, "[\"kya\",\"hai\"]"));
        Prefs.sp(c).edit().remove("gemini_bench_" + LITE1).commit();
        assertEquals(java.util.Arrays.asList("kya", "hai"), ai().transliterate(java.util.Arrays.asList("क्या", "है"), CaptionScript.HINGLISH));
        assertEquals(2, asked.size());
        // light jobs keep minimal thinking
        String b = null;
        for (String s : bodies) if (s.startsWith(LITE2 + " ")) b = s.substring(LITE2.length() + 1);
        assertEquals("minimal", new JSONObject(b).getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"));
    }

    // ------------------------------------------------------------------ old vs new on Sakib's logged timings

    static final double SCALE = 0.25;   // the logs' seconds x 0.25, so the comparison runs in seconds
    static long s(double sec) { return Math.round(sec * 1000 * SCALE); }

    double timeIt(boolean old) throws Exception {
        AiClient a = ai();
        long t0 = System.currentTimeMillis();
        String r = old ? a.geminiHedgedSerial("prompt", true, 0.3, AiClient.HEDGE_MS, V33AiRaceTest::validJson)
                : a.geminiHedged("prompt", true, 0.3, 15000, V33AiRaceTest::validJson);
        assertEquals(VALID, r);
        return (System.currentTimeMillis() - t0) / 1000.0 / SCALE;
    }

    void scenario(String name, Runnable setup, double logged) throws Exception {
        AiClient.HEDGE_MS = s(15);
        AiClient.RACE_EXTRA_MS = s(6); AiClient.RACE_EXTRA_PER_KB_MS = 0;
        setup.run(); asked.clear(); Prefs.sp(c).edit().putString("gemini_models_v4", FLASH1 + "," + FLASH2 + "," + LITE1 + "," + LITE2)
                .putLong("gemini_models_v4_at", System.currentTimeMillis()).commit();
        for (String k : new java.util.HashSet<>(Prefs.sp(c).getAll().keySet())) if (k.startsWith("gemini_bench_")) Prefs.sp(c).edit().remove(k).commit();
        double o = timeIt(true);
        waitLosersCancelled();
        setup.run(); asked.clear();
        for (String k : new java.util.HashSet<>(Prefs.sp(c).getAll().keySet())) if (k.startsWith("gemini_bench_")) Prefs.sp(c).edit().remove(k).commit();
        double n = timeIt(false);
        waitLosersCancelled();
        System.out.println(String.format(Locale.US, "AIRACE %s: logged %.1f s | old flow %.1f s | new flow %.1f s", name, logged, o, n));
        assertTrue(name + ": new " + n + " vs old " + o, n <= o + 0.6);
    }

    @Test public void oldVersusNewOnSakibsTimings() throws Exception {
        // podcast pick 22:44:35 -> 22:45:01: first model silent, the second answered 200 in 11.1 s
        scenario("podcast pick (82 KB)", () -> { plan(LITE1, Plan.hang()); plan(LITE2, Plan.ok(s(11.1))); }, 26.0);
        // ending check 22:45:18 -> 22:45:35: first model silent, the second answered in 1.6 s
        scenario("ending check (4 KB)", () -> { plan(LITE1, Plan.hang()); plan(LITE2, Plan.ok(s(1.6))); }, 16.9);
        // Hindi pick 23:00:09: first model silent, the second 503 after 5.9 s; a Flash answer assumed 8 s
        scenario("Hindi pick (22 KB), Flash answer assumed 8 s", () -> { plan(LITE1, Plan.hang());
            plan(LITE2, Plan.err(s(5.9), 503, "This model is currently experiencing high demand.")); plan(FLASH1, Plan.ok(s(8))); plan(FLASH2, Plan.ok(s(8))); }, -1);
        // a normal day: the first model answers in 6 s
        scenario("normal (first model 6 s, second 7 s)", () -> { plan(LITE1, Plan.ok(s(6))); plan(LITE2, Plan.ok(s(7))); }, -1);
    }
}
