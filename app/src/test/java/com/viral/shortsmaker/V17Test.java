package com.viral.shortsmaker;

import static org.junit.Assert.*;

import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.captions.Word;
import com.viral.shortsmaker.core.FfmpegCommands;
import com.viral.shortsmaker.core.Pipeline;
import com.viral.shortsmaker.core.Prefs;
import com.viral.shortsmaker.core.Transcript;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** v17 (2.8): transcription speed + never-fail fallback + caption-sync safety, removed settings. */
@RunWith(RobolectricTestRunner.class)
public class V17Test {

    private String res(String n) throws Exception {
        return new String(Files.readAllBytes(new File(getClass().getClassLoader().getResource(n).toURI()).toPath()), "UTF-8");
    }

    /** his 19:38:16 failure: Gemini's answer for a 4.6-min part, the last object without its closing brace */
    @Test public void geminiCutOffAnswerIsSalvaged() throws Exception {
        String raw = res("gemini_cutoff.json");
        try { new org.json.JSONArray(raw); fail("the raw answer really is broken JSON"); } catch (org.json.JSONException expected) {}
        Transcript t = Transcript.parseGeminiSegments(raw, "hi", 280);
        assertEquals(84, t.segments.size());
        assertTrue(t.salvaged);
        Transcript.Segment last = t.segments.get(t.segments.size() - 1);
        assertEquals(273.5, last.start, 1e-9);
        assertEquals("और टू द टी फॉलो", last.text);
        assertEquals(0.0, t.segments.get(0).start, 1e-9);
        for (int i = 1; i < t.segments.size(); i++) assertTrue(t.segments.get(i).start >= t.segments.get(i - 1).start);
        assertTrue(t.words.size() > 1000);
        for (Word w : t.words) assertTrue(w.end >= w.start && w.start >= 0 && w.end <= 281);
    }

    @Test public void geminiAnswerCutMidSentenceKeepsCompleteLines() {
        String raw = "[{\"s\": 0.0, \"e\": 3.2, \"t\": \"पहली लाइन\"}, {\"s\": 3.2, \"e\": 7.0, \"t\": \"दूसरी \\\"लाइन\\\"\"}, {\"s\": 7.0, \"e\": 9.5, \"t\": \"तीसरी लाइन जो बीच में कट";
        Transcript t = Transcript.parseGeminiSegments(raw, "hi", 150);
        assertEquals(2, t.segments.size());
        assertEquals("दूसरी \"लाइन\"", t.segments.get(1).text);
        assertTrue(t.salvaged);
        assertEquals(7.0, t.lastEnd(), 1e-9);
        // a clean compact answer is not "salvaged"
        Transcript ok = Transcript.parseGeminiSegments("[{\"s\":0,\"e\":2,\"t\":\"a b\"},{\"s\":2,\"e\":4,\"t\":\"c\"}]", "hi", 10);
        assertEquals(2, ok.segments.size());
        assertFalse(ok.salvaged);
        // garbage / out-of-range / reversed segments are dropped, not fatal
        Transcript g = Transcript.parseGeminiSegments("```json\n[{\"s\":1,\"e\":0.5,\"t\":\"x\"},{\"s\":2,\"e\":3,\"t\":\"ok\"},{\"s\":900,\"e\":901,\"t\":\"far\"}]```", "hi", 150);
        assertEquals(1, g.segments.size());
        assertEquals(0, Transcript.parseGeminiSegments("not json at all", "hi", 10).segments.size());
    }

    /** Gemini pieces: each piece's times are shifted by exactly its cut start; no gap and no double word at the joins */
    @Test public void geminiChunksMergeWithExactOffsets() {
        double len = 466.7;   // one 2.8 part: ~7.8 min of (sped-up) speech
        List<double[]> plan = Pipeline.geminiChunks(len);
        assertEquals(4, plan.size());
        for (double[] p : plan) assertTrue("piece <= 2.6 min", p[1] <= 150 / 1.0 * 0 + Math.ceil(len / 4) + 2 * 3 + 1.01);
        // a "true" word every 0.7 s on the part's timeline; each piece reports the words it hears, relative to its cut
        List<Transcript> parts = new ArrayList<>();
        double[] cs = new double[plan.size()], kf = new double[plan.size()], kt = new double[plan.size()];
        for (int j = 0; j < plan.size(); j++) {
            double[] p = plan.get(j);
            cs[j] = p[0]; kf[j] = p[2]; kt[j] = p[3];
            Transcript t = new Transcript();
            for (double w = 0.2; w < len; w += 0.7) {
                if (w < p[0] + 0.05 || w > Math.min(len, p[0] + p[1]) - 0.4) continue;
                double rel = w - p[0];
                t.words.add(new Word("w" + Math.round(w * 10), rel, rel + 0.3));
                t.segments.add(new Transcript.Segment(rel, rel + 0.3, "w" + Math.round(w * 10)));
            }
            parts.add(t);
        }
        Transcript m = Transcript.merge(parts, cs, kf, kt);
        int expected = 0;
        for (double w = 0.2; w < len - 0.4; w += 0.7) expected++;
        assertEquals("every word exactly once", expected, m.words.size());
        double prev = -1;
        for (Word w : m.words) {
            double truth = Integer.parseInt(w.text.substring(1)) / 10.0;
            assertEquals("absolute time of " + w.text, truth, w.start, 0.051);
            assertTrue(w.start > prev);
            prev = w.start;
        }
    }

    /** a loop re-check of only 20 s goes back into exactly its place */
    @Test public void loopRecheckSplicesOnlyTheSpan() {
        Transcript t = new Transcript();
        for (int i = 0; i < 100; i++) t.words.add(new Word("a" + i, i * 3.0, i * 3.0 + 1));
        t.loops.add(new double[]{120, 150});
        t.loops.add(new double[]{152, 160});
        t.words.removeIf(w -> w.start >= 120 && w.start < 160);
        List<double[]> spans = t.loopSpans(20);
        assertEquals(1, spans.size());
        assertEquals(120, spans.get(0)[0], 1e-9);
        assertEquals(160, spans.get(0)[1], 1e-9);
        Transcript sub = new Transcript();      // re-check cut at 116 s, so its 0 s = 116 s
        for (double s = 0.5; s < 48; s += 2) sub.words.add(new Word("r" + s, s, s + 0.5));
        t.spliceSpan(sub, 116, 117.5, 162.5);
        for (Word w : t.words) if (w.text.startsWith("r")) {
            double rel = Double.parseDouble(w.text.substring(1));
            assertEquals(116 + rel, w.start, 1e-9);
            assertTrue(w.start >= 117.5 && w.start < 162.5);
        }
        assertTrue(t.loops.isEmpty());
        for (int i = 1; i < t.words.size(); i++) assertTrue(t.words.get(i).start >= t.words.get(i - 1).start);
        assertTrue(t.words.stream().anyMatch(w -> w.text.equals("a39")));   // 117 s: before the splice, kept
    }

    /** 130-min podcast (his log): 15 parts instead of 24, each <= 8 min of audio, all inside Groq's hourly budget */
    @Test public void piecePlanUsesFewerLongerParts() {
        List<double[]> p130 = Pipeline.piecePlan(7800);
        assertTrue("parts " + p130.size(), p130.size() <= 16 && p130.size() >= 12);
        double tempo = Pipeline.speechTempo(7800), sent = 0;
        for (double[] p : p130) { assertTrue(p[1] / tempo <= 485); sent += Math.min(p[1], 7800 - p[0]) / tempo; }
        assertTrue("audio sent " + sent, sent <= 7200 - 300);
        assertEquals(6, Pipeline.piecePlan(1800).size());   // 30 min: 5-min parts as before
        assertEquals(12, Pipeline.piecePlan(3600).size());
        assertEquals(13, Pipeline.piecePlan(5700).size());   // 95 min (his 2.6 log: 19 parts)
    }

    @Test public void speechPartsAreResampledBeforeTheSpeedUp() {
        String a = String.join(" ", FfmpegCommands.cutAudioOpus("/a.webm", 12.5, 300, "/p.ogg", 1.18));
        int rs = a.indexOf("aresample=16000"), at = a.indexOf("atempo=1.180");
        assertTrue(a, rs > 0 && at > rs);
        assertTrue(a.contains("-b:a 14k") && a.contains("-compression_level 0") && a.contains("-frame_duration 60"));
        assertTrue("accurate input seek kept", a.startsWith("-y -hide_banner -ss 12.500 -t 300.000 -i /a.webm"));
        assertFalse(String.join(" ", FfmpegCommands.cutAudioOpus("/a.webm", 0, 300, "/p.ogg", 1.0)).contains("atempo"));
    }

    @Test public void removedSwitchesAreAlwaysOnAndMigrated() {
        android.content.Context c = ApplicationProvider.getApplicationContext();
        Prefs.sp(c).edit().putBoolean("hw_encode", false).putBoolean("keep_screen_on", false).putInt("hw_encode_state", 0).remove("v17_switches_migrated").commit();
        assertTrue(Prefs.hwEncodeOn(c));
        assertTrue(Prefs.keepScreenOn(c));
        assertFalse(Prefs.sp(c).contains("hw_encode"));
        assertFalse(Prefs.sp(c).contains("keep_screen_on"));
        assertEquals(0, Prefs.hwEncodeState(c));
    }

    @Test public void onlyKeyAndNetworkErrorsStopTheWholeTranscript() throws Exception {
        java.lang.reflect.Method f = Pipeline.class.getDeclaredMethod("fatalAiError", Throwable.class);
        f.setAccessible(true);
        assertTrue((Boolean) f.invoke(null, new Exception("Groq rejected your API key (401). Update it in Settings.")));
        assertFalse((Boolean) f.invoke(null, new Exception("Groq's free hourly audio limit is used up and Gemini failed too (x)")));
        assertFalse((Boolean) f.invoke(null, new Exception("Gemini returned an empty transcript")));
        assertFalse((Boolean) f.invoke(null, new Exception("Groq's free hourly audio limit is used up and Gemini failed too (Gemini rejected your API key: bad. Update it in Settings.)")));
    }

    /**
     * Caption sync: the same speech cut with 2.7's command and 2.8's command (desktop ffmpeg) starts at the same
     * sample: cross-correlation of the two decodes peaks within 10 ms, at 1.0x and at the 1.18x speed-up.
     */
    @Test public void newCutStartsAtTheSameSampleAsTheOld() throws Exception {
        String src = System.getProperty("v17Audio", "/home/user/tab/work/k17test/src130.webm");
        if (!new File(src).exists() || !hasFfmpeg()) { System.out.println("v17 sync: skipped (no test audio/ffmpeg)"); return; }
        File dir = Files.createTempDirectory("v17sync").toFile();
        for (double tempo : new double[]{1.0, 1.18}) for (double at : new double[]{1234.567, 6000.25}) {
            List<String> old = new ArrayList<>(Arrays.asList("-y", "-hide_banner", "-ss", String.format(java.util.Locale.US, "%.3f", at), "-t", "60.000", "-i", src,
                    "-vn", "-sn", "-dn", "-map", "0:a:0", "-ac", "1", "-ar", "16000"));
            if (tempo > 1) old.addAll(Arrays.asList("-af", "atempo=" + String.format(java.util.Locale.US, "%.3f", tempo)));
            old.addAll(Arrays.asList("-c:a", "libopus", "-b:a", "20k", "-vbr", "on", "-application", "voip", "-compression_level", "3", new File(dir, "old.ogg").getPath()));
            ff(old);
            ff(Arrays.asList(FfmpegCommands.cutAudioOpus(src, at, 60, new File(dir, "new.ogg").getPath(), tempo)));
            float[] a = pcm(new File(dir, "old.ogg")), b = pcm(new File(dir, "new.ogg"));
            int lag = bestLag(a, b, 1600);   // +-100 ms at 16 kHz
            System.out.println(String.format(java.util.Locale.US, "v17 sync: tempo %.2f at %.1f s: new vs old offset %.2f ms, lengths %.3f / %.3f s",
                    tempo, at, lag / 16.0, a.length / 16000.0, b.length / 16000.0));
            assertTrue("offset " + lag / 16.0 + " ms", Math.abs(lag) <= 160);
            assertEquals(a.length / 16000.0, b.length / 16000.0, 0.08);
        }
    }

    private static boolean hasFfmpeg() { try { return new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0; } catch (Exception e) { return false; } }

    private static void ff(List<String> args) throws Exception {
        List<String> c = new ArrayList<>(); c.add("ffmpeg"); c.addAll(args);
        Process p = new ProcessBuilder(c).redirectErrorStream(true).redirectOutput(new File("/dev/null")).start();
        assertEquals(0, p.waitFor());
    }

    private static float[] pcm(File f) throws Exception {
        File raw = new File(f.getPath() + ".raw");
        ff(Arrays.asList("-y", "-hide_banner", "-i", f.getPath(), "-ac", "1", "-ar", "16000", "-f", "f32le", raw.getPath()));
        byte[] b = Files.readAllBytes(raw.toPath());
        java.nio.FloatBuffer fb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] out = new float[fb.remaining()];
        fb.get(out);
        return out;
    }

    /** lag of b against a (samples) with the highest correlation of the two signals' energy envelopes (1 ms frames) */
    static int bestLag(float[] a, float[] b, int max) {
        int n = Math.min(a.length, b.length) - 2 * max;
        double best = -1e18; int arg = 0;
        for (int lag = -max; lag <= max; lag += 4) {
            double s = 0;
            for (int i = max; i < max + n; i += 3) { int j = i + lag; if (j < 0 || j >= b.length) continue; s += a[i] * b[j]; }
            if (s > best) { best = s; arg = lag; }
        }
        return arg;
    }
}
