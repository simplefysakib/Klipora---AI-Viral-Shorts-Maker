package com.viral.shortsmaker;

import static org.junit.Assert.*;

import android.content.Intent;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.core.*;

import org.junit.Before;
import org.junit.Test;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** v7: real-phone crash fixes (Caption Studio NPE, share), home screen on launch, hook rule, long-video shorts. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = {26, 34})
public class V7Test {
    android.content.Context ctx() { return ApplicationProvider.getApplicationContext(); }

    @Before public void reset() {
        Prefs.sp(ctx()).edit().clear().putString("gemini_key", "AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY12")
                .putString("groq_key", "gsk_DUMMYDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY1234").putBoolean("is_onboarding_done", true).apply();
        JobManager.get().removeAllListeners();
        JobManager.get().project = null;
        JobManager.get().reset();
    }

    static String res(String name) throws Exception {
        try (java.io.InputStream in = V7Test.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** a project saved before v6 (no caption language anywhere) with a real transcript next to it */
    Project oldProject() throws Exception {
        Project p = new Project();
        p.title = "Old v5 project"; p.createdAt = System.currentTimeMillis(); p.id = String.valueOf(p.createdAt);
        Project.Clip c = new Project.Clip();
        c.index = 0; c.start = 1; c.end = 9; c.score = 90;
        File f = new File(p.dir(ctx()), "short0.mp4");
        try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(new byte[2000]); }
        c.outPath = f.getAbsolutePath();
        p.clips.add(c);
        String tj = "{\"language\":\"english\",\"segments\":[{\"start\":0,\"end\":10,\"text\":\"What are the dangers of going into space?\"}],"
                + "\"words\":[{\"word\":\"What\",\"start\":1,\"end\":1.3},{\"word\":\"are\",\"start\":1.3,\"end\":1.5},{\"word\":\"the\",\"start\":1.5,\"end\":1.7},"
                + "{\"word\":\"dangers\",\"start\":1.7,\"end\":2.2},{\"word\":\"of\",\"start\":2.2,\"end\":2.3},{\"word\":\"space?\",\"start\":2.3,\"end\":3}]}";
        Project.writeText(p.transcriptFile(ctx()), tj);
        p.save(ctx());
        return p;
    }

    @Test public void captionStudioOpensWithoutLanguagePref() throws Exception {
        // v6 crash on Android 16: NullPointerException in CaptionScript.action (switch on a null language)
        Prefs.sp(ctx()).edit().remove("caption_script").apply();
        Project p = oldProject();
        JobManager.get().project = p;
        CaptionStudioActivity a = Robolectric.buildActivity(CaptionStudioActivity.class, new Intent().putExtra(CaptionStudioActivity.EXTRA_CLIP, 0)).setup().get();
        assertFalse(a.isFinishing());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    @Test public void captionScriptIsNullSafe() {
        List<com.viral.shortsmaker.captions.Word> w = new ArrayList<>();
        w.add(new com.viral.shortsmaker.captions.Word("hello", 0, 1));
        w.add(new com.viral.shortsmaker.captions.Word(null, 1, 2));
        assertEquals("keep", CaptionScript.action(w, null, null));
        assertEquals("keep", CaptionScript.action(null, "hi", CaptionScript.HINDI));
    }

    @Test public void shareWorksForShortsAndOldHistoryItems() throws Exception {
        Project p = oldProject();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Uri u = a.shareUri(new File(p.clips.get(0).outPath));       // files/projects/<id>/short0.mp4 (v6: "Failed to find configured root")
        assertNotNull(u);
        File cache = new File(ctx().getCacheDir(), "render/x.mp4");
        cache.getParentFile().mkdirs();
        try (FileOutputStream fo = new FileOutputStream(cache)) { fo.write(1); }
        assertNotNull(a.shareUri(cache));
        File ext = ctx().getExternalFilesDir(null);
        if (ext != null) { File e = new File(ext, "y.mp4"); try (FileOutputStream fo = new FileOutputStream(e)) { fo.write(1); } assertNotNull(a.shareUri(e)); }
    }

    @Test public void freshLaunchShowsEmptyCreateScreen() throws Exception {
        oldProject().makeCurrent(ctx());
        java.lang.reflect.Field f = JobManager.class.getDeclaredField("loaded");
        f.setAccessible(true);
        f.set(JobManager.get(), false);
        JobManager.get().project = null;
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNotEquals(JobManager.State.DONE, JobManager.get().state);
        assertEquals(android.view.View.VISIBLE, a.findViewById(R.id.panelInput).getVisibility());
        assertNotEquals(android.view.View.VISIBLE, a.findViewById(R.id.panelEnhance).getVisibility());
    }

    @Test public void fillerOpenersAreDetected() {
        for (String s : new String[]{"aur phir maine socha", "Toh, ye baat hai", "matlab ki", "Wahi to", "jaise maine kaha tha", "Haan to suno",
                "isliye hum", "And then he left", "So, what is radiation?", "But the thing is", "Like I said before", "और फिर", "तो हमने", "इसलिए"})
            assertTrue(s, ClipAligner.startsWithFiller(s));
        for (String s : new String[]{"What are the dangers of space?", "Paise kaise kamaye?", "Mera janam Punjab mein hua", "To be honest, nobody knows",
                "Radiation is coming from all directions", "मेरा जन्म पंजाब में हुआ", "Kya aapko pata hai"})
            assertFalse(s, ClipAligner.startsWithFiller(s));
    }

    @Test public void realHindiTranscriptNoClipStartsWithFiller() throws Exception {
        Transcript tr = Transcript.fromJson(res("v7_hindi_transcript.json"));
        List<AiClient.Candidate> cands = AiClient.parseForTest(res("v7_hindi_answer.json"), 600, tr);
        assertTrue(cands.size() >= 5);
        int fillerBefore = 0;
        for (AiClient.Candidate c : cands) {
            double[] r = ClipAligner.align(tr.segments, tr.words, c.startLine, c.endLine, c.start, c.end, 600);
            if (ClipAligner.startsWithFiller(ClipAligner.openingLine(tr.segments, tr.words, r[0], r[1]))) fillerBefore++;
        }
        assertTrue("the AI's raw picks include filler openers", fillerBefore > 0);
        List<Project.Clip> out = Pipeline.pickClips(cands, tr, 600, 5);
        assertFalse(out.isEmpty());
        for (int i = 0; i < out.size(); i++) {
            Project.Clip c = out.get(i);
            assertFalse(c.hook, ClipAligner.startsWithFiller(c.hook));
            assertFalse(c.hook.isEmpty());
            assertTrue(c.end - c.start <= 90.5);   // v18: up to 1:30 when the topic needs it (2.8 test: 60)
            if (i > 0) assertTrue(out.get(i - 1).score >= c.score);
            for (int j = 0; j < i; j++) assertTrue(Math.min(c.end, out.get(j).end) - Math.max(c.start, out.get(j).start) <= 1.0);
        }
    }

    @Test public void subScoresMakeTheTotal() throws Exception {
        String raw = "[{\"start\":10,\"end\":40,\"hook_text\":\"Kya aapko pata hai?\",\"title\":\"T\",\"hook_score\":90,\"standalone_score\":80,"
                + "\"value_score\":70,\"payoff_score\":60,\"viral_score\":99},{\"start\":50,\"end\":80,\"viral_score\":70}]";
        List<AiClient.Candidate> c = AiClient.parseForTest(raw, 100);
        assertEquals(77, c.get(0).score);     // .35*90 + .2*80 + .25*70 + .2*60
        assertEquals("Kya aapko pata hai?", c.get(0).hook);
        assertEquals(70, c.get(1).score);     // no sub-scores: the AI's total
    }

    @Test public void errorsNeverLogNull() {
        assertEquals("IOException (Canceled)", AiClient.describe(new java.io.IOException((String) null, new java.io.IOException("Canceled"))));
        assertEquals("boom", AiClient.describe(new Exception("boom")));
    }

    @Test public void transcriptCacheIsPerVideoAndLanguage() throws Exception {
        Project p = new Project(); p.sourceType = "yt"; p.url = "https://www.youtube.com/watch?v=4QWCShLwNGE";
        Pipeline pl = new Pipeline(ctx());
        File f = pl.transcriptCache(p);
        assertNotNull(f);
        assertTrue(f.getName().startsWith("yt_4QWCShLwNGE_"));
    }

    @Test public void longVideosGetMoreShorts() {
        // v8: the picked number is exact; Auto (0) gives ~1 per 6 min, max 15
        assertEquals(1, Pipeline.shortsFor(1, 600));
        assertEquals(3, Pipeline.shortsFor(3, 900));
        assertEquals(5, Pipeline.shortsFor(0, 3600));          // v9: max 5 shorts (was 10)
        assertEquals(5, Pipeline.shortsFor(0, 3 * 3600));      // v9: max 5 shorts (was 15)
    }
}
