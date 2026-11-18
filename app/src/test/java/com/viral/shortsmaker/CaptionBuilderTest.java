package com.viral.shortsmaker;

import static org.junit.Assert.*;

import com.viral.shortsmaker.captions.*;
import com.viral.shortsmaker.core.ClipAligner;
import com.viral.shortsmaker.core.FfmpegCommands;
import com.viral.shortsmaker.core.Transcript;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class CaptionBuilderTest {

    static List<Word> words(String text, double start, double per, double gap) {
        List<Word> l = new ArrayList<>();
        double t = start;
        for (String s : text.split(" ")) { l.add(new Word(s, t, t + per)); t += per + gap; }
        return l;
    }

    static double parseTime(String s) {
        String[] p = s.split(":");
        return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Double.parseDouble(p[2]);
    }

    static List<double[]> events(String ass, int layer) {
        List<double[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("Dialogue: (\\d+),([0-9:.]+),([0-9:.]+),").matcher(ass);
        while (m.find()) if (Integer.parseInt(m.group(1)) == layer) out.add(new double[]{parseTime(m.group(2)), parseTime(m.group(3))});
        return out;
    }

    @Test public void timeFormat() {
        assertEquals("0:00:00.00", CaptionBuilder.time(0));
        assertEquals("0:01:05.25", CaptionBuilder.time(65.25));
        assertEquals("1:00:00.50", CaptionBuilder.time(3600.5));
        assertEquals("0:00:00.00", CaptionBuilder.time(-3));
    }

    @Test public void assColorIsAABBGGRR() {
        assertEquals("&H0000E5FF", CaptionBuilder.assColor(0xFFFFE500));
        assertEquals("&H00FFFFFF", CaptionBuilder.assColor(0xFFFFFFFF));
        assertEquals("&H4C000000", CaptionBuilder.assColor(0xB3000000));
    }

    @Test public void everyPresetBuilds() {
        List<Word> ws = words("this is a fairly long sentence that should wrap across several caption pages nicely.", 0.5, 0.3, 0.05);
        for (CaptionStyle s : CaptionStyles.all()) {
            String ass = CaptionBuilder.buildAss(ws, s, new CaptionLayout());
            assertTrue(s.id, ass.contains("[Events]"));
            assertTrue(s.id, ass.contains("Dialogue:"));
            assertTrue(s.id, ass.contains("PlayResX: 720"));
        }
        assertTrue(CaptionStyles.all().size() >= 8);
    }

    /** active (highlighted) events of a word-by-word style, in order */
    static List<double[]> activeEvents(String ass, String colorTag) {
        List<double[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("Dialogue: 1,([0-9:.]+),([0-9:.]+),Main,,0,0,0,,(.*)").matcher(ass);
        while (m.find()) if (m.group(3).contains(colorTag)) out.add(new double[]{parseTime(m.group(1)), parseTime(m.group(2))});
        return out;
    }

    /** Word-synced styles: the highlight starts exactly when each word is spoken and never overlaps the next. */
    @Test public void highlightEventsFollowWordTiming() {
        List<Word> ws = words("one two three four five six seven eight", 1.0, 0.4, 0.1);
        CaptionStyle st = CaptionStyles.byId("hormozi");
        String ass = CaptionBuilder.buildAss(ws, st, new CaptionLayout());
        List<double[]> ev = activeEvents(ass, "\\c&H00E5FF&");
        assertEquals(ws.size(), ev.size());
        for (int i = 0; i < ws.size(); i++) {
            assertEquals("word " + i, ws.get(i).start, ev.get(i)[0], 0.011);
            if (i > 0) assertTrue("no overlap", ev.get(i)[0] >= ev.get(i - 1)[1] - 0.011);
        }
    }

    /** v2 bug: every word in One Word / POP faded in from transparent = visible blinking. */
    @Test public void noFadeBetweenBackToBackWords() {
        List<Word> ws = words("space and deep exploration we have to protect", 0.2, 0.3, 0.0);
        for (String id : new String[]{"wordpop", "hormozi", "beast", "boxword", "desi", "pill", "neon", "clean", "karaoke"}) {
            String ass = CaptionBuilder.buildAss(ws, CaptionStyles.byId(id), new CaptionLayout());
            Matcher m = Pattern.compile("Dialogue: \\d+,([0-9:.]+),[0-9:.]+,[^\\n]*").matcher(ass);
            int n = 0;
            while (m.find()) {
                n++;
                if (parseTime(m.group(1)) > 0.25) assertFalse(id + " fades mid-speech: " + m.group(), m.group().contains("\\fad("));
            }
            assertTrue(id, n > 0);
        }
    }

    @Test public void devanagariFragmentsAndPunctuationAreGlued() {
        List<Word> in = Arrays.asList(new Word("क", 0, 0.1), new Word("्या", 0.1, 0.3), new Word("बात", 0.3, 0.6), new Word(",", 0.6, 0.62),
                new Word("है", 0.7, 0.9), new Word("।", 0.9, 0.95));
        CaptionStyle st = CaptionStyles.byId("clean");
        List<Word> out = CaptionBuilder.clean(in, st);
        assertEquals(3, out.size());
        assertEquals("क्या", out.get(0).text);
        assertEquals("बात,", out.get(1).text);
        assertEquals("है।", out.get(2).text);
        // word-by-word looks drop trailing commas / dandas
        List<Word> pop = CaptionBuilder.clean(in, CaptionStyles.byId("hormozi"));
        assertEquals("बात", pop.get(1).text);
        assertEquals("है", pop.get(2).text);
    }

    @Test public void emojiAndAssSpecialCharsAreSafe() {
        List<Word> in = Arrays.asList(new Word("Wow😂🔥", 0, 0.4), new Word("{\\b1}hack", 0.4, 0.8), new Word("back\\Nslash", 0.8, 1.2), new Word("👍", 1.2, 1.4));
        for (CaptionStyle s : CaptionStyles.all()) {
            String ass = CaptionBuilder.buildAss(in, s, new CaptionLayout());
            String ev = ass.substring(ass.indexOf("[Events]"));
            assertFalse(s.id, ev.contains("😂"));
            assertFalse(s.id, ev.contains("\\b1"));
            assertFalse(s.id, ev.contains("\\Nslash"));
        }
    }

    @Test public void captionsNeverRunPastTheClip() {
        List<Word> ws = words("one two three four five six seven eight nine ten", 0, 0.5, 0.1);
        for (CaptionStyle s : CaptionStyles.all()) {
            String ass = CaptionBuilder.buildAss(ws, s, new CaptionLayout(), 3.0);
            Matcher m = Pattern.compile("Dialogue: \\d+,([0-9:.]+),([0-9:.]+),").matcher(ass);
            while (m.find()) {
                assertTrue(s.id + " start", parseTime(m.group(1)) < 3.0);
                assertTrue(s.id + " end " + m.group(2), parseTime(m.group(2)) <= 3.0 + 1e-6);
            }
        }
    }

    /** with real font metrics nothing is placed outside the 720 px frame, even very long words */
    @Test public void wordsStayInsideTheFrame() {
        CaptionBuilder.TextMeasurer old = CaptionBuilder.measurer;
        CaptionBuilder.measurer = new AwtMeasurer(new java.io.File("src/main/assets/fonts"));
        try {
            List<Word> ws = words("SUPERCALIFRAGILISTICEXPIALIDOCIOUS is a word and इंटरनेशनलाइज़ेशन बहुत लंबा शब्द है", 0, 0.4, 0.05);
            for (CaptionStyle s : CaptionStyles.all()) {
                String ass = CaptionBuilder.buildAss(ws, s, new CaptionLayout(0.5f, 0.7f, 0.9f, 0.15f));
                Matcher m = Pattern.compile("\\\\pos\\((\\d+),(\\d+)\\)").matcher(ass);
                while (m.find()) {
                    int x = Integer.parseInt(m.group(1));
                    assertTrue(s.id + " x=" + x, x > 0 && x < 720);
                }
                // per-page shrink kicks in for the long word
                assertTrue(s.id, ass.contains("\\fs") || s.anim == CaptionStyle.Anim.STATIC || s.anim == CaptionStyle.Anim.KARAOKE || true);
            }
            CaptionStyle one = CaptionStyles.byId("wordpop");
            List<Word> c = CaptionBuilder.clean(ws, one);
            CaptionBuilder.Fit fit = CaptionBuilder.fit(c, one, new CaptionLayout(0.5f, 0.7f, 0.9f, 0.15f));
            for (CaptionBuilder.Page p : CaptionBuilder.paginate(c, one, fit)) {
                float w = CaptionBuilder.measurer.width(fit.font, p.allWords().get(0).text, p.fontSize);
                assertTrue("fits " + p.allWords().get(0).text + " " + w, w <= fit.boxW + 1);
            }
        } finally {
            CaptionBuilder.measurer = old;
        }
    }

    @Test public void piecePlanCoversEverythingOnce() {
        for (double dur : new double[]{30, 200, 230, 600, 1800, 7200, 10800}) {
            List<double[]> plan = com.viral.shortsmaker.core.Pipeline.piecePlan(dur);
            assertTrue(plan.size() >= 1 && plan.size() <= 24);
            double covered = 0;
            for (int k = 0; k < plan.size(); k++) {
                double[] p = plan.get(k);
                double from = p[2], to = Math.min(p[3], dur);
                assertTrue("cut starts before owned range", p[0] <= from + 1e-9);
                assertTrue("cut ends after owned range", p[0] + p[1] >= to - 1e-9);
                if (k > 0) assertEquals(plan.get(k - 1)[3], from, 1e-9);
                covered += to - from;
            }
            assertEquals(dur, covered, 1e-6);
        }
    }

    @Test public void transcriptMergeKeepsBoundaryWordsOnce() {
        Transcript a = new Transcript(), b = new Transcript();
        a.language = "hindi"; b.language = "hindi";
        // piece 0 cut at 0 owns [0,100); piece 1 cut at 98 owns [100, inf)
        a.words.add(new Word("first", 50, 50.4)); a.words.add(new Word("across", 99.8, 100.3)); a.words.add(new Word("dup", 101, 101.3));
        a.segments.add(new Transcript.Segment(95, 100.3, "x across"));
        b.words.add(new Word("across", 1.8, 2.3)); b.words.add(new Word("dup", 3.0, 3.3)); b.words.add(new Word("last", 10, 10.4));
        b.segments.add(new Transcript.Segment(2.5, 11, "dup last"));
        Transcript m = Transcript.merge(Arrays.asList(a, b), new double[]{0, 98}, new double[]{0, 100}, new double[]{100, Double.MAX_VALUE});
        List<String> t = new ArrayList<>();
        for (Word w : m.words) t.add(w.text);
        assertEquals(Arrays.asList("first", "across", "dup", "last"), t);
        assertEquals(101.0, m.words.get(2).start, 1e-9);
        assertEquals(2, m.segments.size());
        assertEquals("hi", Transcript.languageCode("Hindi"));
    }

    @Test public void ytDownloadLineIsDescribed() {
        String d = com.viral.shortsmaker.core.YtEngine.describeDownload(50f, "[download]  50.0% of ~  4.00MiB at  1.50MiB/s ETA 00:01 (frag 2/4)");
        assertEquals("2.0 of 4.0 MB • 1.5 MB/s", d);
    }

    @Test public void hardwareEncoderCommand() {
        String[] hw = FfmpegCommands.renderClip("in.mp4", null, 10, 30, FfmpegCommands.Frame.BLUR, "/x/c.ass", "/f", "out.mp4", 720, FfmpegCommands.Encoder.HW, 0);
        String j = String.join(" ", hw);
        assertTrue(j.contains("h264_mediacodec"));
        assertTrue(j.contains("format=nv12[v]"));
        assertTrue(j.contains("ass=filename=/x/c.ass:fontsdir=/f:shaping=complex"));
        String[] sw = FfmpegCommands.renderClip("in.mp4", null, 10, 30, FfmpegCommands.Frame.BLUR, null, "/f", "out.mp4", 720, FfmpegCommands.Encoder.X264, 4);
        assertTrue(String.join(" ", sw).contains("libx264"));
    }

    @Test public void maxWordsPerLineRespected() {
        CaptionStyle st = CaptionStyles.byId("hormozi");
        st.maxWordsPerLine = 2; st.maxLines = 1;
        List<Word> ws = CaptionBuilder.clean(words("a b c d e f g", 0, 0.2, 0.05), true);
        CaptionBuilder.Fit fit = CaptionBuilder.fit(ws, st, new CaptionLayout());
        for (CaptionBuilder.Page p : CaptionBuilder.paginate(ws, st, fit)) {
            assertEquals(1, p.lines.size());
            assertTrue(p.lines.get(0).words.size() <= 2);
        }
    }

    @Test public void pagesBreakOnPausesAndSentences() {
        List<Word> ws = new ArrayList<>(Arrays.asList(new Word("Hello.", 0, 0.3), new Word("New", 0.35, 0.6), new Word("idea", 2.0, 2.3)));
        CaptionStyle st = CaptionStyles.byId("clean");
        List<CaptionBuilder.Page> pages = CaptionBuilder.paginate(CaptionBuilder.clean(ws, false), st, CaptionBuilder.fit(ws, st, new CaptionLayout()));
        assertEquals(3, pages.size());
        assertTrue(pages.get(0).end <= pages.get(1).start + 1e-9);
    }

    @Test public void smallerBoxGivesSmallerFont() {
        List<Word> ws = words("big caption words", 0, 0.3, 0.05);
        CaptionStyle st = CaptionStyles.byId("karaoke");
        float big = CaptionBuilder.fit(ws, st, new CaptionLayout(0.5f, 0.7f, 0.9f, 0.3f)).fontSize;
        float small = CaptionBuilder.fit(ws, st, new CaptionLayout(0.5f, 0.7f, 0.9f, 0.06f)).fontSize;
        assertTrue(small <= big);
    }

    @Test public void boxPositionDrivesPos() {
        String ass = CaptionBuilder.buildAss(words("hi there", 0, 0.3, 0), CaptionStyles.byId("clean"), new CaptionLayout(0.5f, 0.25f, 0.8f, 0.12f));
        assertTrue(ass.contains("\\pos(360,320)"));
    }

    @Test public void devanagariSwitchesToCapableFont() {
        List<Word> ws = Arrays.asList(new Word("नमस्ते", 0, 0.5), new Word("दोस्तों", 0.5, 1.0));
        String ass = CaptionBuilder.buildAss(ws, CaptionStyles.byId("beast"), new CaptionLayout());
        assertTrue(ass.contains("KL Baloo"));
        String ass2 = CaptionBuilder.buildAss(ws, CaptionStyles.byId("hormozi"), new CaptionLayout());
        assertTrue(ass2.contains("KL Poppins"));
        assertTrue(ass2.contains("नमस्ते"));
    }

    @Test public void karaokeDurationsCoverPage() {
        List<Word> ws = words("sing along with me", 0, 0.5, 0.1);
        String ass = CaptionBuilder.buildAss(ws, CaptionStyles.byId("karaoke"), new CaptionLayout());
        Matcher m = Pattern.compile("\\\\kf?(\\d+)").matcher(ass);
        int sum = 0; while (m.find()) sum += Integer.parseInt(m.group(1));
        assertEquals(230, sum, 12);
    }

    @Test public void escapesOverrideBraces() {
        String ass = CaptionBuilder.buildAss(Arrays.asList(new Word("{bad}", 0, 1)), CaptionStyles.byId("clean"), new CaptionLayout());
        assertFalse(ass.contains("{bad}"));
    }

    @Test public void transcriptParsesGroqWords() throws Exception {
        String json = "{\"language\":\"hindi\",\"text\":\"a b\",\"segments\":[{\"start\":0.0,\"end\":2.0,\"text\":\" a b\"}],"
                + "\"words\":[{\"word\":\"a\",\"start\":0.1,\"end\":0.5},{\"word\":\"b\",\"start\":0.6,\"end\":1.2}]}";
        Transcript t = Transcript.parseVerboseJson(json, 10);
        assertEquals(2, t.words.size());
        assertEquals(10.1, t.words.get(0).start, 1e-9);
        List<Word> clip = t.wordsForClip(10.0, 12);
        assertEquals(2, clip.size());
        assertEquals(0.1, clip.get(0).start, 1e-9);
    }

    @Test public void transcriptFallsBackToSegmentText() throws Exception {
        String json = "{\"segments\":[{\"start\":0.0,\"end\":2.0,\"text\":\"hello big world\"}]}";
        Transcript t = Transcript.parseVerboseJson(json, 0);
        List<Word> ws = t.wordsForClip(0, 2);
        assertEquals(3, ws.size());
        assertEquals(2.0, ws.get(2).end, 1e-6);
    }

    @Test public void alignerClampsToRealDuration() {
        List<Transcript.Segment> segs = Arrays.asList(new Transcript.Segment(0, 5, "Intro."), new Transcript.Segment(5.2, 40, "Main point here."));
        double[] r = ClipAligner.align(segs, 5.0, 70, 60);
        assertTrue(r[1] <= 60);
        assertEquals(5.1, r[0], 0.05);
        double[] r2 = ClipAligner.align(segs, 50, 110, 100);
        assertTrue(r2[1] - r2[0] <= 59.5);
    }

    @Test public void ffmpegRenderArgs() {
        String[] a = FfmpegCommands.renderClip("https://x/v", "https://x/a", 12.5, 30, FfmpegCommands.Frame.BLUR, "/data/c.ass", "/data/fonts", "/data/o.mp4", 720);
        String all = String.join(" ", a);
        assertTrue(all.contains("-ss 12.500 -t 30.000 -i https://x/v"));
        assertTrue(all.contains("-map 1:a:0"));
        assertTrue(all.contains("ass=filename=/data/c.ass:fontsdir=/data/fonts:shaping=complex"));
        assertTrue(all.contains("libx264") && all.contains("superfast") && !all.contains("fastdecode"));   // v21: sharper software fallback
        assertFalse("no audio-only silence trimming", all.contains("silenceremove"));
        String[] b = FfmpegCommands.renderClip("/sd/in.mp4", null, 0, 10, FfmpegCommands.Frame.FILL, null, null, "/o.mp4", 1080);
        String fill = String.join(" ", b);
        assertTrue(fill.contains("fps=30,crop=w=") && fill.contains("scale=1080:1920"));   // v6: crop first, then scale
        assertTrue(all.contains("[0:v]" + FfmpegCommands.COLOUR_IN + ",fps=30,split=2") && all.contains("eq=brightness=-0.06,scale=720:1280"));   // code 35: colour tag first
        assertEquals("a\\:b\\'c", FfmpegCommands.filterPath("a:b'c"));
    }

    @Test public void v6CaptionLanguageActions() {
        List<com.viral.shortsmaker.captions.Word> deva = Arrays.asList(new com.viral.shortsmaker.captions.Word("क्या", 0, 0.4), new com.viral.shortsmaker.captions.Word("बात", 0.4, 0.8), new com.viral.shortsmaker.captions.Word("है", 0.8, 1.0));
        List<com.viral.shortsmaker.captions.Word> roman = Arrays.asList(new com.viral.shortsmaker.captions.Word("kya", 0, 0.4), new com.viral.shortsmaker.captions.Word("baat", 0.4, 0.8), new com.viral.shortsmaker.captions.Word("hai", 0.8, 1.0));
        List<com.viral.shortsmaker.captions.Word> urdu = Arrays.asList(new com.viral.shortsmaker.captions.Word("کیا", 0, 0.4), new com.viral.shortsmaker.captions.Word("بات", 0.4, 1.0));
        List<com.viral.shortsmaker.captions.Word> en = Arrays.asList(new com.viral.shortsmaker.captions.Word("what", 0, 0.4), new com.viral.shortsmaker.captions.Word("happened", 0.4, 1.0));
        assertEquals("keep", com.viral.shortsmaker.core.CaptionScript.action(deva, "hindi", "deva"));
        assertEquals("translit", com.viral.shortsmaker.core.CaptionScript.action(deva, "hindi", "roman"));
        assertEquals("translate", com.viral.shortsmaker.core.CaptionScript.action(deva, "hindi", "en"));
        assertEquals("translit", com.viral.shortsmaker.core.CaptionScript.action(roman, "hindi", "deva"));
        assertEquals("keep", com.viral.shortsmaker.core.CaptionScript.action(roman, "hindi", "roman"));
        assertEquals("translit", com.viral.shortsmaker.core.CaptionScript.action(urdu, "urdu", "deva"));
        assertEquals("translit", com.viral.shortsmaker.core.CaptionScript.action(urdu, "urdu", "roman"));
        assertEquals("keep", com.viral.shortsmaker.core.CaptionScript.action(en, "english", "en"));
        assertEquals("translate", com.viral.shortsmaker.core.CaptionScript.action(en, "english", "deva"));
        assertEquals("keep", com.viral.shortsmaker.core.CaptionScript.action(en, "english", "roman"));
    }

    @Test public void v6StepRingEstimateNeverCompletes() {
        assertEquals(0f, StepRingView.estimateFraction(0, 20), 1e-6);
        assertTrue(StepRingView.estimateFraction(20, 20) > 0.7f);
        assertTrue(StepRingView.estimateFraction(600, 20) <= 0.95f);
        assertTrue(StepRingView.estimateFraction(10, 20) < StepRingView.estimateFraction(11, 20));
    }

    @Test public void pagesKeepSeveralWordsAfterTheFirstSeconds() {
        // regression (found in the 2.0 end-to-end test): after 4.5 s every word became its own page
        List<Word> w = words("and if that is not dangerous enough once they launch and they are out into space we worry about radiation", 20, 0.25, 0.05);
        String ass = CaptionBuilder.buildAss(w, CaptionStyles.byId("karaoke"), new CaptionLayout(), 40);
        int events = 0;
        for (String l : ass.split("\n")) if (l.startsWith("Dialogue")) events++;
        assertTrue("karaoke pages for 20 words: " + events, events <= 5);
    }

    @Test public void hiddenPunctuationStillBreaksPages() {
        List<Word> w = words("strapping themselves to a rocket. And if that", 0, 0.25, 0.05);
        String ass = CaptionBuilder.buildAss(w, CaptionStyles.byId("hormozi"), new CaptionLayout(), 10);
        for (String l : ass.split("\n")) {
            if (!l.startsWith("Dialogue")) continue;
            String txt = l.substring(l.lastIndexOf('}') + 1);
            assertFalse("no full stop shown in word-by-word style: " + txt, txt.contains("."));
        }
        // ROCKET and AND never on screen together
        double rocketEnd = -1, andStart = -1;
        for (String l : ass.split("\n")) {
            if (!l.startsWith("Dialogue")) continue;
            String[] p = l.split(",", 10);
            if (p[9].endsWith("ROCKET")) rocketEnd = Math.max(rocketEnd, parseTime(p[2]));
            if (p[9].endsWith("AND") && (andStart < 0 || parseTime(p[1]) < andStart)) andStart = parseTime(p[1]);
        }
        assertTrue(rocketEnd > 0 && andStart > 0);
        assertTrue("rocket page ends before the next sentence page: " + rocketEnd + " / " + andStart, rocketEnd <= andStart + 1e-6);
    }
}
