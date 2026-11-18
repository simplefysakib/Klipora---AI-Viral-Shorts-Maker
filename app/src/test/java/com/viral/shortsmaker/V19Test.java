package com.viral.shortsmaker;

import static org.junit.Assert.*;

import com.viral.shortsmaker.captions.Word;
import com.viral.shortsmaker.core.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** v19 (3.0): fixes from Sakib's first real 2.9 run (17 s short, fake sync [ERROR], 720p crop, no auto split screen). */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class V19Test {

    // ------------------------------------------------------------------ B. sync tolerance

    @Test public void fortyMsAtMatch099IsInSync() {
        // his log: "rendered audio -0.04 s vs transcribed (match 0.99)" -> 2.9 called it [ERROR] and re-rendered
        assertFalse(Pipeline.syncNeedsRetime(-0.04, 0.99));
        assertFalse(Pipeline.syncNeedsRetime(+0.10, 0.95));
        assertFalse(Pipeline.syncNeedsRetime(Pipeline.AAC_PRIMING_SEC + 0.11, 0.92));
    }

    @Test public void reallyOffAudioIsStillRetimed() {
        assertTrue(Pipeline.syncNeedsRetime(-0.25, 0.97));
        assertTrue(Pipeline.syncNeedsRetime(0.30, 0.9));
        assertTrue(Pipeline.syncNeedsRetime(0.12, 0.75));    // weak match: tighter tolerance
        assertFalse(Pipeline.syncNeedsRetime(0.12, 0.95));
        assertFalse(Pipeline.syncNeedsRetime(0.5, 0.4));      // measurement not trusted: no re-render on a guess
        assertFalse(Pipeline.syncNeedsRetime(Double.NaN, 0.99));
    }

    // ------------------------------------------------------------------ A. clip length / story endings

    /** unpunctuated words of 0.3 s with 0.05 s gaps; one clear pause (1.2 s) after the word ending near pauseAt */
    static Transcript flat(double total, double pauseAt) {
        Transcript t = new Transcript();
        t.language = "hi";
        double at = 0; boolean paused = false;
        int i = 0;
        double segStart = 0; StringBuilder seg = new StringBuilder();
        while (at < total) {
            String w = "shabd" + (i++ % 7);
            t.words.add(new Word(w, at, at + 0.3));
            seg.append(w).append(' ');
            at += 0.35;
            if (!paused && at >= pauseAt) {
                paused = true;
                t.segments.add(new Transcript.Segment(segStart, at - 0.05, seg.toString().trim()));
                seg = new StringBuilder();
                at += 1.2;
                segStart = at;
            }
        }
        t.segments.add(new Transcript.Segment(segStart, at - 0.05, seg.toString().trim()));
        return t;
    }

    @Test public void unpunctuatedTopicPastTheLimitIsNotCutAt17s() {
        // the 2.9 bug: topic runs past 1:30, the only strong end is at 17 s -> 2.9 made a 17 s short
        Transcript t = flat(200, 17);
        double e = ClipAligner.endFor(t.segments, t.words, 0, 140, 300, ClipAligner.POD_MIN_LEN, false);
        assertTrue("v19 end " + e, e >= ClipAligner.POD_MIN_LEN && e <= ClipAligner.MAX_LEN + 0.01);
        assertTrue("v19 prefers a 45-90 s short here: " + e, e >= ClipAligner.TARGET_LEN);
    }

    @Test public void shortAiRangeIsExtendedToAStory() {
        String[] ss = new String[30];
        for (int i = 0; i < 30; i++) ss[i] = "yeh kahani ka hissa number " + i + " hai bhai";
        Transcript t = V18Test.tr(ss, 0.6, true);
        // the AI marked 17 s and did not say the story ends there
        double e = ClipAligner.endFor(t.segments, t.words, 0, 17, 300, ClipAligner.POD_MIN_LEN, false);
        assertTrue("end " + e, e >= ClipAligner.POD_MIN_LEN - 0.01 && e <= 55);
        assertTrue("on a sentence end", ClipAligner.endsOnThought(t.segments, t.words, e));
    }

    @Test public void storyThatReallyEndsEarlyMayStayShorter() {
        String[] ss = new String[30];
        for (int i = 0; i < 30; i++) ss[i] = "yeh kahani ka hissa number " + i + " hai bhai";
        Transcript t = V18Test.tr(ss, 0.6, true);
        double e = ClipAligner.endFor(t.segments, t.words, 0, 31, 300, ClipAligner.POD_MIN_LEN, true);
        assertTrue("end " + e, e >= 30 - 0.01 && e < ClipAligner.POD_MIN_LEN + 6);
    }

    @Test public void endsNeverPassTheLimit() {
        Transcript t = flat(400, 300);
        for (double want : new double[]{10, 40, 80, 95, 200}) {
            double e = ClipAligner.endFor(t.segments, t.words, 5, 5 + want, 400, ClipAligner.POD_MIN_LEN, false);
            assertTrue(want + " -> " + e, e - 5 <= ClipAligner.MAX_LEN + 0.01 && e - 5 >= ClipAligner.POD_MIN_LEN - 0.01);
        }
    }

    @Test public void podcastPromptAsksForWholeStoriesOf45to90s() {
        String p = PromptManager.buildViralPrompt(PromptManager.Category.PODCAST, "t", "0 [0-5] hello", 5, 900);
        assertTrue(p.contains("45-90 seconds"));
        assertTrue(p.contains("BIG TOPICS AND FULL STORIES"));
        assertTrue(p.contains("move start_line BACK"));
        assertFalse(p.contains("Length 20-60 seconds normally"));
        String g = PromptManager.buildViralPrompt(PromptManager.Category.GAMING, "t", "0 [0-5] hello", 5, 900);
        assertTrue(g.contains("45-90 seconds when the moment carries it"));
    }

    // ------------------------------------------------------------------ C. source for the 9:16 crop

    static JSONObject fmt(String id, int w, int h, String vc, String ac, double fps) throws Exception {
        return new JSONObject().put("format_id", id).put("url", "https://r.googlevideo.com/" + id).put("protocol", "https")
                .put("width", w).put("height", h).put("vcodec", vc).put("acodec", ac).put("fps", fps).put("ext", vc.startsWith("vp9") ? "webm" : "mp4").put("abr", ac.equals("none") ? 0 : 128);
    }

    @Test public void cropPrefers1080pOver720p() throws Exception {
        JSONArray fs = new JSONArray()
                .put(fmt("134", 640, 360, "avc1.4d401e", "none", 30))
                .put(fmt("136", 1280, 720, "avc1.4d401f", "none", 30))
                .put(fmt("137", 1920, 1080, "avc1.640028", "none", 30))
                .put(fmt("248", 1920, 1080, "vp9", "none", 30))
                .put(fmt("140", 0, 0, "none", "mp4a.40.2", 0));
        JSONObject info = new JSONObject().put("formats", fs);
        String[] s = YtEngine.pickStreams(info, 1080);
        assertTrue(s[3], s[3].contains("itag 137"));
        assertEquals(1080, Pipeline.heightOf(s));
        assertEquals(1080, AudioRace.bestVideoHeight(info, 1080));
        assertEquals(720, AudioRace.bestVideoHeight(info, 720));
        // a list that tops out at 720p (his 2.9 log) is what triggers the sharper-list lookup
        JSONObject only720 = new JSONObject().put("formats", new JSONArray().put(fmt("136", 1280, 720, "avc1.4d401f", "none", 30)).put(fmt("140", 0, 0, "none", "mp4a.40.2", 0)));
        assertEquals(720, Pipeline.heightOf(YtEngine.pickStreams(only720, 1080)));
    }

    @Test public void croppedShortsFetch1080pEvenFor720pExport() {
        Project pr = new Project();
        pr.aspect = "PORTRAIT"; pr.duration = 3000;
        assertTrue(Pipeline.crops(pr));
        assertEquals(1080, Pipeline.sourceMaxHeight(pr, 0));   // auto (1080p locked -> 720p export)
        assertEquals(1080, Pipeline.sourceMaxHeight(pr, 2));   // 720p chip
        assertEquals(480, Pipeline.sourceMaxHeight(pr, 3));    // data saver keeps its cap
        pr.aspect = "LANDSCAPE";
        assertFalse(Pipeline.crops(pr));
        assertEquals(720, Pipeline.sourceMaxHeight(pr, 2));
    }

    // ------------------------------------------------------------------ D. automatic split screen

    static final double SF = 5;

    interface Talk { int who(double t); }   // 0 = A, 1 = B, 2 = both, -1 = nobody

    static List<FaceTrack.Sample> dialog(double sec, Talk talk, long seed) {
        Random r = new Random(seed);
        List<FaceTrack.Sample> s = new ArrayList<>();
        int n = (int) Math.round(sec * SF);
        for (int i = 0; i < n; i++) {
            double t = i / SF;
            int w = talk.who(t);
            boolean a = w == 0 || w == 2, b = w == 1 || w == 2;
            s.add(new FaceTrack.Sample(V13FaceTrackTest.list(V13FaceTrackTest.face(0.18, 0.42, 0.22, a, i, r), V13FaceTrackTest.face(0.82, 0.42, 0.22, b, i, r)),
                    w < 0 ? 0.001f : 0.1f, 2f + r.nextFloat()));
        }
        return s;
    }

    static FaceTrack.Plan plan(List<FaceTrack.Sample> s, double sec) { return FaceTrack.plan(s, SF, 1920, 1080, sec, 30); }

    @Test public void backAndForthInsideALongShotSplitsOnlyThere() {
        // A talks 0-15 s, quick back-and-forth every 1.5 s from 15 to 27 s, B talks 27-45 s (one long wide shot)
        List<FaceTrack.Sample> s = dialog(45, t -> t < 15 ? 0 : t < 27 ? (((int) ((t - 15) / 1.5)) % 2 == 0 ? 1 : 0) : 1, 11);
        FaceTrack.Plan p = plan(s, 45);
        assertTrue(p.usable);
        assertEquals("splits " + p.summary(), 1, p.splits.size());
        FaceTrack.Split sp = p.splits.get(0);
        assertTrue("start " + sp.start, sp.start > 11 && sp.start < 18);
        assertTrue("end " + sp.end, sp.end > 24 && sp.end < 31);
        assertTrue(sp.topCx < sp.botCx);   // left person on top
    }

    @Test public void monologueNeverSplits() {
        List<FaceTrack.Sample> s = dialog(40, t -> 0, 12);
        FaceTrack.Plan p = plan(s, 40);
        assertTrue(p.splits.isEmpty());
        // with short "haan"s from the listener it still does not split
        List<FaceTrack.Sample> s2 = dialog(40, t -> (t % 9) > 4.0 && (t % 9) < 4.4 ? 1 : 0, 13);
        assertTrue(plan(s2, 40).splits.isEmpty());
    }

    @Test public void bothTalkingTogetherSplits() {
        // laughing / talking over each other 12-20 s inside an A monologue
        List<FaceTrack.Sample> s = dialog(36, t -> t >= 12 && t < 20 ? 2 : 0, 14);
        FaceTrack.Plan p = plan(s, 36);
        assertEquals(1, p.splits.size());
        FaceTrack.Split sp = p.splits.get(0);
        assertTrue(sp.start + " - " + sp.end, sp.start > 8 && sp.start < 15 && sp.end > 17 && sp.end < 24);
    }

    @Test public void splitScreenDoesNotFlicker() {
        // irregular exchange 5-35 s (turns of 0.8-2.4 s): one steady split, no part shorter than 2.5 s
        Random r = new Random(5);
        double[] edges = new double[40];
        double at = 5; for (int i = 0; i < 40; i++) { edges[i] = at; at += 0.8 + 1.6 * r.nextDouble(); }
        List<FaceTrack.Sample> s = dialog(45, t -> {
            if (t < 5 || t >= 35) return 0;
            int k = 0; while (k + 1 < edges.length && edges[k + 1] <= t) k++;
            return k % 2 == 0 ? 1 : 0;
        }, 15);
        FaceTrack.Plan p = plan(s, 45);
        assertTrue("splits " + p.splits.size(), p.splits.size() >= 1 && p.splits.size() <= 2);
        double total = 0;
        for (FaceTrack.Split sp : p.splits) { assertTrue(sp.end - sp.start >= 2.5); total += sp.end - sp.start; }
        assertTrue("covered " + total, total > 20);
    }
}
