package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import com.viral.shortsmaker.captions.CaptionBuilder;
import com.viral.shortsmaker.captions.CaptionLayout;
import com.viral.shortsmaker.captions.CaptionStyles;
import com.viral.shortsmaker.captions.Word;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 5.4 caption dropout. Sakib's 35 s short (Doraemon dub with music under the voice): Whisper heard the first 10 s, then
 * looped / went quiet on 12-33 s, so the captions were garbled and then missing while the voice went on. The fixtures are
 * a REAL Whisper run on that short's own audio (dropout_a_full.json: one 35 s request, loop segments with their
 * compression ratio) and the same audio transcribed in 6 s pieces (dropout_a_truth.json: what the speech really holds,
 * absolute times). The recognizer in the re-check is replaced by that second file, cut to whatever window is asked.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class V36CaptionDropoutTest {
    static final double DUR = 35.35;

    static String res(String name) throws Exception {
        try (InputStream in = V36CaptionDropoutTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name, in);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static Transcript full() throws Exception { return Transcript.parseVerboseJson(res("dropout_a_full.json"), 0); }

    static List<Word> truth() throws Exception {
        JSONArray a = new JSONObject(res("dropout_a_truth.json")).getJSONArray("words");
        List<Word> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); out.add(new Word(o.getString("word"), o.getDouble("start"), o.getDouble("end"))); }
        return out;
    }

    /** the recognizer for a window: the true words that start inside it, times relative to the window (like Groq's answer) */
    static CaptionRecovery.Window oracle(List<double[]> asked) throws Exception {
        final List<Word> tr = truth();
        return (a, b) -> {
            if (asked != null) asked.add(new double[]{a, b});
            Transcript t = new Transcript();
            StringBuilder sb = new StringBuilder();
            for (Word w : tr) if (w.start >= a && w.start < b) { t.words.add(w.shift(-a)); sb.append(w.text).append(' '); }
            if (t.words.isEmpty()) return null;
            t.segments.add(new Transcript.Segment(t.words.get(0).start, t.words.get(t.words.size() - 1).end, sb.toString().trim()));
            return t;
        };
    }

    static double maxGap(List<Word> ws, double dur) {
        double prev = 0, g = 0;
        for (Word w : ws) { g = Math.max(g, w.start - prev); prev = Math.max(prev, Math.min(w.end, w.start + 1.2)); }
        return Math.max(g, dur - prev);
    }

    static double parseTime(String s) {
        String[] p = s.split(":");
        return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Double.parseDouble(p[2]);
    }

    static List<double[]> events(String ass) {
        List<double[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("Dialogue: (\\d+),([0-9:.]+),([0-9:.]+),").matcher(ass);
        while (m.find()) if (Integer.parseInt(m.group(1)) == 1) out.add(new double[]{parseTime(m.group(2)), parseTime(m.group(3))});
        return out;
    }

    /** share of the true words (after 'from') whose start time has a caption on screen in the ASS script */
    static double shownShare(List<Word> captionWords, List<Word> truth, double from) throws Exception {
        List<Word> roman = new ArrayList<>();
        for (Word w : captionWords) roman.add(w.withText(Translit.hasDevanagari(w.text) ? Translit.word(w.text) : w.text));
        String ass = CaptionBuilder.buildAss(roman, CaptionStyles.byId("hormozi"), new CaptionLayout(), DUR);
        List<double[]> ev = events(ass);
        int n = 0, shown = 0;
        for (Word w : truth) {
            if (w.start < from) continue;
            n++;
            double t = w.start + 0.05;
            for (double[] e : ev) if (e[0] <= t && t < e[1]) { shown++; break; }
        }
        return n == 0 ? 1 : (double) shown / n;
    }

    // ------------------------------------------------------------------ the bug, on his own audio

    @Test public void before_the_fix_the_loop_stretch_is_lost_and_captions_stop() throws Exception {
        Transcript t = full();
        List<Word> ws = ClipSync.clipWords(t, DUR);
        assertFalse("Whisper's loop was recorded", t.loops.isEmpty());
        double lastBeforeGap = 0;
        for (Word w : ws) if (w.start < 20) lastBeforeGap = Math.max(lastBeforeGap, w.start);
        assertTrue("words stop right after ~13 s: last word " + lastBeforeGap, lastBeforeGap < 14);
        assertTrue("then about 20 s without a single word: " + maxGap(ws, DUR), maxGap(ws, DUR) > 15);
        double share = shownShare(ws, truth(), 10.5);
        assertTrue("only " + share + " of the speech after 10.5 s has a caption", share < 0.35);
    }

    @Test public void suspectSpansCoverTheLostStretch() throws Exception {
        List<double[]> sp = CaptionRecovery.suspectSpans(full(), DUR);
        assertFalse(sp.isEmpty());
        double a = 99, b = 0;
        for (double[] s : sp) { a = Math.min(a, s[0]); b = Math.max(b, s[1]); }
        assertTrue("starts by 14 s: " + a, a < 14);
        assertTrue("reaches 33 s: " + b, b > 33);
    }

    @Test public void recoveryPutsEverySpokenWordBackThroughTheEndOfTheShort() throws Exception {
        Transcript t = full();
        List<double[]> asked = new ArrayList<>();
        int n = CaptionRecovery.recover(t, DUR, oracle(asked), null);
        assertTrue(n >= 3 && n <= CaptionRecovery.MAX_WINDOWS);
        List<Word> ws = ClipSync.clipWords(t, DUR);
        List<Word> truth = truth();
        // completeness: no stretch longer than a real pause is left without words, up to the end
        assertTrue("longest silence now " + maxGap(ws, DUR), maxGap(ws, DUR) <= 3.0);
        int found = 0, total = 0;
        for (Word tw : truth) {
            if (tw.start < 13) continue;
            total++;
            for (Word w : ws) if (w.text.equals(tw.text) && Math.abs(w.start - tw.start) < 0.01) { found++; break; }
        }
        assertTrue(found + " of " + total + " words of 13 s..end back", found >= total * 0.95);
        // every caption page of the finished script is on screen when its word is spoken, to the last second
        double share = shownShare(ws, truth, 0);
        assertTrue("captions on screen for " + share + " of the spoken words", share >= 0.9);
        double last = 0;
        for (Word w : ws) last = Math.max(last, w.start);
        assertTrue("captions run to the end: last word " + last, last > DUR - 1.5);
        // requests asked only inside the lost stretch (+ context), each window at most WIN_SEC + 2 * PAD long
        for (double[] w : asked) {
            assertTrue(w[1] - w[0] <= CaptionRecovery.WIN_SEC + 2 * CaptionRecovery.PAD + 1e-6);
            assertTrue("window " + w[0] + ".." + w[1], w[1] > 12);
        }
    }

    @Test public void sync_recovered_words_sit_where_they_are_spoken_within_a_tenth_of_a_second() throws Exception {
        Transcript t = full();
        List<Word> before = ClipSync.clipWords(t, DUR);
        CaptionRecovery.recover(t, DUR, oracle(null), null);
        List<Word> after = ClipSync.clipWords(t, DUR);
        List<Word> truth = truth();
        // the words that were fine before the fix did not move
        double[] same = ClipSync.wordOffset(before, after);
        assertEquals(0.0, same[0], 1e-9);
        assertEquals(0.0, same[1], 0.1);
        // and the recovered ones sit on the cut audio's own timeline (window offsets added back exactly)
        double[] o = ClipSync.wordOffset(truth, after);
        assertTrue("matched " + o[2], o[2] > 80);
        assertEquals(0.0, o[0], 0.1);
        assertTrue("worst " + o[1], o[1] <= 0.1);
    }

    @Test public void numbersBeforeAndAfter() throws Exception {
        Transcript t = full();
        List<Word> b = ClipSync.clipWords(t, DUR);
        double sb = shownShare(b, truth(), 10.5), gb = maxGap(b, DUR);
        int n = CaptionRecovery.recover(t, DUR, oracle(null), null);
        List<Word> a = ClipSync.clipWords(t, DUR);
        System.out.println(String.format(java.util.Locale.US, "V36 NUMBERS words %d -> %d, longest word-free stretch %.1f s -> %.1f s, spoken words after 10.5 s with a caption on screen %.0f%% -> %.0f%%, extra requests %d, whole short %.0f%% -> %.0f%%",
                b.size(), a.size(), gb, maxGap(a, DUR), sb * 100, shownShare(a, truth(), 10.5) * 100, n, shownShare(b, truth(), 0) * 100, shownShare(a, truth(), 0) * 100));
    }

    // ------------------------------------------------------------------ logic

    @Test public void aCleanTranscriptIsLeftAlone() throws Exception {
        Transcript t = new Transcript();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) { t.words.add(new Word("w" + i, 0.3 + i * 0.5, 0.3 + i * 0.5 + 0.3)); sb.append("w").append(i).append(' '); }
        t.segments.add(new Transcript.Segment(0.3, 30, sb.toString().trim()));
        assertTrue(CaptionRecovery.suspectSpans(t, 30.5).isEmpty());
        int[] calls = {0};
        assertEquals(0, CaptionRecovery.recover(t, 30.5, (a, b) -> { calls[0]++; return null; }, null));
        assertEquals(0, calls[0]);
        assertEquals(60, t.words.size());
    }

    @Test public void aLongPauseInsideTheShortIsRechecked_aNormalPauseIsNot() {
        Transcript t = new Transcript();
        t.words.add(new Word("a", 0.2, 0.5)); t.words.add(new Word("b", 0.6, 0.9));
        t.words.add(new Word("c", 2.0, 2.3));        // 1.1 s pause: normal
        t.words.add(new Word("d", 6.0, 6.3));        // 3.4 s: suspect
        t.words.add(new Word("e", 6.4, 6.8));
        t.segments.add(new Transcript.Segment(0.2, 6.8, "a b c d e"));
        List<double[]> sp = CaptionRecovery.suspectSpans(t, 7.0);
        assertEquals(1, sp.size());
        assertEquals(2.3, sp.get(0)[0], 0.01);   // the last word's speaking end
        assertEquals(6.0, sp.get(0)[1], 0.01);
    }

    @Test public void aSegmentWhoseWordsWereNotTimedIsRechecked() {
        Transcript t = new Transcript();
        t.words.add(new Word("one", 0.3, 0.6)); t.words.add(new Word("two", 0.7, 1.0));
        t.words.add(new Word("x", 3.1, 3.4));
        t.segments.add(new Transcript.Segment(0.3, 1.2, "one two"));
        t.segments.add(new Transcript.Segment(1.5, 3.5, "three four five six seven eight nine"));   // 7 words spoken, 1 timed
        List<double[]> sp = CaptionRecovery.suspectSpans(t, 4.0);
        assertEquals(1, sp.size());
        assertEquals(1.5, sp.get(0)[0], 0.01);
    }

    @Test public void silenceTurnedIntoAStockPhraseIsNotCaptioned() throws Exception {
        Transcript t = full();
        int words = t.words.size();
        CaptionRecovery.recover(t, DUR, (a, b) -> {
            Transcript s = new Transcript();
            s.words.add(new Word("Thank", 0.2, 0.5)); s.words.add(new Word("you", 0.6, 0.9));
            s.segments.add(new Transcript.Segment(0.2, 0.9, "Thank you"));
            return s;
        }, null);
        assertEquals(words, t.words.size());
    }

    @Test public void anotherLoopIsNotCaptioned() throws Exception {
        Transcript t = full();
        int words = t.words.size();
        CaptionRecovery.recover(t, DUR, (a, b) -> {
            Transcript s = new Transcript();
            for (int i = 0; i < 10; i++) s.words.add(new Word("aap", 0.2 + i * 0.4, 0.5 + i * 0.4));
            s.segments.add(new Transcript.Segment(0.2, 4.0, "aap aap aap"));
            return s;
        }, null);
        assertEquals(words, t.words.size());
    }

    @Test public void aFailedRequestKeepsEverythingAndStops() throws Exception {
        Transcript t = full();
        int words = t.words.size();
        int[] calls = {0};
        CaptionRecovery.recover(t, DUR, (a, b) -> { calls[0]++; throw new java.io.IOException("Groq limit"); }, null);
        assertEquals(1, calls[0]);
        assertEquals(words, t.words.size());
    }

    @Test public void aCancelIsNotSwallowed() throws Exception {
        try {
            CaptionRecovery.recover(full(), DUR, (a, b) -> { throw new CancelToken.Cancelled(); }, null);
            fail("cancel must pass through");
        } catch (CancelToken.Cancelled expected) { }
    }

    @Test public void atMostTenExtraRequestsPerShort() throws Exception {
        Transcript t = new Transcript();
        t.words.add(new Word("a", 0.2, 0.5)); t.words.add(new Word("b", 0.6, 0.9));
        t.segments.add(new Transcript.Segment(0.2, 0.9, "a b"));
        int[] calls = {0};
        CaptionRecovery.recover(t, 120, (a, b) -> { calls[0]++; return null; }, null);
        assertEquals(CaptionRecovery.MAX_WINDOWS, calls[0]);
    }

    @Test public void coresPartitionTheSpan() {
        List<double[]> c = CaptionRecovery.cores(12.9, 33.4);
        assertEquals(4, c.size());
        assertEquals(12.9, c.get(0)[0], 1e-9);
        assertEquals(33.4, c.get(c.size() - 1)[1], 1e-9);
        for (int i = 1; i < c.size(); i++) assertEquals(c.get(i - 1)[1], c.get(i)[0], 1e-9);
        for (double[] x : c) assertTrue(x[1] - x[0] <= CaptionRecovery.WIN_SEC + 1e-9);
    }

    // ------------------------------------------------------------------ wiring: one fix for both engines

    @Test public void theShortsOwnTranscriptIsRecoveredBeforeAnyEngineDrawsIt() throws Exception {
        java.lang.reflect.Method m = Pipeline.class.getDeclaredMethod("recoverDropouts", Transcript.class, java.io.File.class, String.class, double.class, Project.Clip.class);
        assertNotNull(m);
        // the FFmpeg ASS file and the backup engine's painter both take the words spokenWords() returns (lastAssWords)
        assertNotNull(Pipeline.class.getDeclaredField("lastAssWords"));
    }
}
