package com.viral.shortsmaker;

import static org.junit.Assert.*;

import com.viral.shortsmaker.captions.*;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v28 caption-style audit: every built-in style x every format (9:16, 1:1, 16:9) x default and dragged-to-the-edge
 * caption boxes, with real font metrics. Geometry, timing and text-cleaning invariants found by rendering every style
 * on the box with libass (see k28cap/REPORT.md).
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class CaptionStylesV28Test {

    static CaptionBuilder.TextMeasurer old;

    @BeforeClass public static void fonts() {
        old = CaptionBuilder.measurer;
        CaptionBuilder.measurer = new AwtMeasurer(new File("src/main/assets/fonts"));
    }

    @AfterClass public static void restore() { CaptionBuilder.measurer = old; }

    static final float[] ASPECTS = {9f / 16f, 1f, 16f / 9f};

    /** default box + boxes dragged to the edges / scaled to the extremes (cx, cy, w, h) */
    static final float[][] LAYOUTS = {null, {0.5f, 0.975f, 0.25f, 0.05f}, {0.5f, 0.02f, 0.25f, 0.05f}, {0f, 0.5f, 0.25f, 0.12f},
            {1f, 1f, 0.5f, 0.1f}, {0.5f, 0f, 1f, 0.1f}, {0.5f, 0.7f, 1f, 0.6f}};

    static List<Word> timed(String text, double start, double per, double gap) {
        List<Word> l = new ArrayList<>();
        double t = start;
        for (String s : text.split(" ")) { l.add(new Word(s, t, t + per)); t += per + gap; }
        return l;
    }

    static List<List<Word>> lists() {
        List<List<Word>> out = new ArrayList<>();
        List<Word> en = timed("So this is UNBELIEVABLY good. CONGRATULATIONS on INTERNATIONALIZATION! You made $1,000,000 in 2024, crazy\uD83D\uDE02\uD83D\uDD25", 0.3, 0.4, 0.02);
        en.addAll(timed("one two three four five six seven eight nine ten", 6.0, 0.12, 0.0));
        en.addAll(timed("wait... what's up?! (really) 100% true", 9.8, 0.35, 0.05));
        out.add(en);
        out.add(timed("\u0915\u094D\u092F\u093E \u0906\u092A \u091C\u093E\u0928\u0924\u0947 \u0939\u0948\u0902 \u0915\u093F \u092F\u0939 \u0938\u091A \u0939\u0948? \u0926\u0941\u0928\u093F\u092F\u093E \u0915\u0940 \u0938\u092C\u0938\u0947 \u092C\u0921\u093C\u0940 \u092A\u094D\u0930\u0924\u093F\u0938\u094D\u092A\u0930\u094D\u0927\u093E \u092E\u0947\u0902 \u0936\u094D\u0930\u0940\u092E\u093E\u0928\u094D \u0928\u0947 100 \u0930\u0941\u092A\u092F\u0947 \u091C\u0940\u0924\u0947", 0.4, 0.33, 0.0));
        out.add(timed("Bhai yeh sach hai, kya aap jaante ho ki paisa kamana mushkil nahi hai! Subscribe karo abhi warna 10,000 log aage nikal jayenge.", 0.25, 0.36, 0.03));
        return out;
    }

    static double t(String s) { String[] p = s.split(":"); return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Double.parseDouble(p[2]); }

    static final Pattern DIALOG = Pattern.compile("Dialogue: (\\d+),([0-9:.]+),([0-9:.]+),(\\w+),,0,0,0,,(.*)");
    static final Pattern POS = Pattern.compile("\\\\pos\\((-?\\d+),(-?\\d+)\\)");
    static final Pattern FS = Pattern.compile("\\\\fs(\\d+)");

    static float popMax(CaptionStyle st) {
        if (st.anim == CaptionStyle.Anim.POP || st.anim == CaptionStyle.Anim.REVEAL) return st.maxWordsPerLine <= 1 ? 1.07f : 1.13f;
        return 1f;
    }

    @Test public void everyStyleEveryFormatStaysInsideTheFrame() {
        int checked = 0;
        for (CaptionStyle st : CaptionStyles.all()) for (float a : ASPECTS) for (float[] lv : LAYOUTS) for (List<Word> ws : lists()) {
            CaptionLayout lay = lv == null ? CaptionLayout.defaultFor(a) : new CaptionLayout(lv[0], lv[1], lv[2], lv[3]);
            lay.aspect = a;
            int W = lay.frameW(), H = lay.frameH();
            String ass = CaptionBuilder.buildAss(ws, st, lay, 14.0);
            Matcher sm = Pattern.compile("Style: Main,([^,]+),(\\d+),").matcher(ass);
            assertTrue(sm.find());
            String fam = sm.group(1);
            int baseFs = Integer.parseInt(sm.group(2));
            CaptionFonts.Font font = null;
            for (CaptionFonts.Font f : CaptionFonts.ALL) if (f.family.equals(fam)) font = f;
            assertNotNull(fam, font);
            String where = st.id + " " + W + "x" + H + " layout " + Arrays.toString(lv);
            for (String line : ass.split("\n")) {
                Matcher d = DIALOG.matcher(line);
                if (!d.matches() || d.group(4).equals("Bar")) continue;
                String txt = d.group(5);
                Matcher p = POS.matcher(txt);
                if (!p.find()) continue;
                int x = Integer.parseInt(p.group(1)), y = Integer.parseInt(p.group(2));
                assertTrue(where + " anchor outside frame: " + line, x >= 0 && x <= W && y >= 0 && y <= H);
                if (txt.contains("\\p1}")) {
                    // vector boxes: rect from the drawing's extent
                    String draw = txt.substring(txt.indexOf("\\p1}") + 4).replaceAll("\\{\\\\p0\\}", "");
                    float maxX = 0, maxY = 0;
                    String[] nums = draw.replaceAll("[mlb]", " ").trim().split("\\s+");
                    for (int i = 0; i + 1 < nums.length; i += 2) { maxX = Math.max(maxX, Float.parseFloat(nums[i])); maxY = Math.max(maxY, Float.parseFloat(nums[i + 1])); }
                    float l, top;
                    if (txt.contains("\\an7")) { l = x; top = y; } else { l = x - maxX / 2f; top = y - maxY / 2f; }
                    assertTrue(where + " box off-frame: " + line, l >= 0 && top >= 0 && l + maxX <= W && top + maxY <= H);
                    continue;
                }
                Matcher f = FS.matcher(txt);
                int fs = f.find() ? Integer.parseInt(f.group(1)) : baseFs;
                String plain = txt.replaceAll("\\{[^}]*\\}", "");
                String[] lines = plain.split("\\\\N");
                float widest = 0;
                for (String s : lines) widest = Math.max(widest, CaptionBuilder.measurer.width(font, s, fs));
                boolean multiWordEvent = lines.length > 1 || plain.contains(" ");
                float grow = multiWordEvent ? 1f : popMax(st);
                float ext = st.outline + (st.glow > 0 ? st.glow * 2.4f : 0f);
                float halfW = widest * grow / 2f + ext, halfH = lines.length * fs * 0.5f * grow + ext + st.shadow;
                assertTrue(where + " text runs off the side (" + (x - halfW) + ".." + (x + halfW) + " of " + W + "): " + line,
                        x - halfW >= -1 && x + halfW <= W + 1);
                assertTrue(where + " text runs off the top/bottom (" + (y - halfH) + ".." + (y + halfH) + " of " + H + "): " + line,
                        y - halfH >= -1 && y + halfH <= H + 1);
                checked++;
            }
        }
        assertTrue(checked > 5000);
    }

    @Test public void pagesNeverOverlapNeverBlinkAndEndByTheClipEnd() {
        List<Word> ws = new ArrayList<>();
        double t = 0.137;
        String[] txt = "so what happened next was honestly the craziest thing I have ever seen in my life and nobody believed me".split(" ");
        double[] gaps = {0, 0.45, 0.013, 0.48, 0.52, 0.027, 0.41, 0, 0.55, 0.0005, 0.6, 0.041, 0.44, 0, 0.5, 0.47, 0.02, 0.9, 0.43, 0};
        for (int i = 0; i < txt.length; i++) { double d = 0.13 + (i % 5) * 0.07; ws.add(new Word(txt[i], t, t + d)); t += d + gaps[i % gaps.length]; }
        double clip = t - 0.15;   // the last word runs past the end of the clip
        for (CaptionStyle st : CaptionStyles.all()) for (float a : ASPECTS) {
            CaptionLayout lay = CaptionLayout.defaultFor(a);
            List<Word> c = CaptionBuilder.clean(ws, st);
            CaptionBuilder.Fit fit = CaptionBuilder.fit(c, st, lay);
            List<CaptionBuilder.Page> pages = CaptionBuilder.paginate(c, st, fit);
            for (int i = 0; i + 1 < pages.size(); i++) {
                double gap = pages.get(i + 1).start - pages.get(i).end;
                assertTrue(st.id + " pages overlap", gap >= -1e-9);
                assertTrue(st.id + " caption blinks off for " + gap + " s between pages", gap < 1e-9 || gap >= 0.25 - 1e-9);
            }
            String ass = CaptionBuilder.buildAss(ws, st, lay, clip);
            Map<String, List<double[]>> byPos = new HashMap<>();
            for (String line : ass.split("\n")) {
                Matcher d = DIALOG.matcher(line);
                if (!d.matches()) continue;
                double s = t(d.group(2)), e = t(d.group(3));
                assertTrue(st.id + " event past the clip end: " + line, e <= clip + 0.006);
                assertTrue(st.id + " empty/negative event: " + line, e > s);
                Matcher p = POS.matcher(d.group(5));
                if (!p.find() || d.group(4).equals("Bar")) continue;
                String key = d.group(1) + "|" + p.group() + "|" + d.group(5).replaceAll("\\{[^}]*\\}", "");
                byPos.computeIfAbsent(key, k -> new ArrayList<>()).add(new double[]{s, e});
            }
            // the same word at the same place on the same layer is never drawn twice at once (no double outline)
            for (Map.Entry<String, List<double[]>> en : byPos.entrySet()) {
                List<double[]> iv = en.getValue();
                iv.sort((x, y) -> Double.compare(x[0], y[0]));
                for (int i = 0; i + 1 < iv.size(); i++)
                    assertTrue(st.id + " overlapping events " + en.getKey(), iv.get(i)[1] <= iv.get(i + 1)[0] + 1e-6);
            }
        }
    }

    @Test public void devanagariIsNeverUppercasedOrGluedAcrossRealWords() {
        CaptionStyle st = CaptionStyles.byId("hormozi");
        assertTrue(st.uppercase);
        List<Word> c = CaptionBuilder.clean(Arrays.asList(new Word("\u0936\u094D\u0930\u0940\u092E\u093E\u0928\u094D", 0, 0.4), new Word("\u0928\u0947", 0.4, 0.6),
                new Word("\u0915\u094D", 0.6, 0.7), new Word("\u092F\u093E", 0.7, 0.9), new Word("bhai", 0.9, 1.2)), st);
        List<String> got = new ArrayList<>();
        for (Word w : c) got.add(w.text);
        // a word-final virama is a real word end ("श्रीमान्" + "ने" stays two words); "क्" + "या" is one word cut in two
        assertEquals(Arrays.asList("\u0936\u094D\u0930\u0940\u092E\u093E\u0928\u094D", "\u0928\u0947", "\u0915\u094D\u092F\u093E", "BHAI"), got);
        // a bare consonant + virama fragment is still joined ("क्" + "या" = "क्या")
        List<Word> f = CaptionBuilder.clean(Arrays.asList(new Word("\u0915\u094D", 0, 0.1), new Word("\u092F\u093E", 0.1, 0.3)), st);
        assertEquals(1, f.size());
        assertEquals("\u0915\u094D\u092F\u093E", f.get(0).text);
        // ALL CAPS leaves Hindi exactly as spoken (Devanagari has no capitals)
        CaptionStyle lower = CaptionStyles.byId("hormozi");
        lower.uppercase = false;
        List<Word> up = CaptionBuilder.clean(lists().get(1), st), lo = CaptionBuilder.clean(lists().get(1), lower);
        assertEquals(lo.size(), up.size());
        for (int i = 0; i < up.size(); i++) assertEquals(lo.get(i).text, up.get(i).text);
    }

    @Test public void emojiAndSymbolsWithoutAGlyphAreRemovedRupeeIsKept() {
        CaptionStyle st = CaptionStyles.byId("beast");
        List<Word> c = CaptionBuilder.clean(Arrays.asList(new Word("\u20B9500", 0, 0.4), new Word("\uD83D\uDE02\uD83D\uDD25", 0.4, 0.6),
                new Word("WOW\u203C", 0.6, 0.9), new Word("\u23F0time", 0.9, 1.2), new Word("\u2197up\u2B06", 1.2, 1.5), new Word("brand\u2122", 1.5, 1.8),
                new Word("1\uFE0F\u20E3", 1.8, 2.0), new Word("\uD83D\uDC4D\uD83C\uDFFD", 2.0, 2.2)), st);
        List<String> t = new ArrayList<>();
        for (Word w : c) t.add(w.text);
        assertEquals(Arrays.asList("\u20B9500", "WOW", "TIME", "UP", "BRAND\u2122", "1"), t);
        String ass = CaptionBuilder.buildAss(Arrays.asList(new Word("fire\uD83D\uDD25", 0, 0.5), new Word("\u23F0", 0.5, 0.8), new Word("now", 0.8, 1.2)), st, new CaptionLayout(), 2);
        for (int i = 0; i < ass.length(); ) {
            int cp = ass.codePointAt(i);
            assertFalse("emoji left in the captions: " + Integer.toHexString(cp), cp >= 0x1F000 || cp == 0x23F0);
            i += Character.charCount(cp);
        }
    }

    @Test public void bracketsStayBalancedInWordByWordStyles() {
        CaptionStyle st = CaptionStyles.byId("hormozi");
        List<Word> c = CaptionBuilder.clean(Arrays.asList(new Word("(really)", 0, 0.4), new Word("(not", 0.4, 0.6), new Word("true)", 0.6, 0.9),
                new Word("[laughs]", 0.9, 1.2), new Word("f(x)", 1.2, 1.5)), st);
        List<String> t = new ArrayList<>();
        for (Word w : c) t.add(w.text);
        assertEquals(Arrays.asList("REALLY", "NOT", "TRUE", "LAUGHS", "F(X)"), t);
        // subtitle looks keep the punctuation as written
        assertEquals("(really)", CaptionBuilder.clean(Arrays.asList(new Word("(really)", 0, 0.4)), CaptionStyles.byId("subtitle")).get(0).text);
    }

    @Test public void longWordsShrinkToFitEveryFormat() {
        for (CaptionStyle st : CaptionStyles.all()) for (float a : ASPECTS) {
            CaptionLayout lay = CaptionLayout.defaultFor(a);
            List<Word> ws = CaptionBuilder.clean(Arrays.asList(new Word("INTERNATIONALIZATION", 0, 0.8), new Word("CONGRATULATIONS", 0.8, 1.6)), st);
            CaptionBuilder.Fit fit = CaptionBuilder.fit(ws, st, lay);
            for (CaptionBuilder.Page p : CaptionBuilder.paginate(ws, st, fit))
                for (Word w : p.allWords()) {
                    float wdt = CaptionBuilder.measurer.width(fit.font, w.text, p.fontSize);
                    assertTrue(st.id + " " + a + " " + w.text + " " + wdt + " > " + fit.boxW, wdt <= fit.boxW + 1);
                }
        }
    }

    @Test public void pillDrawsOneRoundedBoxPerLineAndStackedBoxesDoNotOverlap() {
        CaptionStyle st = CaptionStyles.byId("pill");
        List<Word> ws = lists().get(1);
        for (float a : ASPECTS) {
            CaptionLayout lay = CaptionLayout.defaultFor(a);
            String ass = CaptionBuilder.buildAss(ws, st, lay, 14);
            assertTrue(ass.contains("Style: LBWord,"));
            assertFalse("pill words must not draw libass per-word boxes", ass.matches("(?s).*Dialogue: 1,[^\\n]*,Main,.*"));
            Map<String, List<int[]>> boxesByTime = new HashMap<>();
            int n = 0;
            for (String line : ass.split("\n")) {
                Matcher b = Pattern.compile("Dialogue: 0,([0-9:.]+),([0-9:.]+),LBWord,,0,0,0,,\\{\\\\an7\\\\pos\\((\\d+),(\\d+)\\)").matcher(line);
                if (!b.find()) continue;
                String draw = line.substring(line.indexOf("\\p1}") + 4);
                String[] nums = draw.replaceAll("\\{\\\\p0\\}", "").replaceAll("[mlb]", " ").trim().split("\\s+");
                int h = 0;
                for (int i = 1; i < nums.length; i += 2) h = Math.max(h, Integer.parseInt(nums[i]));
                boxesByTime.computeIfAbsent(b.group(1), k -> new ArrayList<>()).add(new int[]{Integer.parseInt(b.group(4)), h});
                n++;
            }
            assertTrue(n > 0);
            for (List<int[]> bx : boxesByTime.values()) {
                bx.sort((p, q) -> Integer.compare(p[0], q[0]));
                for (int i = 0; i + 1 < bx.size(); i++) assertTrue("line boxes overlap", bx.get(i)[0] + bx.get(i)[1] <= bx.get(i + 1)[0]);
            }
        }
    }

    @Test public void karaokeSweepStartsOnEachSpokenWord() {
        CaptionStyle st = CaptionStyles.byId("karaoke");
        List<Word> ws = new ArrayList<>();
        double tt = 0.213;
        for (int i = 0; i < 40; i++) { double d = 0.117 + (i % 7) * 0.0413; ws.add(new Word("w" + i, tt, tt + d)); tt += d + (i % 9 == 0 ? 0.517 : 0.0137); }
        List<Word> c = CaptionBuilder.clean(ws, st);
        String ass = CaptionBuilder.buildAss(ws, st, CaptionLayout.defaultFor(9f / 16f), tt);
        int wi = 0;
        for (String line : ass.split("\n")) {
            Matcher d = DIALOG.matcher(line);
            if (!d.matches() || !d.group(1).equals("1")) continue;
            double cur = t(d.group(2));
            Matcher k = Pattern.compile("\\{\\\\(kf?)(\\d+)\\}").matcher(d.group(5));
            while (k.find()) {
                if (k.group(1).equals("kf")) { assertEquals("word " + wi, c.get(wi).start, cur, 0.035); wi++; }
                cur += Integer.parseInt(k.group(2)) / 100.0;
            }
        }
        assertEquals(c.size(), wi);
    }
}
