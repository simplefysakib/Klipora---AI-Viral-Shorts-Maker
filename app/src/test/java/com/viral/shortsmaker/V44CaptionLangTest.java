package com.viral.shortsmaker;

import static org.junit.Assert.*;

import com.viral.shortsmaker.captions.CaptionBuilder;
import com.viral.shortsmaker.captions.CaptionFonts;
import com.viral.shortsmaker.captions.CaptionLangs;
import com.viral.shortsmaker.captions.CaptionLayout;
import com.viral.shortsmaker.captions.CaptionStyle;
import com.viral.shortsmaker.captions.CaptionStyles;
import com.viral.shortsmaker.captions.Word;
import com.viral.shortsmaker.core.CaptionScript;
import com.viral.shortsmaker.core.Transcript;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * v44 (5.7): the 29 caption languages. Per language: the sample line builds, its ASS builds with the language's own
 * font, the font has a glyph for every letter of the sample, the words are cut for the spaceless scripts, and the
 * burned frame (host ffmpeg, the same ass filter the phone runs) draws pixels. Frames are kept in build/shots/v44.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V44CaptionLangTest {
    private static final File FONTS = new File("src/main/assets/fonts");

    /** every setting in the picker: Hinglish, हिंदी, English and the 26 others */
    private static List<String> everySetting() {
        List<String> out = new ArrayList<>(Arrays.asList(CaptionScript.HINGLISH, CaptionScript.HINDI, CaptionScript.ENGLISH));
        for (CaptionLangs.L l : CaptionLangs.all()) out.add(l.script());
        return out;
    }

    /** the sample line's words: the preview's own sample for Hinglish / हिंदी / English, the language's line otherwise */
    private static String[] sampleWords(String script) {
        if (!CaptionScript.isLang(script)) {
            List<Word> w = CaptionStudioActivity.sampleFor(script);
            String[] out = new String[w.size()];
            for (int i = 0; i < out.length; i++) out[i] = w.get(i).text;
            return out;
        }
        return CaptionLangs.sample(script).split(" ");
    }

    private static List<Word> timedWords(String script) {
        List<Word> out = new ArrayList<>();
        String[] parts = sampleWords(script);
        double t = 0.2;
        for (String s : parts) { double d = 0.30 + s.length() * 0.05; out.add(new Word(s, t, t + d)); t += d + 0.06; }
        return CaptionLangs.splitUnits(out, script);
    }

    @Test public void versionIsFiftyFourFiveSeven() {
        assertEquals(45, BuildConfig.VERSION_CODE);
        assertEquals("5.8", BuildConfig.VERSION_NAME);
    }

    @Test public void twentyNineSettingsAndTheirSamples() {
        assertEquals(29, everySetting().size());
        assertEquals(26, CaptionLangs.all().size());
        for (String s : everySetting()) {
            assertNotNull(s, CaptionScript.valid(s));
            assertFalse("sample for " + s, CaptionLangs.of(s) != null && CaptionLangs.sample(s).trim().isEmpty());
            assertTrue(CaptionScript.label(s) != null && !CaptionScript.label(s).isEmpty());
        }
        assertNull(CaptionScript.valid("lang:xx"));
        assertNull(CaptionScript.valid(null));
        assertEquals("lang:bn", CaptionLangs.byCode("bn").script());
        assertEquals("Bengali", CaptionScript.label("lang:bn"));
    }

    @Test public void everyLanguageHasItsOwnFontOrTheStyleFontDrawsIt() {
        for (CaptionLangs.L l : CaptionLangs.all()) {
            assertTrue(l.code + " sample is in its own letters", CaptionLangs.inScript(l.code, l.sample));
            assertTrue(l.code + " prompt", l.prompt != null && l.prompt.length() > 3);
            if (l.font.isEmpty()) continue;
            CaptionFonts.Font f = CaptionFonts.get(l.font);
            assertEquals(l.code + " font key", l.font, f.key);
            assertTrue(l.code + " font file is bundled", new File(FONTS, f.asset).isFile());
        }
    }

    /** the glyph coverage of a TrueType file (cmap format 4 and 12) */
    static java.util.Set<Integer> cmap(File f) throws Exception {
        byte[] b = Files.readAllBytes(f.toPath());
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b);
        int num = bb.getShort(4) & 0xFFFF;
        int cmapOff = -1;
        for (int i = 0; i < num; i++) {
            int rec = 12 + i * 16;
            if (new String(b, rec, 4, "ISO-8859-1").equals("cmap")) cmapOff = bb.getInt(rec + 8);
        }
        java.util.Set<Integer> out = new java.util.HashSet<>();
        if (cmapOff < 0) return out;
        int nsub = bb.getShort(cmapOff + 2) & 0xFFFF;
        for (int i = 0; i < nsub; i++) {
            int sub = cmapOff + bb.getInt(cmapOff + 4 + i * 8 + 4);
            int fmt = bb.getShort(sub) & 0xFFFF;
            if (fmt == 4) {
                int segX2 = bb.getShort(sub + 6) & 0xFFFF, seg = segX2 / 2;
                int ends = sub + 14, starts = ends + segX2 + 2, deltas = starts + segX2, ranges = deltas + segX2;
                for (int s = 0; s < seg; s++) {
                    int end = bb.getShort(ends + s * 2) & 0xFFFF, start = bb.getShort(starts + s * 2) & 0xFFFF;
                    int delta = bb.getShort(deltas + s * 2) & 0xFFFF, ro = bb.getShort(ranges + s * 2) & 0xFFFF;
                    for (int c = start; c <= end && c != 0xFFFF; c++) {
                        int g;
                        if (ro == 0) g = (c + delta) & 0xFFFF;
                        else {
                            int gi = ranges + s * 2 + ro + (c - start) * 2;
                            g = bb.getShort(gi) & 0xFFFF;
                            if (g != 0) g = (g + delta) & 0xFFFF;
                        }
                        if (g != 0) out.add(c);
                    }
                }
            } else if (fmt == 12) {
                int groups = bb.getInt(sub + 12);
                for (int g = 0; g < groups; g++) {
                    int gs = sub + 16 + g * 12;
                    int s0 = bb.getInt(gs), e0 = bb.getInt(gs + 4);
                    for (int c = s0; c <= e0; c++) out.add(c);
                }
            }
        }
        return out;
    }

    @Test public void theFontsDrawEveryLetterOfTheSampleLines() throws Exception {
        for (CaptionLangs.L l : CaptionLangs.all()) {
            String font = l.font.isEmpty() ? null : l.font;
            // Latin languages use the caption style's own font: check the four Latin families they could be set in
            String[] files = font != null ? new String[]{CaptionFonts.get(font).asset}
                    : new String[]{"kl_montserrat.ttf", "kl_poppins.ttf", "kl_anton.ttf", "kl_bebas.ttf"};
            for (String asset : files) {
                java.util.Set<Integer> cm = cmap(new File(FONTS, asset));
                String sample = l.sample;
                for (int i = 0; i < sample.length(); ) {
                    int cp = sample.codePointAt(i);
                    i += Character.charCount(cp);
                    if (cp == ' ' || cp == '!' || cp == ',' || cp == '.' || cp == '?' || cp == '\'' || cp == '-') continue;
                    if (font == null && (l.code.equals("vi") || l.code.equals("ru") || l.code.equals("uk"))) continue;
                    assertTrue(l.code + " letter U+" + Integer.toHexString(cp) + " in " + asset, cm.contains(cp));
                }
            }
        }
    }

    @Test public void eachLanguageBuildsItsAssWithItsOwnFont() {
        for (String script : everySetting()) {
            CaptionStyle st = CaptionScript.styleFor(script, CaptionStyles.byId("hormozi"));
            List<Word> words = timedWords(script);
            assertFalse(script + " has words", words.isEmpty());
            String ass = CaptionBuilder.buildAss(words, st, new CaptionLayout(), 6.0);
            assertTrue(script, ass.contains("[Events]") && ass.contains("Dialogue:"));
            CaptionFonts.Font expect = CaptionLangs.of(script) == null ? null : (CaptionLangs.fontFor(script).isEmpty() ? null : CaptionFonts.get(CaptionLangs.fontFor(script)));
            if (expect != null) assertTrue(script + " names its font " + expect.family, ass.contains("," + expect.family + ","));
            // every letter of the sample reaches the ASS text (word pieces joined without spaces for CJK / Thai)
            String joined = letters(ass);
            for (Word w : words) assertTrue(script + " keeps '" + w.text + "'", joined.contains(letters(w.text)));
            if (CaptionLangs.spaceless(script)) {
                String all = String.join("", sampleWords(script));
                StringBuilder got = new StringBuilder();
                for (Word w : words) got.append(w.text);
                assertEquals(script + " loses no letter when cut", all, got.toString());
            }
        }
    }

    /** the letters and digits of a text, upper case, without tags, line breaks, marks or punctuation */
    static String letters(String s) {
        String t = s.replaceAll("\\{[^}]*\\}", "").replace("\\N", " ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(Character.toUpperCase(cp));
        }
        return sb.toString();
    }

    @Test public void spacelessLanguagesCutIntoWordsWithoutLosingTime() {
        for (String script : Arrays.asList("lang:ja", "lang:zh", "lang:th")) {
            List<Word> in = Arrays.asList(new Word(CaptionLangs.sample(script), 1.0, 4.0));
            List<Word> cut = CaptionLangs.splitUnits(in, script);
            assertTrue(script + " is cut into several words", cut.size() > 2);
            StringBuilder sb = new StringBuilder();
            double last = 0;
            for (Word w : cut) {
                assertTrue(script + " piece is not empty", !w.text.isEmpty());
                assertTrue(script + " timing goes forward", w.start >= last - 1e-9 && w.end >= w.start);
                last = w.end;
                sb.append(w.text);
            }
            assertEquals(script + " ends with the word", 4.0, cut.get(cut.size() - 1).end, 1e-6);
            assertEquals(script + " keeps every character", CaptionLangs.sample(script).replace(" ", ""), sb.toString());
        }
        // Thai vowels and tone marks stay with the consonant before them
        for (Word w : CaptionLangs.splitUnits(Arrays.asList(new Word("ภาษาไทยน่าเรียนมาก", 0, 2)), "lang:th")) {
            int first = w.text.codePointAt(0);
            boolean mark = (first >= 0x0E30 && first <= 0x0E3A) || (first >= 0x0E45 && first <= 0x0E4E);
            assertFalse("no piece starts with a vowel or tone mark: " + w.text, mark);
        }
        // Japanese small kana stay with the kana before them
        for (Word w : CaptionLangs.splitUnits(Arrays.asList(new Word("\u304d\u3087\u3046\u306f\u3044\u3044\u5929\u6c17", 0, 2)), "lang:ja")) {
            int first = w.text.codePointAt(0);
            assertFalse("no piece starts with a small kana: " + w.text, first == 0x3087 || first == 0x3063);
        }
        // spaced languages are not touched
        List<Word> es = Arrays.asList(new Word("hola", 0, 1));
        assertSame(es, CaptionLangs.splitUnits(es, "lang:es"));
    }

    @Test public void translationIsOnlyAskedWhenTheSpeechIsNotInTheLanguage() {
        List<Word> words = Arrays.asList(new Word("hello", 0, 0.5), new Word("there", 0.5, 1));
        assertEquals("translate", CaptionScript.action(words, "english", "lang:bn"));
        assertEquals("keep", CaptionScript.action(words, "english", CaptionScript.ENGLISH));
        List<Word> bn = Arrays.asList(new Word("\u0986\u09aa\u09a8\u09be\u09b0", 0, 0.5));
        assertEquals("keep", CaptionScript.action(bn, "bengali", "lang:bn"));
        assertEquals("translate", CaptionScript.action(bn, "english", "lang:bn"));
        assertEquals("keep", CaptionScript.action(words, "spanish", "lang:es"));
    }

    @Test public void translationAnswersAreCheckedAgainstTheLanguagesLetters() {
        assertTrue(CaptionLangs.inScript("bn", "\u0986\u09aa\u09a8\u09be\u09b0 \u0995\u09cd\u09af\u09be\u09aa"));
        assertFalse(CaptionLangs.inScript("bn", "this is english"));
        assertTrue(CaptionLangs.inScript("ar", "\u0647\u0643\u0630\u0627 \u0633\u062a"));
        assertFalse(CaptionLangs.inScript("ja", "hello world"));
        assertTrue(CaptionLangs.inScript("ja", "\u5b57\u5e55\u306f\u3053\u3046"));
        assertTrue(CaptionLangs.inScript("es", "anything at all"));
    }

    @Test public void rightToLeftAndCaseFlags() {
        assertTrue(CaptionLangs.rtl("lang:ar"));
        assertTrue(CaptionLangs.rtl("lang:ur"));
        assertFalse(CaptionLangs.rtl("lang:bn"));
        assertFalse(CaptionLangs.of("lang:ja").cased);
        assertTrue(CaptionLangs.of("lang:ru").cased);
        assertTrue(CaptionLangs.of("lang:es").cased);
        assertEquals("tr", CaptionScript.styleFor("lang:tr", CaptionStyles.byId("hormozi")).upperLocale);
        assertTrue(CaptionScript.styleFor("lang:ja", CaptionStyles.byId("hormozi")).maxWordsPerLine >= 8);
        assertFalse(CaptionScript.styleFor("lang:ja", CaptionStyles.byId("hormozi")).spaced);
        // Hinglish / Hindi / English keep the style exactly
        CaptionStyle base = CaptionStyles.byId("hormozi");
        assertSame(base, CaptionScript.styleFor(CaptionScript.HINGLISH, base));
    }

    /**
     * host ffmpeg burns each language's ASS with the bundled fonts (the same ass filter the phone runs); the frame must
     * show letters: grey-scale pixels brighter than 150 are counted (the background is about 48). A PNG of each frame is
     * kept in build/shots/v44 for a look.
     */
    @Test public void burnedFramesDrawEveryLanguage() throws Exception {
        String ff = firstExisting("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg");
        Assume.assumeTrue("ffmpeg on this host", ff != null);
        File out = new File("build/shots/v44");
        out.mkdirs();
        StringBuilder report = new StringBuilder();
        for (String script : everySetting()) {
            CaptionStyle st = CaptionScript.styleFor(script, CaptionStyles.byId("hormozi"));
            String ass = CaptionBuilder.buildAss(timedWords(script), st, new CaptionLayout(), 6.0);
            String code = CaptionLangs.codeOf(script) == null ? script : CaptionLangs.codeOf(script);
            File assF = new File(out, "cap_" + code + ".ass");
            Files.write(assF.toPath(), ass.getBytes("UTF-8"));
            String vf = "ass=" + assF.getAbsolutePath() + ":fontsdir=" + FONTS.getAbsolutePath();
            // 1) the raw grey frame, counted here
            byte[] raw = run(new String[]{ff, "-v", "error", "-f", "lavfi", "-i", "color=c=0x203060:s=720x1280:d=3",
                    "-vf", vf + ",format=gray", "-ss", "1.2", "-frames:v", "1", "-f", "rawvideo", "-"});
            int bright = 0;
            for (byte b : raw) if ((b & 0xFF) > 150) bright++;
            // 2) the same frame as a PNG for a look
            run(new String[]{ff, "-v", "error", "-y", "-f", "lavfi", "-i", "color=c=0x203060:s=720x1280:d=3",
                    "-vf", vf, "-ss", "1.2", "-frames:v", "1", new File(out, "frame_" + code + ".png").getAbsolutePath()});
            report.append(code).append(' ').append(bright).append('\n');
            assertTrue(script + " draws letters (" + bright + " bright pixels)", bright > 400);
        }
        Files.write(new File(out, "burn_report.txt").toPath(), report.toString().getBytes("UTF-8"));
    }

    static byte[] run(String[] cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).start();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        java.io.InputStream in = p.getInputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        String err = new String(p.getErrorStream().readAllBytes(), "UTF-8");
        assertEquals("ffmpeg: " + err, 0, p.waitFor());
        return bo.toByteArray();
    }

    static String firstExisting(String... paths) {
        for (String p : paths) if (new File(p).canExecute()) return p;
        return null;
    }
}
