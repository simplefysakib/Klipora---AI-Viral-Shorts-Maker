package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.PromptManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * 5.3 (Sakib's bug report, Xiaomi 24090RA29I, Nov 4): Groq transcribed fine, then "Gemini rejected your API key: API
 * key not valid. Please pass a valid API key.. Update it in Settings." ended the whole job at the clip pick.
 * Now: a rejected Gemini key -> Groq's text model picks the clips (and checks the endings), with one line in the result;
 * a rejected Groq key -> Gemini; fails only when no working key is left, naming the bad key; no doubled period.
 * Everything runs against a local fake Gemini + Groq server.
 */
@RunWith(RobolectricTestRunner.class)
public class V35KeyFallbackTest {
    static final String GEM = "AIzaTESTKEY0000000000000000000000000000", GROQ = "gsk_TESTKEY000000000000000000000000000000000000000000";
    static final String VALID = "[{\"start\":10,\"end\":50,\"start_line\":1,\"end_line\":5,\"hook_text\":\"a b c\",\"viral_score\":90}]";
    Context c;
    FakeAiServer srv;

    @Before public void setUp() throws Exception {
        c = ApplicationProvider.getApplicationContext();
        Prefs.sp(c).edit().clear().commit();
        Prefs.setKeys(c, GEM, GROQ);
        KeyCheck.forget();
        srv = new FakeAiServer();
        KeyCheck.setBasesForTest(srv.base(), srv.base(), 1500);
        AiClient.testInterceptor = null;
        // the key's model list is cached (as listModels stores it), so the race asks these two first
        Prefs.sp(c).edit().putString("gemini_models_v4", "gemini-3.5-flash-lite,gemini-3.1-flash-lite")
                .putLong("gemini_models_v4_at", System.currentTimeMillis()).commit();
    }

    @After public void tearDown() {
        KeyCheck.setBasesForTest(null, null, 0);
        KeyCheck.forget();
        srv.close();
    }

    static Transcript transcript() {
        Transcript t = new Transcript();
        t.language = "hi";
        for (int i = 0; i < 24; i++) t.segments.add(new Transcript.Segment(i * 5, i * 5 + 5, "line number " + i + " of the talk, a full sentence here."));
        return t;
    }

    AiClient ai(List<String> notes) { return new AiClient(c, new CancelToken()).notices(notes); }

    @Test public void rejectedGeminiKey_groqPicksTheClips_andSaysSo() throws Exception {
        srv.on("POST gemini", 400, FakeAiServer.geminiKeyInvalid());
        srv.on("POST groq-chat:openai/gpt-oss-120b", 200, FakeAiServer.groqAnswer("```json\n" + VALID + "\n```"));
        List<String> notes = new ArrayList<>();
        List<AiClient.Candidate> out = ai(notes).findViralClips(PromptManager.Category.PODCAST, "Diwali test", transcript(), 1, 120);
        assertFalse("the short still gets made", out.isEmpty());
        assertEquals(10.0, out.get(0).start, 0.01);
        assertEquals("one clear line in the result", 1, notes.size());
        assertEquals("Your Gemini key isn't valid \u2014 used Groq instead. Update it in Settings for best results.", notes.get(0));
        assertTrue(KeyCheck.knownInvalid(KeyCheck.Service.GEMINI, GEM));
        // same prompt + JSON contract: the clip prompt and the schema reach Groq, with the user's Groq key
        String body = srv.bodies.stream().filter(b -> b.startsWith("POST groq-chat")).findFirst().orElse("");
        assertTrue(body.contains("viral_score") || body.contains("hook"));
        assertTrue(body.contains("\"reasoning_effort\":\"low\""));
        assertTrue(srv.auth.contains("POST groq-chat:openai/gpt-oss-120b Bearer " + GROQ));

        // the next AI step in the same job goes straight to Groq (no second Gemini call), and the line isn't repeated
        int gemCalls = srv.count("POST gemini");
        srv.on("POST groq-chat:openai/gpt-oss-120b", 200, FakeAiServer.groqAnswer("[{\"clip\":1,\"phrase\":2,\"complete\":true,\"reason\":\"ends\"}]"));
        ai(notes).refineEnds(new ArrayList<>(), transcript(), 120);   // nothing to check: no call
        String raw = ai(notes).withKeyFallback("p", 0.2, s -> s.startsWith("["), 5, 3000, () -> { throw new AssertionError("Gemini asked again"); });
        assertTrue(raw.startsWith("["));
        assertEquals(gemCalls, srv.count("POST gemini"));
        assertEquals(1, notes.size());
    }

    @Test public void rejectedKeyFromTheModelsList_alsoFallsBack() throws Exception {
        // Sakib's case can fail one step earlier: the daily model list itself answers 400 API_KEY_INVALID
        Prefs.sp(c).edit().remove("gemini_models_v4").remove("gemini_models_v4_at").commit();
        srv.on("GET /v1beta/models", 400, FakeAiServer.geminiKeyInvalid());
        srv.on("POST groq-chat:openai/gpt-oss-120b", 200, FakeAiServer.groqAnswer(VALID));
        List<String> notes = new ArrayList<>();
        assertFalse(ai(notes).findViralClips(PromptManager.Category.PODCAST, "t", transcript(), 1, 120).isEmpty());
        assertEquals(1, notes.size());
        assertEquals(0, srv.count("POST gemini"));
    }

    @Test public void groqBusyModel_handsOverToTheNextGroqModel() throws Exception {
        srv.on("POST gemini", 403, "{\"error\":{\"code\":403,\"message\":\"Permission denied: Consumer has been suspended.\"}}");
        srv.on("POST groq-chat:openai/gpt-oss-120b", 503, "{\"error\":{\"message\":\"over capacity\"}}");
        srv.on("POST groq-chat:llama-3.3-70b-versatile", 200, FakeAiServer.groqAnswer("Here you go:\n" + VALID));
        List<String> notes = new ArrayList<>();
        assertFalse(ai(notes).findViralClips(PromptManager.Category.PODCAST, "t", transcript(), 1, 120).isEmpty());
        assertEquals(1, srv.count("POST groq-chat:llama-3.3-70b-versatile"));
        assertEquals(1, notes.size());
    }

    @Test public void bothKeysRejected_failsNamingBoth() throws Exception {
        srv.on("POST gemini", 400, FakeAiServer.geminiKeyInvalid());
        srv.on("POST groq-chat:openai/gpt-oss-120b", 401, FakeAiServer.groqKeyInvalid());
        try { ai(null).findViralClips(PromptManager.Category.PODCAST, "t", transcript(), 1, 120); fail(); }
        catch (AiClient.ApiException e) {
            assertEquals("Both API keys were rejected: your Gemini key and your Groq key are not valid. Update both in Settings.", e.getMessage());
            assertTrue("the job's fatal check still stops on it", com.viral.shortsmaker.core.Pipeline.fatalAiError(e));
        }
        assertTrue(KeyCheck.knownInvalid(KeyCheck.Service.GROQ, GROQ));
    }

    @Test public void geminiRejected_groqCantAnswer_saysWhichKeyIsBad() throws Exception {
        srv.on("POST gemini", 400, FakeAiServer.geminiKeyInvalid());
        srv.on("POST groq-chat:openai/gpt-oss-120b", 503, "{\"error\":{\"message\":\"over capacity\"}}");
        srv.on("POST groq-chat:llama-3.3-70b-versatile", 503, "{\"error\":{\"message\":\"over capacity\"}}");
        try { ai(null).findViralClips(PromptManager.Category.PODCAST, "t", transcript(), 1, 120); fail(); }
        catch (AiClient.ApiException e) {
            String m = e.getMessage();
            assertTrue(m, m.startsWith("Your Gemini API key isn't valid (Google: API key not valid. Please pass a valid API key)"));
            assertTrue(m, m.endsWith("Update your Gemini key in Settings."));
            assertFalse(m, m.contains(".."));
            assertTrue(com.viral.shortsmaker.core.Pipeline.fatalAiError(e));
        }
    }

    @Test public void otherGeminiFailures_doNotTouchGroq() throws Exception {
        srv.on("POST gemini", 400, "{\"error\":{\"code\":400,\"message\":\"User location is not supported for the API use.\"}}");
        try { ai(null).findViralClips(PromptManager.Category.PODCAST, "t", transcript(), 1, 120); fail(); }
        catch (AiClient.ApiException e) { assertTrue(e.getMessage(), e.getMessage().contains("region")); }
        assertEquals(0, srv.count("POST groq-chat:openai/gpt-oss-120b"));
    }

    @Test public void endingCheck_usesGroqWhenGeminiKeyIsRejected() throws Exception {
        srv.on("POST gemini", 400, FakeAiServer.geminiKeyInvalid());
        srv.on("POST groq-chat:openai/gpt-oss-120b", 200, FakeAiServer.groqAnswer("[{\"clip\":1,\"phrase\":3,\"complete\":true,\"reason\":\"topic ends\"}]"));
        List<Project.Clip> clips = new ArrayList<>();
        Project.Clip cl = new Project.Clip();
        cl.start = 10; cl.end = 40;
        clips.add(cl);
        List<String> notes = new ArrayList<>();
        assertTrue("the AI answered the ending check (via Groq)", ai(notes).refineEnds(clips, transcript(), 120));
        assertEquals(1, srv.count("POST groq-chat:openai/gpt-oss-120b"));
        assertEquals(1, notes.size());
    }

    @Test public void groqRejectedInTranscription_isTypedAndRemembered() throws Exception {
        srv.on("POST groq-audio", 401, FakeAiServer.groqKeyInvalid());
        java.io.File f = java.io.File.createTempFile("part", ".ogg");
        java.nio.file.Files.write(f.toPath(), new byte[2048]);
        try { new AiClient(c, new CancelToken()).transcribe(f, "hi", null); fail(); }
        catch (AiClient.KeyRejected e) {
            assertEquals(KeyCheck.Service.GROQ, e.service);
            assertEquals("Groq rejected your API key (401). Update it in Settings.", e.getMessage());
        } finally { f.delete(); }
        assertTrue("later parts go to Gemini without asking Groq again", KeyCheck.knownInvalid(KeyCheck.Service.GROQ, GROQ));
        assertFalse(KeyCheck.knownInvalid(KeyCheck.Service.GEMINI, GEM));
    }

    @Test public void keyErrorText_hasNoDoubledPeriod() {
        assertEquals("Gemini rejected your API key: API key not valid. Please pass a valid API key. Update it in Settings.",
                AiClient.geminiRejected(400, "API key not valid. Please pass a valid API key.").getMessage());
        assertEquals("Gemini rejected your API key (403). Update it in Settings.", AiClient.geminiRejected(403, "").getMessage());
        assertEquals("Gemini rejected your API key: bad key. Update it in Settings.", AiClient.geminiRejected(400, "bad key").getMessage());
        assertEquals("Groq rejected your API key (401). Update it in Settings.", AiClient.groqRejected(401, "Invalid API Key").getMessage());
        // the error line shown to the user: ".." -> "." (old messages / other sources), "..." stays
        assertEquals("Please pass a valid API key. Update it in Settings.", KeyCheck.tidy("Please pass a valid API key.. Update it in Settings."));
        assertEquals("Loading... done.", KeyCheck.tidy("Loading... done."));
        assertEquals("A.", KeyCheck.sentence("A.."));
        assertEquals("", KeyCheck.sentence(" . "));
    }

    @Test public void geminiTranscriptionFallback_detectsRejectedKeyTyped() throws Exception {
        // the reverse path's Gemini side: a rejected Gemini key during the audio fallback is a KeyRejected (both-bad message upstream)
        srv.on("POST gemini", 401, "{\"error\":{\"code\":401,\"message\":\"Request had invalid authentication credentials.\"}}");
        try { ai(null).gemini("hello", false, 0.0); fail(); }
        catch (AiClient.KeyRejected e) { assertEquals(KeyCheck.Service.GEMINI, e.service); assertFalse(e.getMessage().contains("..")); }
        assertEquals("Both API keys were rejected: your Gemini key and your Groq key are not valid. Update both in Settings.",
                AiClient.bothRejected(AiClient.geminiRejected(401, "x")).getMessage());
    }
}
