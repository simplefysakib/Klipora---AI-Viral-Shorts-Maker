package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 5.3: the live key check used on save (Settings / onboarding) and before a job starts (Generate): what each answer
 * means, the 10-minute memory, the ~5 s time limit (never blocks on a network problem) and the pre-flight decision.
 */
@RunWith(RobolectricTestRunner.class)
public class V35KeyCheckTest {
    static final String GEM = "AIzaCHECKKEY000000000000000000000000000", GROQ = "gsk_CHECKKEY00000000000000000000000000000000000000000";
    FakeAiServer srv;

    @Before public void setUp() throws Exception {
        KeyCheck.forget();
        srv = new FakeAiServer();
        KeyCheck.setBasesForTest(srv.base(), srv.base(), 800);
    }

    @After public void tearDown() { KeyCheck.setBasesForTest(null, null, 0); KeyCheck.forget(); srv.close(); }

    @Test public void geminiAnswers() {
        srv.on("GET /v1beta/models", 200, "{\"models\":[{\"name\":\"models/gemini-3.5-flash-lite\"}]}");
        KeyCheck.Verdict v = KeyCheck.check(KeyCheck.Service.GEMINI, GEM);
        assertEquals(KeyCheck.Result.OK, v.result);
        assertTrue("a tiny request: one model, the key in the header", srv.bodies.get(0).startsWith("GET /v1beta/models"));
        assertEquals("GET /v1beta/models " + GEM, srv.auth.get(0));

        KeyCheck.forget();
        srv.on("GET /v1beta/models", 400, FakeAiServer.geminiKeyInvalid());
        v = KeyCheck.check(KeyCheck.Service.GEMINI, GEM);
        assertEquals(KeyCheck.Result.INVALID, v.result);
        assertEquals(400, v.code);
        assertEquals("API key not valid. Please pass a valid API key.", v.message);

        for (int code : new int[]{401, 403}) {
            KeyCheck.forget();
            srv.on("GET /v1beta/models", code, "{\"error\":{\"code\":" + code + ",\"message\":\"denied\"}}");
            assertEquals("HTTP " + code, KeyCheck.Result.INVALID, KeyCheck.check(KeyCheck.Service.GEMINI, GEM).result);
        }
        // busy, server trouble, region block: we couldn't tell -> never "not valid"
        for (int code : new int[]{429, 500, 503}) {
            KeyCheck.forget();
            srv.on("GET /v1beta/models", code, "{\"error\":{\"code\":" + code + ",\"message\":\"busy\"}}");
            assertEquals("HTTP " + code, KeyCheck.Result.UNREACHABLE, KeyCheck.check(KeyCheck.Service.GEMINI, GEM).result);
        }
        KeyCheck.forget();
        srv.on("GET /v1beta/models", 400, "{\"error\":{\"code\":400,\"message\":\"User location is not supported for the API use.\"}}");
        assertEquals(KeyCheck.Result.UNREACHABLE, KeyCheck.check(KeyCheck.Service.GEMINI, GEM).result);
    }

    @Test public void groqAnswers() {
        srv.on("GET /openai/v1/models", 200, "{\"data\":[]}");
        assertEquals(KeyCheck.Result.OK, KeyCheck.check(KeyCheck.Service.GROQ, GROQ).result);
        assertEquals("GET /openai/v1/models Bearer " + GROQ, srv.auth.get(0));
        KeyCheck.forget();
        srv.on("GET /openai/v1/models", 401, FakeAiServer.groqKeyInvalid());
        KeyCheck.Verdict v = KeyCheck.check(KeyCheck.Service.GROQ, GROQ);
        assertEquals(KeyCheck.Result.INVALID, v.result);
        assertEquals("Invalid API Key", v.message);
        KeyCheck.forget();
        srv.on("GET /openai/v1/models", 429, "{\"error\":{\"message\":\"rate limit\"}}");
        assertEquals(KeyCheck.Result.UNREACHABLE, KeyCheck.check(KeyCheck.Service.GROQ, GROQ).result);
    }

    @Test public void slowOrNoNetwork_isUnreachable_withinTheTimeLimit() throws Exception {
        srv.on("GET /v1beta/models", 200, "{}", 3000);
        long t0 = System.currentTimeMillis();
        KeyCheck.Verdict v = KeyCheck.check(KeyCheck.Service.GEMINI, GEM);
        long ms = System.currentTimeMillis() - t0;
        assertEquals(KeyCheck.Result.UNREACHABLE, v.result);
        assertTrue("gave up at the limit: " + ms, ms < 2500);
        // nothing listening at all (no connection)
        java.net.ServerSocket s = new java.net.ServerSocket(0);
        int port = s.getLocalPort();
        s.close();
        KeyCheck.setBasesForTest("http://127.0.0.1:" + port, "http://127.0.0.1:" + port, 800);
        assertEquals(KeyCheck.Result.UNREACHABLE, KeyCheck.check(KeyCheck.Service.GROQ, GROQ, true).result);
        assertEquals(KeyCheck.Result.UNREACHABLE, KeyCheck.check(KeyCheck.Service.GEMINI, GEM, true).result);
        assertEquals(KeyCheck.Preflight.START, KeyCheck.preflight(GEM, GROQ));
    }

    @Test public void goodAnswerIsRememberedPerKey() {
        srv.on("GET /v1beta/models", 200, "{}");
        assertTrue(KeyCheck.check(KeyCheck.Service.GEMINI, GEM).ok());
        KeyCheck.Verdict again = KeyCheck.check(KeyCheck.Service.GEMINI, GEM);
        assertTrue(again.ok());
        assertTrue(again.cached);
        assertEquals("the second tap is instant: no request", 1, srv.count("GET /v1beta/models"));
        assertEquals(10 * 60_000L, KeyCheck.GOOD_TTL_MS);
        // another key is checked on its own
        assertTrue(KeyCheck.check(KeyCheck.Service.GEMINI, GEM.replace('0', '1')).ok());
        assertEquals(2, srv.count("GET /v1beta/models"));
        // save-time checks ask fresh
        assertTrue(KeyCheck.check(KeyCheck.Service.GEMINI, GEM, true).ok());
        assertEquals(3, srv.count("GET /v1beta/models"));
        assertNull(KeyCheck.cached(KeyCheck.Service.GROQ, GROQ));
    }

    @Test public void preflightDecision() {
        KeyCheck.Verdict ok = KeyCheck.classify(KeyCheck.Service.GEMINI, 200, "{}");
        KeyCheck.Verdict bad = KeyCheck.classify(KeyCheck.Service.GEMINI, 400, FakeAiServer.geminiKeyInvalid());
        KeyCheck.Verdict groqBad = KeyCheck.classify(KeyCheck.Service.GROQ, 401, FakeAiServer.groqKeyInvalid());
        KeyCheck.Verdict off = KeyCheck.unreachable("no connection");
        assertEquals(KeyCheck.Preflight.START, KeyCheck.decide(ok, ok));
        assertEquals(KeyCheck.Preflight.START, KeyCheck.decide(off, off));
        assertEquals(KeyCheck.Preflight.START, KeyCheck.decide(ok, off));
        assertEquals(KeyCheck.Preflight.GEMINI_INVALID, KeyCheck.decide(bad, ok));
        assertEquals("a bad Gemini key with a good Groq key still shows the popup", KeyCheck.Preflight.GEMINI_INVALID, KeyCheck.decide(bad, off));
        assertEquals(KeyCheck.Preflight.GROQ_INVALID, KeyCheck.decide(ok, groqBad));
        assertEquals(KeyCheck.Preflight.GROQ_INVALID, KeyCheck.decide(off, groqBad));
        assertEquals(KeyCheck.Preflight.BOTH_INVALID, KeyCheck.decide(bad, groqBad));
    }

    @Test public void preflightChecksBothAtOnce() {
        srv.on("GET /v1beta/models", 400, FakeAiServer.geminiKeyInvalid(), 400);
        srv.on("GET /openai/v1/models", 200, "{\"data\":[]}", 400);
        long t0 = System.currentTimeMillis();
        assertEquals(KeyCheck.Preflight.GEMINI_INVALID, KeyCheck.preflight(GEM, GROQ));
        long ms = System.currentTimeMillis() - t0;
        assertTrue("both at the same time: " + ms, ms < 750);
        assertFalse(KeyCheck.bothKnownGood(GEM, GROQ));
        // remembered: the next tap decides without any request
        t0 = System.currentTimeMillis();
        assertEquals(KeyCheck.Preflight.GEMINI_INVALID, KeyCheck.preflight(GEM, GROQ));
        assertTrue(System.currentTimeMillis() - t0 < 200);
        assertEquals(1, srv.count("GET /v1beta/models"));
        srv.on("GET /v1beta/models", 200, "{}");
        KeyCheck.forget();
        assertEquals(KeyCheck.Preflight.START, KeyCheck.preflight(GEM, GROQ));
        assertTrue(KeyCheck.bothKnownGood(GEM, GROQ));
    }

    @Test public void saveTimeWords() {
        KeyCheck.Verdict ok = KeyCheck.classify(KeyCheck.Service.GROQ, 200, "{}");
        KeyCheck.Verdict bad = KeyCheck.classify(KeyCheck.Service.GROQ, 401, FakeAiServer.groqKeyInvalid());
        KeyCheck.Verdict off = KeyCheck.unreachable("timeout");
        assertEquals("Key not valid. Create a new key and paste it here.", KeyCheck.fieldMessage(bad));
        assertNull(KeyCheck.fieldMessage(ok));
        assertNull("no connection never marks a field", KeyCheck.fieldMessage(off));
        assertEquals("Keys saved and checked", KeyCheck.saveSummary(ok, ok));
        assertEquals("Keys saved. Couldn't check the keys (no connection)", KeyCheck.saveSummary(ok, off));
    }
}
