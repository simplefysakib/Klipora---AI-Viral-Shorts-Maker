package com.viral.shortsmaker;

import static org.junit.Assert.*;

import com.viral.shortsmaker.captions.Word;
import com.viral.shortsmaker.core.*;

import org.junit.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** v4 (2.0) fixes: yt-dlp process ids, long-audio chunking, sentence-snapped clips, model ranking, AI answer validation. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class CoreLogicTest {

    /** Fake youtubedl-android: refuses an id that is still registered, and (like a timed-out run) can leak an id. */
    static final class FakeYtdl {
        final Set<String> registered = ConcurrentHashMap.newKeySet();
        int calls;
        String run(String id, boolean fail, boolean leak) throws Exception {
            calls++;
            if (registered.contains(id)) throw new Exception("Process ID already exists");
            registered.add(id);
            if (fail) { if (!leak) registered.remove(id); throw new Exception("ERROR: HTTP Error 403"); }
            registered.remove(id);
            return "ok:" + id;
        }
    }

    @Test public void retriesNeverReuseAProcessId() throws Exception {
        FakeYtdl y = new FakeYtdl();
        Set<String> live = ConcurrentHashMap.newKeySet();
        Set<String> used = new HashSet<>();
        // attempts 1 and 2 fail and leak their id (the v1 bug: the next attempt with the same id died), attempt 3 works
        for (int attempt = 0; attempt < 3; attempt++) {
            final boolean fail = attempt < 2;
            try {
                String r = ProcIds.run("audio", live, id -> y.registered.remove(id), id -> { used.add(id); return y.run(id, fail, true); });
                assertTrue(r.startsWith("ok:audio-"));
            } catch (Exception e) {
                assertFalse("must never hit the duplicate-id error", e.getMessage().contains("already exists"));
                assertTrue(fail);
            }
        }
        assertEquals(3, used.size());
        assertTrue("every attempt is cleaned up", live.isEmpty() && y.registered.isEmpty());
    }

    @Test public void duplicateIdClashIsRetriedWithAFreshId() throws Exception {
        final int[] n = {0};
        String r = ProcIds.run("video", null, null, id -> { if (n[0]++ == 0) throw new Exception("Process ID already exists"); return id; });
        assertEquals(2, n[0]);
        assertTrue(r.startsWith("video-"));
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) assertTrue(ids.add(ProcIds.next("x")));
    }

    @Test public void longPodcastIsSplitIntoSmallParallelPieces() {
        for (double dur : new double[]{3600, 7200, 10800}) {
            List<double[]> plan = Pipeline.piecePlan(dur);
            double tempo = Pipeline.speechTempo(dur);
            for (double[] p : plan) {
                double audioSec = p[1] / tempo;
                assertTrue("piece too long: " + audioSec, audioSec <= 8 * 60 + 5);
                // worst case stream-copied 160 kbps audio must stay under the 18 MB guard
                assertTrue(p[1] * 160_000 / 8 < 18L * 1024 * 1024 || tempo > 1.0);
            }
            double sent = dur / tempo;
            assertTrue("total audio sent fits the free hourly budget: " + sent, sent <= 6700);
        }
        assertEquals(1.0, Pipeline.speechTempo(3000), 0);
        assertTrue(Pipeline.speechTempo(10800) > 1.6 && Pipeline.speechTempo(10800) <= 1.7);
    }

    @Test public void punctuationMovesFromSegmentsToWords() {
        Transcript t = new Transcript();
        t.segments.add(new Transcript.Segment(0, 4, "strapping themselves to a rocket. And if that"));
        String[] w = {"strapping", "themselves", "to", "a", "rocket", "And", "if", "that"};
        for (int i = 0; i < w.length; i++) t.words.add(new Word(w[i], i * 0.5, i * 0.5 + 0.4));
        t.words.set(4, new Word("rocket", 2.0, 2.0));   // zero-length, as whisper gives sometimes
        t.punctuateWords();
        assertEquals("rocket.", t.words.get(4).text);
        List<Word> c = t.wordsForClip(0, 4);
        assertEquals("zero-length word kept", 8, c.size());
        assertTrue(c.get(4).end > c.get(4).start);
        for (int i = 0; i + 1 < c.size(); i++) assertTrue(c.get(i).end <= c.get(i + 1).start + 1e-9);
    }

    @Test public void scaledTranscriptRestoresRealTime() {
        Transcript t = new Transcript();
        t.segments.add(new Transcript.Segment(10, 20, "Hello."));
        t.words.add(new Word("Hello", 10, 11));
        Transcript s = t.scaled(1.5);
        assertEquals(15, s.segments.get(0).start, 1e-9);
        assertEquals(16.5, s.words.get(0).end, 1e-9);
    }

    static List<Transcript.Segment> talk() {
        List<Transcript.Segment> s = new ArrayList<>();
        s.add(new Transcript.Segment(0, 6, "Welcome back to the show."));
        s.add(new Transcript.Segment(6.1, 12, "So yesterday something crazy happened,"));
        s.add(new Transcript.Segment(12.05, 18, "and I still can't believe it."));
        s.add(new Transcript.Segment(19.2, 26, "Here is the thing nobody tells you about money."));
        s.add(new Transcript.Segment(26.1, 33, "Most people save first and spend later,"));
        s.add(new Transcript.Segment(33.1, 41, "but rich people do the exact opposite"));
        s.add(new Transcript.Segment(41.05, 47, "with one simple rule."));
        s.add(new Transcript.Segment(47.2, 56, "They pay themselves before anyone else."));
        s.add(new Transcript.Segment(57.5, 66, "Anyway let's take a quick break."));
        s.add(new Transcript.Segment(66.1, 80, "When we come back we talk about crypto."));
        return s;
    }

    @Test public void clipStartsAtSentenceStartAndEndsOnCompleteThought() {
        List<Transcript.Segment> segs = talk();
        // AI started mid-sentence (inside "but rich people...") and ended mid-sentence
        double[] r = ClipAligner.align(segs, null, -1, -1, 35.0, 44.0, 600);
        assertEquals("starts where the sentence starts", 26.0, r[0], 0.15);
        assertTrue("ends after 'with one simple rule.'", r[1] >= 47.0 && r[1] < 47.3);
        // AI gave line numbers: trusted when close to its times
        double[] r2 = ClipAligner.align(segs, null, 3, 7, 19.3, 55.0, 600);
        assertEquals(19.1, r2[0], 0.15);
        assertTrue(r2[1] >= 56 && r2[1] <= 56.5);   // v18: + up to 0.45 s tail (the next line starts at 57.5)
        // too short -> extended by whole sentences to >= 15 s
        double[] r3 = ClipAligner.align(segs, null, -1, -1, 19.2, 24, 600);
        assertTrue(r3[1] - r3[0] >= 15);
        assertTrue(r3[1] - r3[0] <= 59.5);
        // never past the real duration
        double[] r4 = ClipAligner.align(segs, null, -1, -1, 60, 200, 70);
        assertTrue(r4[1] <= 70);
    }

    /** dense word timings spread over each segment, like whisper gives */
    static List<Word> wordsOf(List<Transcript.Segment> segs) {
        List<Word> w = new ArrayList<>();
        for (Transcript.Segment s : segs) {
            String[] t = s.text.split(" ");
            double per = (s.end - s.start) / t.length;
            for (int i = 0; i < t.length; i++) w.add(new Word(t[i].replaceAll("[.,?!]", ""), s.start + i * per, s.start + (i + 1) * per - 0.02));
        }
        return w;
    }

    @Test public void wordEdgesNeverCutAWord() {
        List<Transcript.Segment> segs = talk();
        List<Word> w = wordsOf(segs);
        double[] r = ClipAligner.align(segs, w, -1, -1, 26.2, 46.0, 600);
        Word rule = null, they = null;
        for (Word x : w) { if (x.text.equals("rule")) rule = x; if (x.text.equals("They")) they = x; }
        assertTrue("last word kept whole", r[1] >= rule.end - 1e-9);
        assertTrue("next sentence's first word not included", r[1] <= they.start);
        assertEquals("starts on 'Most'", 26.1, r[0], 0.13);
        // a whisper segment holding the end of one sentence and the start of the next: cut at the full stop
        List<Transcript.Segment> s2 = new ArrayList<>();
        s2.add(new Transcript.Segment(0, 5, "we lost a lot of muscle in our"));
        s2.add(new Transcript.Segment(5, 10, "astronauts. And so to prevent this, we"));
        s2.add(new Transcript.Segment(10, 30, "have them exercising for hours every day and it really works for them."));
        double[] r2 = ClipAligner.align(s2, wordsOf(s2), -1, -1, 4.0, 30, 600);
        Word and = null;
        for (Word x : wordsOf(s2)) if (x.text.equals("And")) { and = x; break; }
        assertEquals("starts at 'And so'", and.start, r2[0], 0.13);
    }

    @Test public void geminiModelsRankedFromTheRealList() {
        String json = "{\"models\":["
                + "{\"name\":\"models/gemini-2.0-flash\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                + "{\"name\":\"models/gemini-2.5-flash-lite\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                + "{\"name\":\"models/gemini-2.5-flash\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                + "{\"name\":\"models/gemini-2.5-flash-preview-09-2025\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                + "{\"name\":\"models/gemini-2.5-flash-image\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                + "{\"name\":\"models/text-embedding-004\",\"supportedGenerationMethods\":[\"embedContent\"]},"
                + "{\"name\":\"models/gemini-2.5-pro\",\"supportedGenerationMethods\":[\"generateContent\"]}]}";
        List<String> r = AiClient.rankModelsForTest(json);
        assertEquals(Arrays.asList("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash"), r);
    }

    @Test public void realModelListFromOct2026() throws Exception {
        String json = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                getClass().getClassLoader().getResource("gemini_models_2026_10.json").toURI())), "UTF-8");
        List<String> r = AiClient.rankModelsForTest(json);
        assertEquals("gemini-3.8-flash", r.get(0));
        for (String m : r) assertFalse(m, m.contains("preview") || m.contains("tts") || m.contains("image") || m.contains("live"));
    }

    @Test public void aiAnswerIsValidatedAgainstDuration() throws Exception {
        String raw = "```json\n[{\"start_line\":3,\"end_line\":7,\"start\":19.2,\"end\":56.0,\"viral_score\":91,\"title\":\"Money rule\",\"hook\":\"Here is the thing\"},"
                + "{\"start\":900,\"end\":950,\"viral_score\":99},{\"start\":50,\"end\":40},{\"start\":60,\"end\":120,\"viral_score\":80}]\n```";
        List<AiClient.Candidate> c = AiClient.parseForTest(raw, 100);
        assertEquals(2, c.size());
        assertEquals(91, c.get(0).score);
        assertEquals(3, c.get(0).startLine);
        assertEquals("Money rule", c.get(0).title);
        assertEquals(100, c.get(1).end, 1e-9);
    }

    @Test public void numberedTranscriptNeverDropsText() {
        Transcript t = new Transcript();
        for (int i = 0; i < 3000; i++) t.segments.add(new Transcript.Segment(i * 3, i * 3 + 2.9, "sentence number " + i + "."));
        String full = t.forPromptNumbered(1_000_000);
        assertTrue(full.startsWith("L0 [0.0-2.9] sentence number 0."));
        String small = t.forPromptNumbered(40_000);
        for (int i = 0; i < 3000; i += 137) assertTrue("lost line " + i, small.contains("sentence number " + i + "."));
    }

    @Test public void whisperRepetitionLoopIsDropped() throws Exception {
        // real whisper output from the 2-hour test: a 4.5-min piece where the model looped on one phrase for 60 s
        String json = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                getClass().getClassLoader().getResource("whisper_loop.json").toURI())), "UTF-8");
        Transcript t = Transcript.parseVerboseJson(json, 0);
        assertTrue(t.looping());
        for (Transcript.Segment s : t.segments) assertFalse(s.text, Transcript.isLoopText(s.text, 0));
        int zeroLen = 0;
        for (Word w : t.words) if (w.end - w.start < 0.001) zeroLen++;
        assertTrue("loop words removed: " + zeroLen + " zero-length of " + t.words.size(), t.words.size() < 800);
        assertFalse(Transcript.isLoopText("So what happens there? We sent astronauts to the moon and they came back safely after a long trip.", 1.4));
    }

    @Test public void safeRenderIsSimpleAndToleratesMissingAudio() {
        String all = String.join(" ", FfmpegCommands.renderClipSafe("/in.mp4", null, 5, 30, null, null, "/o.mp4", 720));
        assertFalse(all.contains("split") || all.contains("boxblur") || all.contains("mediacodec"));
        assertTrue(all.contains("-map 0:a:0?") && all.contains("libx264") && all.contains("pad=720:1280"));
        String tempo = String.join(" ", FfmpegCommands.cutAudioOpus("/a.webm", 0, 300, "/p.ogg", 1.64));
        assertTrue(tempo.contains("atempo=1.640") && tempo.contains("-ar 16000") && tempo.contains("-ac 1"));
    }
}
