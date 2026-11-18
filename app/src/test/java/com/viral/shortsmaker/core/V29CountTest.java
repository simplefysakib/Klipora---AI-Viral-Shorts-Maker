package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.PromptManager;
import com.viral.shortsmaker.captions.Word;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * code 29 (4.7): Sakib picked 3 / 5 shorts and got 1. Root cause: the clip picker drops every AI moment that overlaps a
 * better one and nothing replaced them, so bunched moments (a transcript with gaps, podcast stories stretched to 45-90 s,
 * a short video) gave fewer shorts than asked, silently. These tests pin: the shortfall on the old path, the top-up to the
 * exact count (N = 1, 3, 5), no overlaps, the second AI look's line mapping, and the real render scheduling of 4.6
 * (every short prepares at once, the heavy part takes one of par slots) producing N saved files that the project and
 * the results screen list.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V29CountTest {
    static Transcript TR;
    static final double DUR = 900.92;
    static File TINY;

    @BeforeClass public static void load() throws Exception {
        try (InputStream in = V29CountTest.class.getClassLoader().getResourceAsStream("k29/en_transcript.json")) {
            TR = Transcript.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertTrue(TR.segments.size() > 50);
        // a real tiny H.264 mp4 from the box's ffmpeg when there is one (else a stand-in file of the same size class)
        TINY = File.createTempFile("k29tiny", ".mp4");
        boolean made = false;
        try {
            Process pr = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=s=180x320:r=30:d=2",
                    "-f", "lavfi", "-i", "sine=f=440:d=2", "-c:v", "libx264", "-b:v", "600k", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
                    TINY.getAbsolutePath()).redirectErrorStream(true).start();
            pr.getInputStream().readAllBytes();
            made = pr.waitFor() == 0 && TINY.length() > 20000;
        } catch (Exception ignored) {}
        if (!made) { byte[] b = new byte[60000]; new java.util.Random(1).nextBytes(b); Files.write(TINY.toPath(), b); }
    }

    @After public void clear() { Pipeline.renderHookForTest = null; Pipeline.parForTest = 0; }

    /** AI moments close together: each overlaps the next one (what his runs got) */
    static List<AiClient.Candidate> bunched(int n, double from, double step, double len) {
        List<AiClient.Candidate> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            AiClient.Candidate c = new AiClient.Candidate();
            c.start = from + i * step; c.end = c.start + len; c.score = 90 - i;
            c.hookScore = 80; c.standaloneScore = 80; c.valueScore = 80; c.payoffScore = 80;
            c.title = "moment " + i;
            l.add(c);
        }
        return l;
    }

    static void assertClean(List<Project.Clip> l, double dur) {
        for (int i = 0; i < l.size(); i++) {
            Project.Clip a = l.get(i);
            assertTrue("inside the video", a.start >= 0 && a.end <= dur + 0.01);
            assertTrue("long enough: " + (a.end - a.start), a.end - a.start >= Pipeline.TOPUP_MIN_LEN - 0.01);
            assertTrue("not too long", a.end - a.start <= ClipAligner.MAX_LEN + 0.01);
            for (int j = i + 1; j < l.size(); j++) {
                Project.Clip b = l.get(j);
                assertTrue("overlap " + a.start + "-" + a.end + " / " + b.start + "-" + b.end, Math.min(a.end, b.end) - Math.max(a.start, b.start) <= 1.0);
            }
        }
    }

    @Test public void oldPathMakesFewerThanAskedAndTopUpMakesExactlyN() {
        for (boolean podcast : new boolean[]{false, true}) {
            PromptManager.Category cat = podcast ? PromptManager.Category.PODCAST : PromptManager.Category.OTHER;
            for (int want : new int[]{1, 3, 5}) {
                List<Project.Clip> left = new ArrayList<>();
                List<Project.Clip> picked = Pipeline.pickClips(bunched(want + 3, 120, 10, 60), TR, DUR, want, cat, left);
                if (want > 1) assertTrue("the 4.6 picker came up short (" + picked.size() + " of " + want + ")", picked.size() < want);
                List<Project.Clip> all = Pipeline.topUpClips(picked, left, TR, DUR, want, podcast);
                assertEquals("podcast=" + podcast + " want=" + want, want, all.size());
                assertClean(all, DUR);
                for (Project.Clip c : all) assertFalse(c.hook == null);
            }
        }
    }

    @Test public void realAnswerStillGivesExactCount() throws Exception {
        String raw;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("k29/en_answer.txt")) { raw = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        for (int want : new int[]{1, 3, 5}) {
            List<AiClient.Candidate> c = AiClient.parseCandidatesForTest(raw, DUR);
            List<Project.Clip> left = new ArrayList<>();
            List<Project.Clip> picked = Pipeline.pickClips(c, TR, DUR, want, PromptManager.Category.PODCAST, left);
            List<Project.Clip> all = Pipeline.topUpClips(picked, left, TR, DUR, want, true);
            assertEquals(want, all.size());
            // a good AI answer is used as it is (nothing replaced)
            for (int i = 0; i < picked.size(); i++) assertSame(picked.get(i), all.get(i));
            assertClean(all, DUR);
        }
    }


    /** a transcript without punctuation (a dubbed track, Whisper without punctuation): every AI moment "opens mid-thought" */
    static Transcript unpunctuated(Transcript src) {
        Transcript t = new Transcript();
        t.language = src.language;
        for (Transcript.Segment s : src.segments) t.segments.add(new Transcript.Segment(s.start, s.end, s.text.replaceAll("[.?!,;:\u0964\u2026]", "").toLowerCase(java.util.Locale.US)));
        for (Word w : src.words) t.words.add(new Word(w.text.replaceAll("[.?!,;:\u0964\u2026]", "").toLowerCase(java.util.Locale.US), w.start, w.end));
        return t;
    }

    @Test public void unpunctuatedTranscriptGaveExactlyOneShort() throws Exception {
        String raw;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("k29/en_answer.txt")) { raw = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        Transcript t = unpunctuated(TR);
        for (int want : new int[]{1, 3, 5}) {
            List<AiClient.Candidate> c = AiClient.parseCandidatesForTest(raw, DUR);
            List<Project.Clip> left = new ArrayList<>();
            List<Project.Clip> picked = Pipeline.pickClips(c, t, DUR, want, PromptManager.Category.OTHER, left);
            System.out.println("K29 unpunctuated want=" + want + " old picker=" + picked.size() + " leftovers=" + left.size());
            List<Project.Clip> all = Pipeline.topUpClips(picked, left, t, DUR, want, false);
            assertEquals(want, all.size());
            assertClean(all, DUR);
        }
    }

    @Test public void transcriptWithGapsStillGivesN() {
        // the speech service's hourly limit left only the first 3 minutes transcribed
        Transcript t = new Transcript();
        t.language = TR.language;
        for (Transcript.Segment s : TR.segments) if (s.end <= 180) t.segments.add(s);
        for (Word w : TR.words) if (w.end <= 180) t.words.add(w);
        for (int want : new int[]{3, 5}) {
            List<Project.Clip> left = new ArrayList<>();
            List<Project.Clip> picked = Pipeline.pickClips(bunched(6, 20, 12, 55), t, DUR, want, PromptManager.Category.OTHER, left);
            assertTrue(picked.size() < want);
            List<Project.Clip> all = Pipeline.topUpClips(picked, left, t, DUR, want, false);
            assertEquals(want, all.size());
            assertClean(all, DUR);
        }
    }

    @Test public void shortVideoNeverOverlapsOrOverflows() {
        Transcript t = new Transcript();
        for (Transcript.Segment s : TR.segments) if (s.end <= 70) t.segments.add(s);
        for (Word w : TR.words) if (w.end <= 70) t.words.add(w);
        List<Project.Clip> left = new ArrayList<>();
        List<Project.Clip> picked = Pipeline.pickClips(bunched(4, 0, 5, 40), t, 70, 5, PromptManager.Category.OTHER, left);
        List<Project.Clip> all = Pipeline.topUpClips(picked, left, t, 70, 5, false);
        assertTrue(all.size() >= 1 && all.size() <= 4);
        assertClean(all, 70);
    }

    @Test public void secondLookMapsLinesBackToTheWholeTranscript() {
        List<double[]> taken = new ArrayList<>();
        taken.add(new double[]{100, 300});
        Transcript sub = new Transcript();
        List<Integer> map = AiClient.outsideLines(TR, taken, sub);
        assertEquals(sub.segments.size(), map.size());
        for (int i = 0; i < map.size(); i++) assertSame(TR.segments.get(map.get(i)), sub.segments.get(i));
        for (Transcript.Segment s : sub.segments) assertFalse(s.start >= 100 && s.end <= 300);
        for (Word w : sub.words) assertFalse(w.start >= 100 && w.end <= 300);
        AiClient.Candidate c = new AiClient.Candidate();
        int k = map.size() - 2;
        c.startLine = k; c.endLine = k + 1; c.questionLine = 999999;
        AiClient.remapLines(c, map, TR.segments.size());
        assertEquals((int) map.get(k), c.startLine);
        assertEquals((int) map.get(k + 1), c.endLine);
        assertEquals(-1, c.questionLine);
    }

    @Test public void endingPassOverlapIsCutBack() {
        Project.Clip a = new Project.Clip(), b = new Project.Clip();
        a.start = 100; a.end = 170; b.start = 160; b.end = 220;
        List<Project.Clip> l = new ArrayList<>();
        l.add(b); l.add(a);
        Pipeline.trimOverlaps(l, TR);
        assertTrue(a.end <= b.start);
        assertTrue(a.end - a.start >= Pipeline.TOPUP_MIN_LEN);
        assertEquals(220, b.end, 1e-9);
    }

    // ------------------------------------------------------------------ the real render step's scheduling

    static Project project(Context ctx, int n, String id) {
        Project pr = new Project();
        pr.id = id;
        pr.sourceType = "local";
        pr.title = "k29";
        pr.duration = 900;
        for (int i = 0; i < n; i++) {
            Project.Clip c = new Project.Clip();
            c.index = i; c.start = i * 100; c.end = c.start + 30;
            pr.clips.add(c);
        }
        pr.save(ctx);
        return pr;
    }

    private void renderN(int n, int par, int hwState) throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        Prefs.setHwEncodeState(ctx, hwState);
        Pipeline p = new Pipeline(ctx);
        Pipeline.parForTest = par;
        Project pr = project(ctx, n, "k29_" + n + "_" + par + "_" + hwState);
        AtomicInteger inSlot = new AtomicInteger(), maxIn = new AtomicInteger(), started = new AtomicInteger();
        Pipeline.renderHookForTest = (pp, prj, ci, threads, prog) -> {
            Project.Clip c = prj.clips.get(ci);
            started.incrementAndGet();
            Thread.sleep(15);                // network part: clip fetch + its own captions
            pp.acquireRenderSlot(c.index);   // the heavy part takes a slot
            int now = inSlot.incrementAndGet();
            maxIn.accumulateAndGet(now, Math::max);
            try {
                pp.awaitEncodeGate(c.index);
                for (int k = 1; k <= 4; k++) { prog.on(k / 4.0); Thread.sleep(10); }
                File out = new File(prj.dir(ctx), String.format(java.util.Locale.US, "Klipora_short%d_%d.mp4", c.index + 1, System.currentTimeMillis() % 100000000L));
                Files.copy(TINY.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                pp.finishClipForTest(prj, c, out);
            } finally { inSlot.decrementAndGet(); }
        };
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < n; i++) all.add(i);
        p.renderAllForTest(pr, all, new Transcript(), 3);
        assertEquals(n, started.get());
        assertTrue("at most " + par + " heavy parts at once, saw " + maxIn.get(), maxIn.get() <= Math.max(1, Math.min(par, n)));
        Set<String> paths = new HashSet<>();
        for (Project.Clip c : pr.clips) {
            assertNotNull("short #" + (c.index + 1) + " has no file", c.file());
            assertTrue(c.file().exists() && c.file().length() > 20000);
            paths.add(c.outPath);
        }
        assertEquals("every short has its own file", n, paths.size());
        Project back = Project.loadFrom(pr.dir(ctx));   // what History / the results screen reload
        assertNotNull(back);
        assertEquals(n, back.clips.size());
    }

    @Test public void renderStepMakesAllShorts() throws Exception {
        for (int n : new int[]{1, 3, 5})
            for (int par : new int[]{1, 2, 3})
                for (int hw : new int[]{0, 1}) renderN(n, par, hw);
    }

    @Test public void oneFailingShortFailsLoudlyAndTheOthersFinish() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        Prefs.setHwEncodeState(ctx, 1);
        Pipeline p = new Pipeline(ctx);
        Pipeline.parForTest = 2;
        Project pr = project(ctx, 3, "k29_fail");
        Pipeline.renderHookForTest = (pp, prj, ci, threads, prog) -> {
            Project.Clip c = prj.clips.get(ci);
            pp.acquireRenderSlot(c.index);
            pp.awaitEncodeGate(c.index);
            if (c.index == 1) throw new Exception("encoder broke on short 2");
            prog.on(1);
            File out = new File(prj.dir(ctx), "Klipora_short" + (c.index + 1) + "_x.mp4");
            Files.copy(TINY.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            pp.finishClipForTest(prj, c, out);
        };
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) all.add(i);
        long t0 = System.currentTimeMillis();
        try { p.renderAllForTest(pr, all, new Transcript(), 3); fail("a failed short must not end the step as done"); }
        catch (Exception e) { assertTrue(e.getMessage().contains("short 2")); }
        assertTrue("no wait on a dead slot", System.currentTimeMillis() - t0 < 15000);
        assertNotNull(pr.clips.get(0).file());
        assertNotNull(pr.clips.get(2).file());
    }
}
