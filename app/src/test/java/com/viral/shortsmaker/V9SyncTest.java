package com.viral.shortsmaker;

import com.viral.shortsmaker.captions.CaptionBuilder;
import com.viral.shortsmaker.captions.CaptionLayout;
import com.viral.shortsmaker.captions.CaptionStyles;
import com.viral.shortsmaker.captions.Word;
import com.viral.shortsmaker.core.CaptionScript;
import com.viral.shortsmaker.core.ClipSync;
import com.viral.shortsmaker.core.FfmpegCommands;
import com.viral.shortsmaker.core.Pipeline;
import com.viral.shortsmaker.core.Transcript;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
/** v9: caption sync (captions timed from the short itself), phrase-timed English, validated Hinglish, max 5 shorts */
public class V9SyncTest {

    static Word w(String t, double s, double e) { return new Word(t, s, e); }

    @Test public void maxFiveShorts() {
        assertEquals(5, Pipeline.MAX_SHORTS);
        assertEquals(5, Pipeline.shortsFor(15, 3600));
        assertEquals(3, Pipeline.shortsFor(3, 3600));
        assertEquals(5, Pipeline.shortsFor(0, 38 * 60));   // Auto on a 38-min video: 6 -> capped at 5
        assertEquals(1, Pipeline.shortsFor(0, 120));
    }

    @Test public void wordOffsetFindsConstantShift() {
        // his v8 short: every caption ~8.1 s late
        List<Word> spoken = Arrays.asList(w("आपने", 4.78, 5.44), w("अर्जुन", 5.44, 5.76), w("रेड्डी", 5.76, 6.02), w("से", 6.02, 6.16),
                w("शादी", 6.16, 6.44), w("की?", 6.44, 6.6), w("विजय", 7.94, 8.58));
        List<Word> late = new ArrayList<>();
        for (Word x : spoken) late.add(x.shift(8.1));
        double[] o = ClipSync.wordOffset(spoken, late);
        assertEquals(8.1, o[0], 1e-6);
        assertEquals(7, (int) o[2]);
        double[] z = ClipSync.wordOffset(spoken, spoken);
        assertEquals(0, z[0], 1e-9);
    }

    @Test public void clipWordsAreClampedAndKeepZeroLengthWords() throws Exception {
        String json = "{\"language\":\"hindi\",\"segments\":[{\"start\":0,\"end\":3,\"text\":\"a b c\"}],"
                + "\"words\":[{\"word\":\"a\",\"start\":0.0,\"end\":0.4},{\"word\":\"b\uFFFD\",\"start\":1.0,\"end\":1.0},{\"word\":\"c\",\"start\":2.9,\"end\":3.5}]}";
        Transcript t = Transcript.parseVerboseJson(json, 0);
        List<Word> ws = ClipSync.clipWords(t, 3.0);
        assertEquals(3, ws.size());
        assertEquals("b", ws.get(1).text);
        assertTrue(ws.get(1).end - ws.get(1).start >= 0.1);
        assertTrue(ws.get(2).end <= 3.0);
    }

    @Test public void audioOffsetMeasuresShift() {
        java.util.Random r = new java.util.Random(7);
        float[] src = new float[6000];
        for (int i = 0; i < src.length; i++) src[i] = (float) ((i / 37) % 3 == 0 ? 3 + r.nextDouble() : r.nextDouble() * 0.3);
        // the short really starts 8.10 s after the planned start; the source window starts 12 s before the planned start
        int start = (int) Math.round((12 + 8.10) / ClipSync.HOP);
        float[] clip = Arrays.copyOfRange(src, start, start + 2500);
        double[] o = ClipSync.audioOffset(clip, src, 12);
        assertEquals(8.10, o[0], 0.011);
        assertTrue(o[1] > 0.9);
    }

    @Test public void wordStartInSilenceMovesToSpeechOnset() {
        float[] env = new float[1000];
        Arrays.fill(env, 3f);
        for (int i = 620; i < 700; i++) env[i] = 0.4f;   // pause 6.20-7.00 s; "my" really starts at 7.00
        for (int i = 0; i < 100; i++) env[i] = 0.4f;     // silence before the first word
        List<Word> ws = ClipSync.snapToSpeech(new ArrayList<>(Arrays.asList(w("how", 0.0, 1.3), w("girl", 5.86, 6.08), w("my", 6.08, 7.2), w("dear", 7.2, 7.6))), env);
        assertEquals(0.97, ws.get(0).start, 0.011);
        assertEquals(5.86, ws.get(1).start, 1e-9);
        assertEquals(6.97, ws.get(2).start, 0.011);
        assertEquals(7.2, ws.get(3).start, 1e-9);
    }

    @Test public void alignSegmentSnapsToPauses() {
        float[] env = new float[400];
        Arrays.fill(env, 3f);
        env[230] = 0f;   // the only real pause between the two words is at 2.30 s (length guess: 2.40 s)
        List<Word> ws = ClipSync.alignSegment(new String[]{"namaste", "dosto"}, 0.0, 4.0, env);
        assertEquals(2, ws.size());
        assertEquals(2.30, ws.get(1).start, 0.011);
        assertEquals(0.0, ws.get(0).start, 1e-9);
        assertEquals(4.0, ws.get(1).end, 1e-9);
    }

    @Test public void englishPhrasesSitOnTheirSpokenTime() {
        List<Word> hi = new ArrayList<>(Arrays.asList(w("आपने", 4.78, 5.44), w("अर्जुन", 5.44, 5.76), w("रेड्डी", 5.76, 6.02), w("से", 6.02, 6.16),
                w("शादी", 6.16, 6.44), w("की?", 6.44, 6.6), w("विजय", 7.94, 8.58), w("देवरकोंडा", 8.58, 9.22), w("से", 9.22, 9.54),
                w("शादी", 9.54, 9.92), w("की?", 9.92, 10.06)));
        List<String> units = CaptionScript.phraseTexts(hi);
        assertEquals(2, units.size());
        List<Word> en = CaptionScript.phraseTranslation(hi, Arrays.asList("Did you marry Arjun Reddy?", "Did you marry Vijay Deverakonda?"));
        // every phrase word is shown exactly from its phrase's first spoken word to its last
        for (Word x : en) {
            assertTrue(x.phrase >= 0);
            if (x.phrase == 0) { assertEquals(4.78, x.start, 1e-9); assertEquals(6.6, x.end, 1e-9); }
            else { assertEquals(7.94, x.start, 1e-9); assertEquals(10.06, x.end, 1e-9); }
        }
        // a long translation is split into chunks inside the same spoken time
        List<Word> longer = CaptionScript.phrases(hi.subList(0, 6), "one two three four five six seven eight nine ten", new int[]{0});
        assertTrue(longer.get(0).phrase != longer.get(longer.size() - 1).phrase);
        assertEquals(4.78, longer.get(0).start, 1e-9);
        assertEquals(6.6, longer.get(longer.size() - 1).end, 1e-9);
    }

    @Test public void phrasePagesNeverOverlapAndAreShownWhole() throws Exception {
        CaptionBuilder.measurer = new AwtMeasurer(new File("src/main/assets/fonts"));
        List<Word> hi = new ArrayList<>();
        double t = 0.5;
        String[] toks = "मैंने कभी किसी का बिस्तर गर्म नहीं किया है और मैं ये सच में बोल रही हूँ आज".split(" ");
        for (String s : toks) { hi.add(w(s, t, t + 0.3)); t += 0.35; }
        List<String> units = CaptionScript.phraseTexts(hi);
        List<String> tl = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) tl.add(i == 0 ? "I have never warmed anyone's bed and" : "I am really saying this today");
        List<Word> en = CaptionScript.phraseTranslation(hi, tl);
        for (String id : new String[]{"hormozi", "karaoke", "clean", "beast", "boxword"}) {
            String ass = CaptionBuilder.buildAss(en, CaptionStyles.byId(id), new CaptionLayout(), 10);
            double lastEnd = -1;
            for (String line : ass.split("\n")) {
                if (!line.startsWith("Dialogue: 1,")) continue;
                String[] p = line.split(",", 10);
                double s = ts(p[1]), e = ts(p[2]);
                assertTrue(id + " overlap at " + line, s >= lastEnd - 0.011);
                assertFalse(id + ": no word highlight inside a translation", p[9].contains("\\kf") && p[9].matches(".*\\\\kf\\d+.*\\\\kf.*"));
                lastEnd = e;
            }
        }
    }

    static double ts(String s) { String[] a = s.trim().split(":"); return Integer.parseInt(a[0]) * 3600 + Integer.parseInt(a[1]) * 60 + Double.parseDouble(a[2]); }

    @Test public void hinglishAiMayChangeOnlyTheScript() {
        List<Word> src = Arrays.asList(w("आपने", 0, 1), w("शादी", 1, 2), w("की", 2, 3), w("प्यार", 3, 4));
        // the AI paraphrased one word ("married") and kept the others
        int[] rep = {0};
        List<Word> out = CaptionScript.validateTranslit(src, Arrays.asList("aapne", "married", "ki", "pyaar"), CaptionScript.HINGLISH, rep);
        assertEquals(1, rep[0]);
        assertEquals(4, out.size());
        assertEquals("aapne", out.get(0).text);
        assertNotEquals("married", out.get(1).text);
        assertTrue(CaptionScript.sameSpokenWord(out.get(1).text, "shaadi"));
        assertEquals("pyaar", out.get(3).text);
        for (int i = 0; i < 4; i++) assertEquals(src.get(i).start, out.get(i).start, 1e-9);
        assertTrue(CaptionScript.sameSpokenWord("pyar", "pyaar"));
        assertTrue(CaptionScript.sameSpokenWord("kya", "kyaa"));
        assertFalse(CaptionScript.sameSpokenWord("married", "shaadee"));
        assertFalse(CaptionScript.sameSpokenWord("you", "aapne"));
    }

    @Test public void clipSpeechAudioStartsAtFileTimeZero() {
        String a = String.join(" ", FfmpegCommands.clipSpeechAudio("in.mp4", "o.flac"));
        assertTrue(a.contains("first_pts=0"));
        assertFalse(a.contains("-ss"));
        String b = String.join(" ", FfmpegCommands.burnCaptions("raw.mp4", "c.ass", "f", "o.mp4", FfmpegCommands.Encoder.X264, 720, 0));
        assertTrue(b.contains("-c:a copy"));
    }

    // ------------------------------------------------------------------ desktop harness (driven by k9 test script)

    static String rd(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    static void wr(File f, String s) throws Exception { Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8)); }

    @Test public void harness() throws Exception {
        String d = System.getProperty("v9Dir", "");
        Assume.assumeTrue(d != null && !d.isEmpty());
        File dir = new File(d);
        String mode = System.getProperty("v9Mode", "");
        JSONObject in = new JSONObject(rd(new File(dir, "in.json")));
        String fonts = new File("src/main/assets/fonts").getAbsolutePath();
        if (mode.equals("plan")) {
            double dur = in.getDouble("duration");
            JSONArray out = new JSONArray();
            List<double[]> plan = Pipeline.piecePlan(dur);
            for (int k = 0; k < plan.size(); k++) {
                double[] p = plan.get(k);
                String[] args = FfmpegCommands.cutAudioOpus(in.getString("src"), p[0], p[1], new File(dir, "piece" + k + ".ogg").getAbsolutePath(), 1.0);
                out.put(new JSONObject().put("cs", p[0]).put("len", p[1]).put("kf", p[2]).put("kt", p[3] == Double.MAX_VALUE ? -1 : p[3])
                        .put("args", new JSONArray(Arrays.asList(args))));
            }
            wr(new File(dir, "plan.json"), new JSONObject().put("pieces", out).toString(1));
        } else if (mode.equals("merge")) {
            JSONArray ps = new JSONObject(rd(new File(dir, "plan.json"))).getJSONArray("pieces");
            int n = ps.length();
            List<Transcript> parts = new ArrayList<>();
            double[] cs = new double[n], kf = new double[n], kt = new double[n];
            for (int k = 0; k < n; k++) {
                JSONObject p = ps.getJSONObject(k);
                parts.add(Transcript.parseVerboseJson(rd(new File(dir, "piece" + k + ".json")), 0));
                cs[k] = p.getDouble("cs"); kf[k] = p.getDouble("kf"); kt[k] = p.getDouble("kt") < 0 ? Double.MAX_VALUE : p.getDouble("kt");
            }
            Transcript tr = Transcript.merge(parts, cs, kf, kt);
            tr.language = in.getString("lang");
            tr.punctuateWords();
            wr(new File(dir, "transcript.json"), tr.toJson().toString());
        } else if (mode.equals("render")) {
            double s = in.getDouble("start"), e = in.getDouble("end");
            String raw = new File(dir, "raw.mp4").getAbsolutePath();
            String[] r = FfmpegCommands.renderClip(in.getString("video"), null, s, e - s, FfmpegCommands.Frame.BLUR, null, fonts, raw, 720, FfmpegCommands.Encoder.X264, 0);
            String[] a = FfmpegCommands.clipSpeechAudio(raw, new File(dir, "clip_speech.flac").getAbsolutePath());
            wr(new File(dir, "render.json"), new JSONObject().put("render", new JSONArray(Arrays.asList(r))).put("audio", new JSONArray(Arrays.asList(a))).toString(1));
        } else if (mode.equals("words")) {
            // the short's own transcript -> spoken words on its timeline (what the app burns), + full-transcript check
            double s = in.getDouble("start"), e = in.getDouble("end");
            Transcript ct = Transcript.parseVerboseJson(rd(new File(dir, "clip_tx.json")), 0);
            ct.punctuateWords();
            List<Word> words = ClipSync.clipWords(ct, e - s);
            File pcm = new File(dir, "clip_speech.pcm");
            if (pcm.exists()) {
                byte[] b = Files.readAllBytes(pcm.toPath());
                short[] sh = new short[b.length / 2];
                java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sh);
                words = ClipSync.refineWords(words, ClipSync.envelope(sh, 16000));
            }
            Transcript tr = Transcript.fromJson(rd(new File(dir, "transcript.json")));
            double[] o = ClipSync.wordOffset(words, tr.wordsForClip(s, e));
            wr(new File(dir, "spoken.json"), Pipeline.wordsToJsonPublic(words));
            wr(new File(dir, "phrases.json"), new JSONArray(CaptionScript.phraseTexts(words)).toString(1));
            wr(new File(dir, "src_words.json"), new JSONArray(texts(words)).toString(1));
            wr(new File(dir, "fulltx_offset.json"), new JSONObject().put("median", o[0]).put("max", o[1]).put("matched", o[2]).toString());
        } else if (mode.equals("captions")) {
            CaptionBuilder.measurer = new AwtMeasurer(new File("src/main/assets/fonts"));
            double s = in.getDouble("start"), e = in.getDouble("end");
            List<Word> words = Pipeline.wordsFromJson(rd(new File(dir, "spoken.json")));
            String lang = in.getString("lang");
            JSONObject res = new JSONObject();
            JSONArray burns = new JSONArray();
            for (String[] l : new String[][]{{"hinglish", CaptionScript.HINGLISH}, {"hindi", CaptionScript.HINDI}, {"english", CaptionScript.ENGLISH}}) {
                String act = CaptionScript.action(words, lang, l[1]);
                List<Word> cw;
                int[] rep = {0};
                if (act.equals("keep")) cw = words;
                else if (act.equals("translit")) {
                    File ai = new File(dir, "ai_translit.json");
                    List<String> a = null;
                    if (ai.exists()) { JSONArray j = new JSONArray(rd(ai)); a = new ArrayList<>(); for (int i = 0; i < j.length(); i++) a.add(j.getString(i)); }
                    cw = CaptionScript.romanize(a != null && a.size() == words.size() ? CaptionScript.validateTranslit(words, a, l[1], rep) : words);
                } else {
                    JSONArray j = new JSONArray(rd(new File(dir, "translated_" + l[0] + ".json")));
                    List<String> tl = new ArrayList<>();
                    for (int i = 0; i < j.length(); i++) tl.add(j.getString(i));
                    cw = CaptionScript.phraseTranslation(words, tl);
                }
                String ass = CaptionBuilder.buildAss(cw, CaptionStyles.byId(in.optString("style", "hormozi")), new CaptionLayout(), e - s);
                File af = new File(dir, "cap_" + l[0] + ".ass");
                wr(af, ass);
                JSONArray wj = new JSONArray();
                for (Word x : cw) wj.put(new JSONArray().put(x.text).put(x.start).put(x.end).put(x.phrase));
                res.put(l[0], new JSONObject().put("action", act).put("replaced", rep[0]).put("words", wj));
                burns.put(new JSONObject().put("lang", l[0]).put("args", new JSONArray(Arrays.asList(FfmpegCommands.burnCaptions(
                        new File(dir, "raw.mp4").getAbsolutePath(), af.getAbsolutePath(), fonts, new File(dir, "final_" + l[0] + ".mp4").getAbsolutePath(),
                        FfmpegCommands.Encoder.X264, 720, 0)))));
            }
            wr(new File(dir, "captions.json"), res.toString(1));
            wr(new File(dir, "burn.json"), burns.toString(1));
        }
    }

    static List<String> texts(List<Word> w) { List<String> l = new ArrayList<>(); for (Word x : w) l.add(x.text); return l; }
}
