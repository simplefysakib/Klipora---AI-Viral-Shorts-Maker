package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.captions.Word;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** code 32 (5.0) overnight audit, second part: pipeline (A), lifecycle / monetisation (C). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V32bTest {
    static final long H = 3600_000L, MIN = 60_000L;
    long wall = 1_800_000_000_000L, up = 50 * H;
    Context c;

    @Before public void clocks() {
        c = ApplicationProvider.getApplicationContext();
        Prefs.wall = () -> wall;
        Prefs.elapsed = () -> up;
        Prefs.sp(c).edit().clear().putBoolean("migrated_v18", true).commit();
        android.provider.Settings.Global.putInt(c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, 7);
    }
    @After public void restore() { Prefs.wall = System::currentTimeMillis; Prefs.elapsed = android.os.SystemClock::elapsedRealtime; }

    // ---------------------------------------------------------------- C2: timed unlocks can't be stretched by the clock
    @Test public void clockSetBackThreeDaysDoesNotStretchTheHdHour() {
        Prefs.unlockHd(c);
        assertEquals(H, Prefs.hdLeftMs(c));
        wall -= 3 * 24 * H;                    // clock moved back 3 days (4.9: the hour stayed full for 3 days)
        up += 30 * MIN; assertEquals(30 * MIN, Prefs.hdLeftMs(c));
        up += 31 * MIN; assertEquals(0, Prefs.hdLeftMs(c));
    }

    @Test public void clockSetBackHoursDoesNotFreezeThePass() {
        SharedPreferences.Editor e = Prefs.sp(c).edit(); Prefs.stampGrant(c, e, "premium_unlock_time"); e.commit();
        assertEquals(24 * H, Prefs.premiumLeftMs(c));
        wall -= 23 * H;                         // 4.9: the timer stood still for 23 real hours
        up += 25 * H;
        assertEquals(0, Prefs.premiumLeftMs(c));   // (isPremium is always true in the premium edition)
    }

    @Test public void clockForwardDoesNotEatAnUnlockWithinOneBoot() {
        Prefs.unlockHd(c);
        wall += 5 * H; up += 10 * MIN;           // network time fixed a wrong clock
        assertEquals(50 * MIN, Prefs.hdLeftMs(c));
    }

    @Test public void afterARebootTheWallClockCountsAndTimeNeverRunsBack() {
        Prefs.unlockHd(c);
        up += 10 * MIN; wall += 10 * MIN;
        assertEquals(50 * MIN, Prefs.hdLeftMs(c));
        // phone off 20 min, reboot (uptime restarts, boot number +1)
        android.provider.Settings.Global.putInt(c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, 8);
        up = 2 * MIN; wall += 22 * MIN;
        assertEquals(28 * MIN, Prefs.hdLeftMs(c));
        wall -= 5 * 24 * H;                       // clock back after the reboot: uptime counts again from here
        up += 8 * MIN;
        assertEquals(20 * MIN, Prefs.hdLeftMs(c));
    }

    @Test public void grantsFromOlderBuildsStillCount() {
        // 4.9 wrote only the wall time
        Prefs.sp(c).edit().putLong("high_speed_unlock_time", wall - 20 * MIN).commit();
        assertEquals(40 * MIN, Prefs.highSpeedLeftMs(c));
        up += 40 * MIN; wall -= 24 * H;
        assertEquals(0, Prefs.highSpeedLeftMs(c));
    }

    @Test public void paidWindowFollowsTheSameClock() {
        Prefs.markPaid(c, Prefs.F_FACE, "yt_abc");
        assertTrue(Prefs.paidRecently(c, Prefs.F_FACE, "yt_abc"));
        wall -= 2 * 24 * H; up += 6 * H + MIN;
        assertFalse(Prefs.paidRecently(c, Prefs.F_FACE, "yt_abc"));
    }

    // ---------------------------------------------------------------- A3: vertical videos at 1080p
    static JSONObject fmt(String id, int w, int h, String vc, String ac) throws Exception {
        return new JSONObject().put("format_id", id).put("url", "https://x/" + id).put("protocol", "https").put("ext", "mp4")
                .put("width", w).put("height", h).put("vcodec", vc).put("acodec", ac).put("fps", 30).put("abr", 128);
    }

    @Test public void shortsLinkPicks1080x1920() throws Exception {
        JSONArray fs = new JSONArray().put(fmt("137v", 1080, 1920, "avc1.640028", "none")).put(fmt("136v", 720, 1280, "avc1.4d401f", "none"))
                .put(fmt("135v", 480, 854, "avc1.4d401f", "none")).put(fmt("140", 0, 0, "none", "mp4a.40.2"))
                .put(fmt("2160v", 2160, 3840, "vp09.00.50.08", "none"));
        String[] s = YtEngine.pickStreams(new JSONObject().put("formats", fs), 1080);
        assertNotNull(s);
        assertTrue(s[3], s[3].startsWith("1080x1920"));
        assertEquals(1080, Pipeline.heightOf(s));
        assertEquals(720, YtEngine.pOf(fmt("a", 720, 1280, "avc1", "none")));
        assertEquals(720, YtEngine.pOf(fmt("b", 1280, 720, "avc1", "none")));
        String[] s7 = YtEngine.pickStreams(new JSONObject().put("formats", fs), 720);
        assertTrue(s7[3], s7[3].startsWith("720x1280"));
    }

    @Test public void ytDlpFormatMeasuresVerticalByWidth() {
        String f = YtEngine.videoFormat(1080);
        assertTrue(f, f.startsWith("bv*[aspect_ratio<1][width<=1080][vcodec^=avc1]/bv*[height<=1080][vcodec^=avc1]/"));
        assertTrue(f.endsWith("/b[height<=1080]/b"));
        assertFalse(f.contains("res<="));
    }

    // ---------------------------------------------------------------- A2 / A11 / A12: messages
    @Test public void ageRestrictedWinsOverSignIn() {
        String m = YtEngine.friendlyYtError(new Exception("ERROR: Sign in to confirm your age. This video may be inappropriate for some users."));
        assertTrue(m, m.toLowerCase(java.util.Locale.ROOT).contains("age"));
    }

    @Test public void liveStreamsAreRecognised() throws Exception {
        assertTrue(YtEngine.isLive(new JSONObject().put("is_live", true)));
        assertTrue(YtEngine.isLive(new JSONObject().put("live_status", "is_upcoming")));
        assertFalse(YtEngine.isLive(new JSONObject().put("live_status", "was_live")));
        assertFalse(YtEngine.isLive(null));
    }

    @Test public void noSpaceFoundInFfmpegLog() {
        assertTrue(Pipeline.isNoSpace(new MediaRunner.Failed("Video command failed", "...\n[out#0] Error writing trailer: No space left on device\n")));
        assertTrue(Pipeline.isNoSpace(new Exception("cut", new java.io.IOException("write failed: ENOSPC (No space left on device)"))));
        assertFalse(Pipeline.isNoSpace(new MediaRunner.Failed("Video command failed", "Invalid data found")));
    }

    // ---------------------------------------------------------------- A4 / A6 / A7: languages
    @Test public void scriptsDecideTheSpeechLanguage() {
        List<String> en = Arrays.asList("en", "en", "en");
        assertEquals("hi", Pipeline.decideSpeechLanguage("", "\u06a9\u06cc\u0627 \u06c1\u06d2 \u06cc\u06c1 \u0628\u0627\u062a", "", Arrays.asList("ur", "ur")));   // Urdu letters
        assertEquals("fa", Pipeline.decideSpeechLanguage("", "\u0627\u06cc\u0646 \u0641\u06cc\u0644\u0645 \u062e\u0648\u0628 \u0627\u0633\u062a", "", Arrays.asList("fa", "fa", "en")));
        assertEquals("ar", Pipeline.decideSpeechLanguage("", "\u0645\u0631\u062d\u0628\u0627 \u0628\u0643\u0645 \u062c\u0645\u064a\u0639\u0627", "", en));
        assertEquals("uk", Pipeline.decideSpeechLanguage("", "\u041f\u0440\u0438\u0432\u0456\u0442 \u0434\u0440\u0443\u0437\u0456", "", Arrays.asList("uk", "uk")));
        assertEquals("ja", Pipeline.decideSpeechLanguage("", "\u65e5\u672c\u306e\u6620\u753b", "", en));   // kanji + kana
        assertEquals("bn", Pipeline.decideSpeechLanguage("", "\u0986\u09ae\u09be\u09b0 \u0997\u09be\u09a8", "", en));
        // Odia: not a speech-model language - never forced (it was a 400 from the speech service)
        String or = Pipeline.decideSpeechLanguage("", "\u0b13\u0b21\u0b3c\u0b3f\u0b06 \u0b17\u0b40\u0b24", "", Arrays.asList("hi", "hi"));
        assertNotEquals("or", or);
        assertEquals("hi", or);
    }

    @Test public void languageNamesAndCodes() {
        assertEquals("pt", Transcript.languageCode("pt-BR"));
        assertEquals("pt", Transcript.languageCode("Portuguese"));
        assertEquals("haw", Transcript.languageCode("hawaiian"));
        assertEquals("yue", Transcript.languageCode("cantonese"));
        assertEquals("zh", Transcript.languageCode("zh-Hans"));
        assertTrue(Transcript.whisperKnows("bn"));
        assertFalse(Transcript.whisperKnows("or"));
        assertEquals("", Pipeline.whisperOnly("or"));
        assertEquals("ta", Pipeline.whisperOnly("ta"));
    }

    @Test public void playerResponseCarriesMetadataAndLanguage() throws Exception {
        JSONObject a1 = new JSONObject().put("itag", 140).put("url", "https://x/a").put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                .put("bitrate", 130000).put("audioTrack", new JSONObject().put("id", "bn.4").put("displayName", "Bengali original").put("audioIsDefault", true));
        JSONObject pl = new JSONObject()
                .put("playabilityStatus", new JSONObject().put("status", "OK"))
                .put("streamingData", new JSONObject().put("adaptiveFormats", new JSONArray().put(a1)))
                .put("videoDetails", new JSONObject().put("videoId", "abc").put("title", "Gaan").put("lengthSeconds", "200")
                        .put("shortDescription", "desc here").put("author", "Chan").put("keywords", new JSONArray().put("bangla")).put("isLive", false))
                .put("microformat", new JSONObject().put("playerMicroformatRenderer", new JSONObject().put("category", "Music")));
        JSONObject info = AudioRace.fromPlayer(pl, "ua");
        assertEquals("desc here", info.optString("description"));
        assertEquals("Chan", info.optString("channel"));
        assertEquals("bangla", info.optJSONArray("tags").optString(0));
        assertEquals("Music", info.optJSONArray("categories").optString(0));
        assertEquals("bn", info.optString("language"));
    }

    @Test public void audioLanguagePrefersOriginal() throws Exception {
        JSONArray f = new JSONArray()
                .put(new JSONObject().put("language", "en").put("language_preference", -1))
                .put(new JSONObject().put("language", "hi").put("language_preference", 5))
                .put(new JSONObject().put("language", "ta").put("language_preference", 10));
        assertEquals("ta", AudioRace.audioLanguage(f));
        f.remove(2);
        assertEquals("hi", AudioRace.audioLanguage(f));
    }

    // ---------------------------------------------------------------- A8: token cache
    @Test public void tokensAreBuiltOncePerTranscript() {
        List<Transcript.Segment> segs = new ArrayList<>();
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < 200; i++) { segs.add(new Transcript.Segment(i * 2, i * 2 + 2, "hello there. friend")); words.add(new Word("hello", i * 2, i * 2 + 0.6)); words.add(new Word("there.", i * 2 + 0.7, i * 2 + 1.2)); words.add(new Word("friend", i * 2 + 1.3, i * 2 + 1.9)); }
        List<ClipAligner.Tok> a = ClipAligner.tokens(segs, words), b = ClipAligner.tokens(segs, words);
        assertSame(a, b);
        try { a.clear(); fail("shared tokens must be read-only"); } catch (UnsupportedOperationException expected) {}
        words.set(5, new Word("changed", words.get(5).start, words.get(5).end));
        assertNotSame(a, ClipAligner.tokens(segs, words));
    }

    // ---------------------------------------------------------------- A10 / A13: projects on disk
    @Test public void continueKeepsUnfinishedMoments() throws Exception {
        File d = new File(Project.root(c), "p_unf"); d.mkdirs();
        File out = new File(d, "s1.mp4"); try (FileOutputStream o = new FileOutputStream(out)) { o.write(1); }
        JSONObject j = new JSONObject().put("title", "T").put("clips", new JSONArray()
                .put(new JSONObject().put("index", 1).put("start", 0).put("end", 30).put("outPath", out.getPath()))
                .put(new JSONObject().put("index", 2).put("start", 40).put("end", 70))
                .put(new JSONObject().put("index", 3).put("start", 80).put("end", 110)));
        try (FileOutputStream o = new FileOutputStream(new File(d, "project.json"))) { o.write(j.toString().getBytes("UTF-8")); }
        Project p = Project.loadFrom(d);
        assertEquals(1, p.clips.size());
        assertEquals(2, p.unfinished.size());
        JSONArray back = p.toJson().getJSONArray("clips");
        assertEquals(3, back.length());
        assertEquals(1, back.getJSONObject(0).getInt("index"));
        assertEquals(3, back.getJSONObject(2).getInt("index"));
    }

    @Test public void orphanFoldersOfFailedJobsAreRemoved() throws Exception {
        File root = Project.root(c);
        File done = new File(root, "o_done"), failed = new File(root, "o_failed"), noJson = new File(root, "o_nojson"),
                fresh = new File(root, "o_fresh"), keep = new File(root, "o_keep");
        for (File d : new File[]{done, failed, noJson, fresh, keep}) d.mkdirs();
        File out = new File(done, "s1.mp4"); try (FileOutputStream o = new FileOutputStream(out)) { o.write(1); }
        String withShort = new JSONObject().put("clips", new JSONArray().put(new JSONObject().put("index", 1).put("outPath", out.getPath()))).toString();
        String noShort = new JSONObject().put("clips", new JSONArray().put(new JSONObject().put("index", 1))).toString();
        write(new File(done, "project.json"), withShort);
        write(new File(failed, "project.json"), noShort); write(new File(failed, "audio.m4a"), "big");
        write(new File(noJson, "seg_0.m4a"), "x");
        write(new File(fresh, "project.json"), noShort);
        write(new File(keep, "project.json"), noShort);
        long now = System.currentTimeMillis();
        for (File d : new File[]{done, failed, noJson, keep}) age(d, now - 3 * H);
        int n = Project.dropOrphans(c, new HashSet<>(Arrays.asList("o_keep")), now);
        assertEquals(2, n);
        assertTrue(done.exists()); assertTrue(fresh.exists()); assertTrue(keep.exists());
        assertFalse(failed.exists()); assertFalse(noJson.exists());
    }

    static void write(File f, String s) throws Exception { try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); } }
    static void age(File d, long t) { File[] fs = d.listFiles(); if (fs != null) for (File f : fs) f.setLastModified(t); d.setLastModified(t); }

    // ---------------------------------------------------------------- A9: cancel aborts uploads
    @Test public void cancelAbortsRegisteredCalls() {
        CancelToken ct = new CancelToken();
        okhttp3.Call call = new okhttp3.OkHttpClient().newCall(new okhttp3.Request.Builder().url("https://example.invalid/").build());
        ct.calls.add(call);
        ct.cancelCalls();
        assertTrue(call.isCanceled());
    }

    // ---------------------------------------------------------------- C6: the stopped job's log survives background starts
    @Test public void stoppedJobLogSurvivesExtraProcessStarts() throws Exception {
        File d = new File(System.getProperty("java.io.tmpdir"), "v32log" + System.nanoTime()); d.mkdirs();
        write(new File(d, "session.log"), "JOB LOG: rendering short 2");
        AppLog.startSession(d, true);                       // background start after the kill
        write(new File(d, "session.log"), "Video engine ready");
        AppLog.startSession(d, true);                       // the user opens the app
        assertTrue(AppLog.previousSession(d, 50).contains("JOB LOG"));
        AppLog.dropJobLog(d);
        assertFalse(AppLog.previousSession(d, 50).contains("JOB LOG"));
    }

    // ---------------------------------------------------------------- C10: the report fits an intent
    @Test public void reportIsCapped() {
        StringBuilder b = new StringBuilder("HEAD\n");
        for (int i = 0; i < 20000; i++) b.append("line ").append(i).append(" some encoder text here\n");
        b.append("TAIL");
        String r = AppLog.capReport(b.toString(), 40_000);
        assertTrue(r.length() <= 40_000);
        assertTrue(r.startsWith("HEAD"));
        assertTrue(r.endsWith("TAIL"));
        assertEquals("short", AppLog.capReport("short", 40_000));
    }
}
