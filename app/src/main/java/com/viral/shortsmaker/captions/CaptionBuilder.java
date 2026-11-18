package com.viral.shortsmaker.captions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns word timestamps + a style + the user's caption box into an ASS subtitle script for libass.
 * Pure Java (no Android) so it is unit-tested and also runs on desktop for the demo render.
 *
 * v3: every word is laid out with real font metrics (see {@link TextMeasurer}) and positioned on its own,
 * so the highlighted word can pop without shifting its neighbours, nothing overflows the box, back-to-back
 * words never fade (no blinking), and captions never run past the end of the clip.
 */
public final class CaptionBuilder {
    public static final int W = 720, H = 1280;

    /** Measures text the way libass will draw it. The app installs a real (font-file) measurer. */
    public interface TextMeasurer {
        /** advance width in script pixels of {@code text} drawn with ASS font size {@code assSize} */
        float width(CaptionFonts.Font font, String text, float assSize);
    }

    /** Fallback when no font files are available: average glyph advance per font. */
    public static final TextMeasurer HEURISTIC = (f, t, s) -> visualLength(t) * f.widthFactor * s;
    public static volatile TextMeasurer measurer = HEURISTIC;

    public static final class Line {
        public final List<Word> words = new ArrayList<>();
    }

    public static final class Page {
        public final List<Line> lines = new ArrayList<>();
        public double start, end;
        /** font size for this page (shrunk only when a word would not fit the box) */
        public float fontSize;

        public List<Word> allWords() {
            List<Word> out = new ArrayList<>();
            for (Line l : lines) out.addAll(l.words);
            return out;
        }
    }

    public static final class Fit {
        public float fontSize;
        public int maxChars;
        public int maxLines;
        public float boxW, boxH;
        public CaptionFonts.Font font;
    }

    private CaptionBuilder() {}

    // ------------------------------------------------------------------ text helpers

    static boolean isCombining(char c) {
        return (c >= 0x0900 && c <= 0x0903) || (c >= 0x093A && c <= 0x094F) || (c >= 0x0951 && c <= 0x0957)
                || (c >= 0x0962 && c <= 0x0963) || (c >= 0x0300 && c <= 0x036F) || c == 0x200C || c == 0x200D;
    }

    /** approximate number of visible glyph cells */
    public static int visualLength(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (!isCombining(s.charAt(i))) n++;
        return n;
    }

    static boolean endsSentence(String t) {
        return t.endsWith(".") || t.endsWith("?") || t.endsWith("!") || t.endsWith("\u0964") || t.endsWith("\u0965");
    }

    static boolean endsClause(String t) {
        return t.endsWith(",") || t.endsWith(";") || t.endsWith(":") || t.endsWith("-");
    }

    static boolean isPunctOnly(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c) || (c >= 0x0900 && c <= 0x097F && c != 0x0964 && c != 0x0965)) return false;
        }
        return true;
    }

    /** removes emoji / pictographs (no caption font has them; libass would draw empty boxes) */
    static String stripEmoji(String t) {
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            boolean emoji = cp >= 0x1F000 || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x2B00 && cp <= 0x2BFF)
                    || cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || (cp >= 0xE000 && cp <= 0xF8FF) || isPictoSymbol(cp);
            if (cp == 0x200D) {
                // keep ZWJ only inside Devanagari (conjunct control)
                boolean prevDeva = sb.length() > 0 && sb.charAt(sb.length() - 1) >= 0x0900 && sb.charAt(sb.length() - 1) <= 0x097F;
                emoji = !prevDeva;
            }
            if (!emoji) sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    /**
     * v28: emoji-style symbols below U+1F000 that none of the bundled caption fonts has (checked against every
     * kl_*.ttf cmap): on the phone libass only sees the app's font folder, so these were drawn as empty boxes
     * (‼ ⁉ ℹ ↔ ↗ ↩ ⌚ ⏰ ⏩ Ⓜ ▪ ▶ ◀ ◻ ⤴ 〰 〽 ㊗ ㊙ ...). © ® ™ ₹ and ordinary punctuation are kept.
     */
    static boolean isPictoSymbol(int cp) {
        return cp == 0x203C || cp == 0x2049 || cp == 0x2139 || (cp >= 0x2194 && cp <= 0x2199) || (cp >= 0x21A9 && cp <= 0x21AA)
                || (cp >= 0x231A && cp <= 0x231B) || cp == 0x2328 || cp == 0x23CF || (cp >= 0x23E9 && cp <= 0x23F3)
                || (cp >= 0x23F8 && cp <= 0x23FA) || cp == 0x24C2 || (cp >= 0x25AA && cp <= 0x25AB) || cp == 0x25B6 || cp == 0x25C0
                || (cp >= 0x25FB && cp <= 0x25FE) || (cp >= 0x2934 && cp <= 0x2935) || cp == 0x3030 || cp == 0x303D
                || cp == 0x3297 || cp == 0x3299;
    }

    /** word-by-word looks read cleaner without trailing commas/full stops ("SPACE," -> "SPACE") */
    static String stripSoftPunct(String t) {
        String s = t;
        while (s.length() > 1) {
            char c = s.charAt(s.length() - 1);
            if (c == ',' || c == '.' || c == ';' || c == ':' || c == '\u0964' || c == '\u0965' || c == '"' || c == '\u201D' || c == '\u2026') s = s.substring(0, s.length() - 1);
            else break;
        }
        while (s.length() > 1) {
            char c = s.charAt(0);
            if (c == '"' || c == '\u201C' || c == '\'' || c == '(' || c == '[') s = s.substring(1);
            else break;
        }
        // v28: "(really)" became "REALLY)" - the opening bracket was dropped but the closing one kept. A closing
        // bracket with no opening one in the same word goes too (also the end of a bracketed phrase: "true)").
        while (s.length() > 1) {
            char c = s.charAt(s.length() - 1);
            if ((c == ')' && s.indexOf('(') < 0) || (c == ']' && s.indexOf('[') < 0)) s = s.substring(0, s.length() - 1);
            else break;
        }
        return s;
    }

    static boolean wordByWord(CaptionStyle st) {
        return st.anim != CaptionStyle.Anim.STATIC && st.anim != CaptionStyle.Anim.KARAOKE;
    }

    public static List<Word> clean(List<Word> in, boolean uppercase) {
        CaptionStyle st = new CaptionStyle();
        st.uppercase = uppercase;
        st.anim = CaptionStyle.Anim.STATIC;
        return clean(in, st);
    }

    /**
     * Normalises Whisper words: trims, removes emoji, glues Devanagari fragments (a word that starts with a
     * vowel sign / virama, or follows a dangling virama) and punctuation-only tokens back onto the previous word,
     * and makes the timeline strictly monotonic with no overlaps.
     */
    public static List<Word> clean(List<Word> in, CaptionStyle st) {
        List<Word> out = new ArrayList<>();
        if (in == null) return out;
        for (Word w : in) {
            if (w == null || w.text == null) continue;
            String t = stripEmoji(w.text.replace('\n', ' ').replace('\r', ' ')).trim();
            if (t.isEmpty()) continue;
            double s = Double.isNaN(w.start) ? 0 : Math.max(0, w.start);
            double e = Double.isNaN(w.end) ? s : w.end;
            if (!out.isEmpty()) {
                Word p = out.get(out.size() - 1);
                // v28: a word that ENDS in a virama is only a fragment when it is a bare consonant (+ one more), like
                // "क्" + "या"; real words end in a virama too ("श्रीमान्", "महान्", "अर्थात्") and were glued onto the next
                // word ("श्रीमान् ने" was drawn as "श्रीमान्ने")
                boolean viramaFragment = p.text.endsWith("\u094D") && visualLength(p.text) <= 2;
                boolean glue = isCombining(t.charAt(0)) || viramaFragment || isPunctOnly(t);
                if (glue && (s - p.end) < 0.6 && p.phrase == w.phrase) {
                    Word g = new Word(p.text + t, p.start, Math.max(p.end, e));
                    g.phrase = p.phrase;
                    out.set(out.size() - 1, g);
                    continue;
                }
            }
            if (isPunctOnly(t) && out.isEmpty()) continue;
            Word nw0 = new Word(t, s, Math.max(e, s + 0.05));
            nw0.phrase = w.phrase;
            nw0.sentenceEnd = w.sentenceEnd;
            out.add(nw0);
        }
        List<Word> fin = new ArrayList<>();
        boolean strip = wordByWord(st);
        for (int i = 0; i < out.size(); i++) {
            Word c = out.get(i);
            String t = c.text;
            boolean sEnd = endsSentence(t) || (c.phrase >= 0 && c.sentenceEnd);
            if (strip) t = stripSoftPunct(t);
            if (st.uppercase) t = st.upperLocale != null ? t.toUpperCase(Locale.forLanguageTag(st.upperLocale)) : t.toUpperCase(Locale.ROOT);
            double s = c.start, e = c.end;
            if (!fin.isEmpty()) {
                Word p = fin.get(fin.size() - 1);
                boolean samePhrase = c.phrase >= 0 && p.phrase == c.phrase;
                if (!samePhrase) {   // v9: words of one translated phrase share its time; everything else is strictly ordered
                    // code 32: the earlier word keeps a readable slice (up to 0.12 s). 4.9 cut it to 0.02 s when Whisper gave the
                    // next word the same / an earlier start, and a page that short was skipped: the word never showed
                    double minS = p.start + Math.min(0.12, Math.max(0.02, p.end - p.start));
                    if (s < minS) s = minS;
                    if (p.end > s) fin.set(fin.size() - 1, p.retimed(p.start, s));
                }
            }
            Word nw = new Word(t, s, Math.max(e, s + 0.05));
            nw.sentenceEnd = sEnd;
            nw.phrase = c.phrase;
            fin.add(nw);
        }
        return fin;
    }

    // ------------------------------------------------------------------ measuring

    static float measure(CaptionFonts.Font f, String t, float size) {
        try {
            float w = measurer.width(f, t, size);
            if (w > 0 && !Float.isNaN(w)) return w;
        } catch (Throwable ignored) {}
        return HEURISTIC.width(f, t, size);
    }

    static float spaceW(CaptionFonts.Font f, float size) {
        return Math.max(size * 0.22f, measure(f, "a a", size) - 2 * measure(f, "a", size));
    }

    static float popScale(CaptionStyle st, boolean single) {
        if (st.anim == CaptionStyle.Anim.POP) return single ? 1.0f : 1.08f;
        if (st.anim == CaptionStyle.Anim.REVEAL) return 1.06f;
        return 1f;
    }

    // ------------------------------------------------------------------ layout

    public static Fit fit(List<Word> words, CaptionStyle st, CaptionLayout lay) {
        StringBuilder sample = new StringBuilder();
        for (Word w : words) sample.append(w.text).append(' ');
        Fit f = new Fit();
        f.font = st.scriptFont != null && !st.scriptFont.isEmpty() ? CaptionFonts.get(st.scriptFont) : CaptionFonts.resolve(st.fontKey, sample);
        float pad = Math.max(st.outline, st.lineBox ? st.outline : 0) + 10f;
        int W = lay.frameW(), H = lay.frameH();   // v14: per format
        f.boxW = Math.max(80f, Math.min(lay.w * W, W - 24f) - 2 * pad);
        f.boxH = Math.max(40f, lay.h * H);
        // v28: a caption box dragged to full width let a popping word (up to 113 %) or the glow run off the frame edge
        float edge = 8 + Math.max(st.outline, Math.max(st.lineBox ? Math.max(4f, st.outline) : 0f, st.glow > 0 ? st.outline + st.glow * 2.4f : 0f));
        f.boxW = Math.max(80f, Math.min(f.boxW, (W - 2 * edge) / popMax(st)));
        float fs = st.fontSize * st.sizeScale * lay.fontScale();
        // v8: Devanagari letters are drawn much smaller than Latin capitals at the same font size (हिंदी captions looked
        // about half the size of Hinglish ones): give Hindi text 30 % more size; the fit below still keeps it inside the box
        if (CaptionFonts.containsDevanagari(sample)) fs *= 1.3f;
        else fs *= f.font.sizeBoost;   // v44: the other scripts' fonts draw smaller at the same size
        int lines = Math.max(1, st.maxLines);
        float byHeight = f.boxH / (lines * 1.18f);
        if (lines > 1 && byHeight < fs * 0.62f) { lines = 1; byHeight = f.boxH / 1.18f; }
        fs = Math.min(fs, byHeight);
        fs = Math.max(fs, 22f);
        f.fontSize = fs;
        f.maxLines = lines;
        f.maxChars = Math.max(4, (int) Math.floor(f.boxW / (fs * f.font.widthFactor)));
        return f;
    }

    public static List<Page> paginate(List<Word> words, CaptionStyle st, Fit fit) {
        List<Page> pages = new ArrayList<>();
        Page page = null;
        Line line = null;
        float lineW = 0;
        float fs = fit.fontSize;
        float sp = st.spaced ? spaceW(fit.font, fs) : 0f;
        float grow = popScale(st, st.maxWordsPerLine <= 1);
        double pageStart = 0;   // v3 bug: compared with page.start, which is only set after paging, so every word after 4.5 s became its own page
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            Word prev = i > 0 ? words.get(i - 1) : null;
            float ww = measure(fit.font, w.text, fs) * grow;
            boolean inPhrase = w.phrase >= 0 && prev != null && prev.phrase == w.phrase;
            boolean forceNewPage = page == null
                    || (prev != null && w.phrase != prev.phrase && (w.phrase >= 0 || prev.phrase >= 0))
                    || (!inPhrase && prev != null && (w.start - prev.end) > 0.75)
                    || (!inPhrase && prev != null && (prev.sentenceEnd || endsSentence(prev.text)))
                    || (!inPhrase && page != null && (w.end - pageStart) > 4.5);
            if (!forceNewPage) {
                // v9: a translated phrase stays on ONE page (its own time); it may use up to 3 lines and ignores the
                // words-per-line limit of word-by-word looks
                boolean lineFull = (!inPhrase && line.words.size() >= st.maxWordsPerLine)
                        || lineW + sp + ww > fit.boxW
                        || (!inPhrase && prev != null && endsClause(prev.text) && line.words.size() >= 2);
                if (lineFull) {
                    if (page.lines.size() >= (inPhrase ? Math.max(3, fit.maxLines) : fit.maxLines)) forceNewPage = true;
                    else { line = new Line(); page.lines.add(line); lineW = 0; }
                }
            }
            if (forceNewPage) {
                page = new Page();
                pageStart = w.start;
                page.fontSize = fs;
                pages.add(page);
                line = new Line();
                page.lines.add(line);
                lineW = 0;
            }
            line.words.add(w);
            lineW += (line.words.size() == 1 ? 0 : sp) + ww;
        }
        // per-page shrink so the widest line fits the box (never overflows the frame)
        for (Page pg : pages) {
            float widest = 0;
            for (Line l : pg.lines) widest = Math.max(widest, lineWidth(l, fit.font, fs, st));
            if (widest > fit.boxW) pg.fontSize = Math.max(16f, fs * fit.boxW / widest);
        }
        // timing
        for (int p = 0; p < pages.size(); p++) {
            Page pg = pages.get(p);
            List<Word> ws = pg.allWords();
            pg.start = ws.get(0).start;
            double lastEnd = ws.get(ws.size() - 1).end;
            double next = p + 1 < pages.size() ? pages.get(p + 1).allWords().get(0).start : Double.MAX_VALUE;
            double end = (next - lastEnd) < 0.4 ? next : Math.min(lastEnd + 0.35, next);
            // v28: a pause of 0.4-0.6 s left a 0.05-0.25 s blank between two pages: the caption blinked off for a few
            // frames and straight back on. Short gaps are bridged; real pauses still clear the screen.
            if (p + 1 < pages.size() && next - end < MIN_BLANK) end = next;
            pg.end = Math.max(end, pg.start + 0.2);
            if (p + 1 < pages.size()) pg.end = Math.min(pg.end, next);
        }
        return pages;
    }

    /** v28: largest scale a word reaches while it pops in (POP: 108 % + 4 % overshoot; single word 106 %) */
    static float popMax(CaptionStyle st) {
        if (st.anim == CaptionStyle.Anim.POP || st.anim == CaptionStyle.Anim.REVEAL) return st.maxWordsPerLine <= 1 ? 1.07f : 1.13f;
        return 1f;
    }

    /** v28: height of a line box (Pill) and the line pitch: boxes of stacked lines sit a small gap apart, never overlap */
    static float lbBoxH(float fs, CaptionStyle st) { return fs + 2f * Math.round(Math.max(4f, st.outline) * 0.6f); }

    static float lbPitch(float fs, CaptionStyle st) { return lbBoxH(fs, st) + Math.max(2f, Math.round(fs * 0.06f)); }

    static float maxOf(float[] a) { float m = 0; for (float v : a) m = Math.max(m, v); return m; }

    static float plainWidth(Line l, CaptionFonts.Font font, float fs, boolean spaced) {
        float sp = spaced ? spaceW(font, fs) : 0f, w = 0;
        for (int k = 0; k < l.words.size(); k++) w += (k == 0 ? 0 : sp) + measure(font, l.words.get(k).text, fs);
        return w;
    }

    /** v28: the shortest blank between two caption pages (shorter reads as a blink) */
    static final double MIN_BLANK = 0.25;

    static float lineWidth(Line l, CaptionFonts.Font font, float fs, CaptionStyle st) {
        float sp = st.spaced ? spaceW(font, fs) : 0f, w = 0;
        float grow = popScale(st, l.words.size() <= 1);
        for (int k = 0; k < l.words.size(); k++) w += (k == 0 ? 0 : sp) + measure(font, l.words.get(k).text, fs) * (k == 0 && l.words.size() == 1 ? 1f : grow);
        return w;
    }

    // ------------------------------------------------------------------ ASS output

    public static String assColor(int argb) {
        int a = 255 - ((argb >>> 24) & 0xFF);
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return String.format(Locale.US, "&H%02X%02X%02X%02X", a, b, g, r);
    }

    static String tagColor(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return String.format(Locale.US, "&H%02X%02X%02X&", b, g, r);
    }

    public static String time(double sec) {
        if (sec < 0) sec = 0;
        long cs = Math.round(sec * 100.0);
        long h = cs / 360000; cs -= h * 360000;
        long m = cs / 6000; cs -= m * 6000;
        long s = cs / 100; cs -= s * 100;
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs);
    }

    /** ASS-safe text: no override blocks, no escape sequences (\N \h \n), no hard line breaks */
    static String esc(String t) {
        return t.replace("\\", "\uFF3C").replace("{", "(").replace("}", ")").replace("\n", " ").replace("\r", " ");
    }

    private static final int[] CYCLE = {0xFF3DFF5A, 0xFFFFE500, 0xFFFF3B5C, 0xFF38BDF8};

    // ------------------------------------------------------------------ v10: key words

    static final java.util.Set<String> STRONG = new java.util.HashSet<>(java.util.Arrays.asList(
            "money", "paisa", "paise", "crore", "crores", "lakh", "lakhs", "million", "billion", "dollar", "dollars", "rupees", "rupaye",
            "secret", "never", "always", "best", "worst", "free", "viral", "truth", "mistake", "mistakes", "success", "fail", "failure",
            "biggest", "first", "last", "only", "every", "nobody", "everyone", "important", "danger", "shocking", "real", "love",
            "kabhi", "nahi", "sabse", "zaroor", "sach", "galti", "pehla", "pehli", "sirf", "kamaal", "khatarnak",
            "पैसा", "पैसे", "करोड़", "लाख", "कभी", "नहीं", "सबसे", "ज़रूर", "जरूर", "सच", "गलती", "ग़लती", "पहला", "पहली", "सिर्फ", "सिर्फ़", "कमाल", "खतरनाक"));

    static String plainKey(String t) {
        return t == null ? "" : t.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s\u0964\u0965]+", "");
    }

    /**
     * v10 keyword highlight: numbers, money words and strong words get an accent colour (like Submagic / OpusClip),
     * at most one word in every 4 so the effect stays special. Pure function (unit-tested).
     */
    public static java.util.Set<Word> emphasis(List<Word> words) {
        java.util.Set<Word> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int last = -10;
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            if (w.phrase >= 0) continue;
            String k = plainKey(w.text);
            if (k.isEmpty()) continue;
            boolean digit = k.matches(".*[0-9\u0966-\u096F].*");
            boolean strong = STRONG.contains(k);
            if ((digit || strong) && i - last >= 4) { out.add(w); last = i; }
        }
        return out;
    }

    /** accent colour for key words: a colour that differs from the highlight */
    public static int accent(CaptionStyle st) {
        int h = st.highlightColor & 0xFFFFFF;
        return h == 0x3DFF5A || h == 0x22C55E || h == 0x00FF00 ? 0xFFFFE500 : 0xFF3DFF5A;
    }

    public static String buildAss(List<Word> rawWords, CaptionStyle st, CaptionLayout lay) {
        return buildAss(rawWords, st, lay, -1);
    }

    /** @param clipDuration seconds; when > 0 no caption is shown past the end of the clip */
    public static String buildAss(List<Word> rawWords, CaptionStyle st, CaptionLayout lay, double clipDuration) {
        if (lay == null) lay = new CaptionLayout();
        List<Word> words = clean(rawWords, st);
        if (clipDuration > 0) {
            List<Word> in = new ArrayList<>();
            for (Word w : words) if (w.start < clipDuration - 0.08) in.add(w.retimed(w.start, Math.min(w.end, clipDuration)));
            words = in;
        }
        Fit fit = fit(words, st, lay);
        List<Page> pages = words.isEmpty() ? new ArrayList<Page>() : paginate(words, st, fit);
        if (clipDuration > 0) for (Page pg : pages) pg.end = Math.min(pg.end, clipDuration);
        java.util.Set<Word> emph = st.keywords ? emphasis(words) : java.util.Collections.<Word>emptySet();
        String accTag = "\\c" + tagColor(accent(st));

        final int W = lay.frameW(), H = lay.frameH();   // v14: the ASS canvas follows the output format
        StringBuilder sb = new StringBuilder(16384);
        sb.append("[Script Info]\nScriptType: v4.00+\nPlayResX: ").append(W).append("\nPlayResY: ").append(H)
                .append("\nWrapStyle: 2\nScaledBorderAndShadow: yes\nYCbCr Matrix: TV.709\n\n");
        sb.append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n");
        String fam = fit.font.family;
        int fs = Math.round(fit.fontSize);
        boolean karaoke = st.anim == CaptionStyle.Anim.KARAOKE;
        String primary = assColor(karaoke ? st.highlightColor : st.textColor);
        String secondary = assColor(karaoke ? st.textColor : st.highlightColor);
        float outline = st.outline * fit.fontSize / Math.max(1f, st.fontSize * st.sizeScale);
        outline = Math.max(st.outline > 0 ? 1.5f : 0f, Math.min(st.outline, outline + 0.5f));
        float shadow = st.shadow;
        if (st.lineBox) {
            sb.append(style("Main", fam, fs, primary, secondary, assColor(st.boxColor), assColor(st.boxColor), 3, Math.max(4f, st.outline), 0));
        } else {
            sb.append(style("Main", fam, fs, primary, secondary, assColor(st.outlineColor), assColor(st.shadowColor), 1, outline, shadow));
        }
        if (st.lineBox && wordByWord(st)) {
            // v28: line-box looks with per-word events (Pill) get ONE rounded box per line drawn below the words. The
            // libass BorderStyle-3 box was drawn per word: neighbouring boxes overlapped into darker seams and the two
            // lines' boxes overlapped into a dark band.
            sb.append(style("LBWord", fam, fs, primary, secondary, assColor(st.boxColor), assColor(0x00000000), 1, 0f, 0f));
        }
        sb.append(style("Box", fam, fs, assColor(st.textColor), secondary, assColor(st.highlightColor), assColor(0x00000000), 3, Math.max(5f, fs * 0.12f), 0));
        sb.append(style("Bar", fam, 20, assColor(0xFFFFFFFF), secondary, assColor(0xFF000000), assColor(0x00000000), 1, 0f, 0));
        sb.append("\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");

        int px = Math.round(lay.cx * W), py = Math.round(lay.cy * H);
        boolean lbBox = st.lineBox && wordByWord(st);
        String wst = lbBox ? "LBWord" : "Main";
        float glowExt = st.glow > 0 ? st.glow * 2.4f : 0f;   // \bord(outline + 1.4 glow) + \blur(glow)
        float boxPad = st.lineBox ? Math.max(4f, st.outline) : 0f;
        float edgeH = 8 + Math.max(outline, Math.max(boxPad, (st.glow > 0 ? st.outline : 0) + glowExt));
        float edgeV = 6 + Math.max(outline + st.shadow, Math.max(boxPad, (st.glow > 0 ? st.outline : 0) + glowExt));
        int wordCounter = 0;
        double prevEnd = -1;
        for (Page pg : pages) {
            boolean afterGap = prevEnd < 0 || pg.start - prevEnd > 0.2;
            prevEnd = pg.end;
            if (pg.end - pg.start < 0.04) continue;
            List<Word> ws = pg.allWords();
            int n = ws.size();
            String fsTag = Math.round(pg.fontSize) != fs ? "\\fs" + Math.round(pg.fontSize) : "";
            boolean phrasePage = ws.get(0).phrase >= 0;
            // v28: keep the whole page (outline, shadow, glow, pop, highlight box, Hindi matras) inside the frame when the
            // caption box was dragged to an edge: the text was centred on the box with no vertical limit (cut off at the
            // top/bottom edge) and the side limit ignored the glow and the line box
            boolean wbw = !phrasePage && st.anim != CaptionStyle.Anim.STATIC && !karaoke;
            int nlines = pg.lines.size();
            float plh = wbw ? (lbBox ? lbPitch(pg.fontSize, st) : pg.fontSize * 1.16f) : pg.fontSize;
            float halfH = (nlines - 1) * plh / 2f + pg.fontSize * (st.anim == CaptionStyle.Anim.BOX_WORD ? 0.56f : 0.5f) * (wbw ? popMax(st) : 1f) + edgeV;
            int ppy = Math.round(Math.max(halfH, Math.min(H - halfH, py)));
            if (halfH * 2 > H) ppy = H / 2;
            int ppx = px;
            if (!wbw) {
                float widest = 0;
                for (Line l : pg.lines) widest = Math.max(widest, plainWidth(l, fit.font, pg.fontSize, st.spaced));
                float halfW = widest / 2f + edgeH;
                if (halfW * 2 <= W) ppx = Math.round(Math.max(halfW, Math.min(W - halfW, px)));
            }
            if (phrasePage) {
                // v9: a translated phrase is shown whole, in the text colour, for exactly the phrase's spoken time
                String pos = "\\an5\\pos(" + ppx + "," + ppy + ")\\q2" + fsTag + "\\1c" + tagColor(st.textColor);
                String head = "{" + pos + (st.entrance && afterGap ? "\\fad(80,0)" : "") + "}";
                if (st.glow > 0) sb.append(dialog(0, pg.start, pg.end, "Main", "{" + pos + glowTags(st) + "}" + plainLines(pg, st.spaced)));
                sb.append(dialog(1, pg.start, pg.end, "Main", head + plainLines(pg, st.spaced)));
                wordCounter += n;
                continue;
            }
            if (st.anim == CaptionStyle.Anim.STATIC || karaoke) {
                String text = karaoke ? karaokeText(pg, st.spaced) : plainLines(pg, st.spaced);
                String pos = "\\an5\\pos(" + ppx + "," + ppy + ")\\q2" + fsTag;
                String head = "{" + pos + (st.entrance && afterGap ? "\\fad(80,0)" : "") + "}";
                if (st.glow > 0) sb.append(dialog(0, pg.start, pg.end, "Main", "{" + pos + glowTags(st) + "}" + stripTags(text)));
                sb.append(dialog(1, pg.start, pg.end, "Main", head + text));
                wordCounter += n;
                continue;
            }
            // ---- word-by-word looks: every word is its own positioned event
            float pfs = pg.fontSize;
            float sp = st.spaced ? spaceW(fit.font, pfs) : 0f;
            float lh = lbBox ? lbPitch(pfs, st) : pfs * 1.16f;
            boolean single = n == 1;
            float grow = popScale(st, single);
            int nl = pg.lines.size();
            int idx = 0;
            for (int li = 0; li < nl; li++) {
                Line l = pg.lines.get(li);
                float y = ppy - (nl - 1) * lh / 2f + li * lh;
                float[] widths = new float[l.words.size()];
                float total = 0;
                for (int k = 0; k < widths.length; k++) {
                    widths[k] = measure(fit.font, l.words.get(k).text, pfs);
                    total += widths[k] * (single ? 1f : grow) + (k == 0 ? 0 : sp);
                }
                float x = px - total / 2f;
                float popPad = (popMax(st) - 1f) * maxOf(widths) / 2f;
                float minX = edgeH + popPad, maxX = W - edgeH - popPad;
                if (x < minX) x = minX;
                if (x + total > maxX) x = Math.max(minX, maxX - total);
                if (lbBox) {
                    // one rounded box per line, from the page's first to last frame, below the words (layer 0)
                    float hp = Math.max(4f, st.outline);
                    float bh = lbBoxH(pfs, st);
                    int bl = Math.round(x - hp), bt = Math.round(y - bh / 2f);
                    int bw = Math.round(x + total + hp) - bl, bhi = Math.round(bh);
                    int a = 255 - ((st.boxColor >>> 24) & 0xFF);
                    sb.append(dialog(0, pg.start, pg.end, "LBWord", "{\\an7\\pos(" + bl + "," + bt + ")" + (st.entrance && afterGap ? "\\fad(60,0)" : "")
                            + "\\bord0\\shad0\\1c" + tagColor(st.boxColor) + String.format(Locale.US, "\\1a&H%02X&", a) + "\\p1}"
                            + roundedRect(bw, bhi, bhi * BOX_RADIUS) + "{\\p0}"));
                }
                // v44: right-to-left scripts (Arabic, Urdu) read from the right: the first word sits at the right end of the line
                float[] cxArr = new float[widths.length];
                float cur = x;
                for (int v = 0; v < widths.length; v++) {
                    int kk = st.rtl ? widths.length - 1 - v : v;
                    float cw = widths[kk] * (single ? 1f : grow);
                    cxArr[kk] = cur + cw / 2f;
                    cur += cw + sp;
                }
                for (int k = 0; k < widths.length; k++, idx++) {
                    float cellW = widths[k] * (single ? 1f : grow);
                    float cx = cxArr[k];
                    x += cellW + sp;
                    Word w = l.words.get(k);
                    double as = idx == 0 ? pg.start : ws.get(idx).start;
                    // v10: the highlight follows the word's real spoken duration: a slow word stays lit longer; across a
                    // real pause (> 0.3 s after the word ends, or the page's closing hold) it goes out 0.12 s after the sound instead of waiting.
                    double nextS = idx + 1 < n ? ws.get(idx + 1).start : pg.end;
                    double ae = nextS - w.end > 0.3 ? Math.min(nextS, Math.max(w.end, ws.get(idx).start + 0.1) + 0.12) : nextS;
                    as = Math.max(pg.start, Math.min(as, pg.end));
                    ae = Math.max(as, Math.min(ae, pg.end));
                    int hl = st.colorCycle ? CYCLE[(wordCounter + idx) % CYCLE.length] : st.highlightColor;
                    String pos = "\\an5\\pos(" + Math.round(cx) + "," + Math.round(y) + ")" + fsTag;
                    String ent = st.entrance && afterGap ? "\\fad(60,0)" : "";
                    String t = esc(w.text);
                    boolean reveal = st.anim == CaptionStyle.Anim.REVEAL;
                    // base (not active): before the word is spoken (unless reveal) and after it
                    String em = emph.contains(w) ? accTag : "";
                    if (!reveal && as - pg.start >= 0.02) word(sb, st, 1, pg.start, as, wst, "{" + pos + ent + em + "}" + t);
                    if (pg.end - ae >= 0.02) word(sb, st, 1, ae, pg.end, wst, "{" + pos + em + "}" + t);
                    // active
                    if (ae - as >= 0.02) {
                        String act;
                        if (st.anim == CaptionStyle.Anim.BOX_WORD) {
                            word(sb, st, 1, as, ae, "Main", "{" + pos + (as == pg.start ? ent : "") + "}" + t);
                            // v12: a ROUNDED highlight box (libass vector drawing, corner radius ~28 % of its height)
                            // behind the word, then the word on top in the text colour; same start/end = same sync
                            float[] bx = boxGeom(w.text, widths[k], pfs, fit.font);
                            sb.append(dialog(2, as, ae, "Main", "{\\an5\\pos(" + Math.round(cx) + "," + Math.round(y + bx[2]) + ")\\bord0\\shad0\\blur0.6\\1c"
                                    + tagColor(hl) + "\\1a&H00&\\p1}" + roundedRect(bx[0], bx[1], bx[1] * BOX_RADIUS) + "{\\p0}"));
                            sb.append(dialog(3, as, ae, "Main", "{" + pos + "\\bord0\\shad0\\1c" + tagColor(st.textColor) + "}" + t));
                            continue;
                        }
                        act = "\\c" + tagColor(hl);
                        if (st.anim == CaptionStyle.Anim.POP || reveal) {
                            if (single) act += "\\fscx78\\fscy78\\t(0,70,\\fscx106\\fscy106)\\t(70,130,\\fscx100\\fscy100)";
                            else {
                                int g = Math.round(grow * 100);
                                act += "\\fscx92\\fscy92\\t(0,70," + "\\fscx" + (g + 4) + "\\fscy" + (g + 4) + ")\\t(70,130,\\fscx" + g + "\\fscy" + g + ")";
                            }
                        }
                        word(sb, st, 1, as, ae, wst, "{" + pos + (as == pg.start ? ent : "") + act + "}" + t);
                    }
                }
            }
            wordCounter += n;
        }
        double total = clipDuration > 0 ? clipDuration : (pages.isEmpty() ? 0 : pages.get(pages.size() - 1).end);
        appendExtras(sb, st, total, fam, W, H);
        return sb.toString();
    }

    /** v10 progress bar, drawn above the captions (v11: the 3 s hook title was removed) */
    static void appendExtras(StringBuilder sb, CaptionStyle st, double total, String fam) { appendExtras(sb, st, total, fam, 720, 1280); }

    static void appendExtras(StringBuilder sb, CaptionStyle st, double total, String fam, int W, int H) {
        if (total <= 0.5) return;
        if (st.progressBar) {
            int ms = (int) Math.round(total * 1000);
            String rect = "m 0 0 l " + W + " 0 l " + W + " 10 l 0 10";
            sb.append(dialog(5, 0, total, "Bar", "{\\an7\\pos(0," + (H - 10) + ")\\p1\\1c&H000000&\\1a&H99&}" + rect + "{\\p0}"));
            sb.append(dialog(6, 0, total, "Bar", "{\\an7\\pos(0," + (H - 10) + ")\\p1\\1c" + tagColor(st.highlightColor)
                    + "\\clip(0,0,0," + H + ")\\t(0," + ms + ",\\clip(0,0," + W + "," + H + "))}" + rect + "{\\p0}"));
        }
    }

    private static void word(StringBuilder sb, CaptionStyle st, int layer, double s, double e, String style, String text) {
        if (st.glow > 0) {
            int b = text.indexOf('}');
            String tags = text.substring(1, b), t = text.substring(b + 1);
            sb.append(dialog(0, s, e, style, "{" + tags.replaceAll("\\\\c&H[0-9A-F]{6}&", "") + glowTags(st) + "}" + t));
        }
        sb.append(dialog(layer, s, e, style, text));
    }

    private static String style(String name, String fam, int fs, String pri, String sec, String out, String back,
                                int border, float outline, float shadow) {
        return String.format(Locale.US, "Style: %s,%s,%d,%s,%s,%s,%s,0,0,0,0,100,100,0,0,%d,%.1f,%.1f,5,20,20,20,1\n",
                name, fam, fs, pri, sec, out, back, border, outline, shadow);
    }

    private static String dialog(int layer, double s, double e, String style, String text) {
        return "Dialogue: " + layer + "," + time(s) + "," + time(e) + "," + style + ",,0,0,0,," + text + "\n";
    }

    private static String glowTags(CaptionStyle st) {
        return "\\bord" + Math.round(st.outline + st.glow * 1.4f) + "\\blur" + Math.round(st.glow) + "\\1a&HFF&\\shad0";
    }

    private static String stripTags(String t) { return t.replaceAll("\\{[^}]*\\}", ""); }

    private static String plainLines(Page pg, boolean spaced) {
        StringBuilder sb = new StringBuilder();
        for (int li = 0; li < pg.lines.size(); li++) {
            if (li > 0) sb.append("\\N");
            List<Word> lw = pg.lines.get(li).words;
            for (int k = 0; k < lw.size(); k++) {
                if (k > 0 && spaced) sb.append(' ');
                sb.append(esc(lw.get(k).text));
            }
        }
        return sb.toString();
    }

    private static String karaokeText(Page pg, boolean spaced) {
        StringBuilder sb = new StringBuilder();
        List<Word> ws = pg.allWords();
        int idx = 0;
        double cursor = pg.start;
        for (int li = 0; li < pg.lines.size(); li++) {
            if (li > 0) sb.append("\\N");
            List<Word> lw = pg.lines.get(li).words;
            for (int k = 0; k < lw.size(); k++) {
                Word w = lw.get(k);
                double until = idx + 1 < ws.size() ? ws.get(idx + 1).start : Math.min(pg.end, w.end + 0.1);
                if (until - w.end > 0.3) until = Math.max(w.start + 0.1, w.end + 0.12);   // v10: real duration across a pause
                if (w.start - cursor > 0.04) sb.append("{\\k").append(Math.round((w.start - cursor) * 100)).append('}');
                long cs = Math.max(1, Math.round((until - Math.max(cursor, w.start)) * 100));
                if (k > 0 && spaced) sb.append(' ');
                sb.append("{\\kf").append(cs).append('}').append(esc(w.text));
                cursor = Math.max(cursor, until);
                idx++;
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ v12 rounded highlight box

    /** corner radius as a share of the highlight box height (Sakib: no sharp square corners) */
    public static final float BOX_RADIUS = 0.28f;

    /**
     * Ink extents of each bundled font, measured from libass renders at \\an5 (fractions of the font size, + = down):
     * {capTop, baseline, descender, devanagari top incl. matras, devanagari bottom incl. matras}.
     */
    static final java.util.Map<String, float[]> INK = new java.util.HashMap<>();
    static {
        INK.put("KL Montserrat", new float[]{-0.288f, 0.288f, 0.45f, -0.30f, 0.30f});
        INK.put("KL Poppins", new float[]{-0.262f, 0.138f, 0.288f, -0.45f, 0.338f});
        INK.put("KL Poppins Medium", new float[]{-0.25f, 0.138f, 0.288f, -0.438f, 0.325f});
        INK.put("KL Anton", new float[]{-0.188f, 0.30f, 0.375f, -0.20f, 0.30f});
        INK.put("KL Bebas", new float[]{-0.312f, 0.212f, 0.225f, -0.32f, 0.22f});
        INK.put("KL Baloo", new float[]{-0.225f, 0.162f, 0.275f, -0.412f, 0.362f});
        INK.put("KL Noto Deva", new float[]{-0.162f, 0.20f, 0.325f, -0.262f, 0.338f});
        INK.put("KL Noto Sans", new float[]{-0.230f, 0.240f, 0.372f, -0.230f, 0.240f});
        INK.put("KL Noto Bengali", new float[]{-0.234f, 0.209f, 0.352f, -0.234f, 0.209f});
        INK.put("KL Noto Tamil", new float[]{-0.374f, 0.202f, 0.363f, -0.374f, 0.202f});
        INK.put("KL Noto Telugu", new float[]{-0.246f, 0.173f, 0.309f, -0.246f, 0.173f});
        INK.put("KL Noto Gujarati", new float[]{-0.174f, 0.225f, 0.360f, -0.174f, 0.225f});
        INK.put("KL Noto Kannada", new float[]{-0.248f, 0.172f, 0.293f, -0.248f, 0.172f});
        INK.put("KL Noto Malayalam", new float[]{-0.380f, 0.193f, 0.353f, -0.380f, 0.193f});
        INK.put("KL Noto Gurmukhi", new float[]{-0.290f, 0.187f, 0.340f, -0.290f, 0.187f});
        INK.put("KL Noto Arabic", new float[]{-0.169f, 0.160f, 0.252f, -0.169f, 0.160f});
        INK.put("KL Noto Thai", new float[]{-0.270f, 0.202f, 0.335f, -0.270f, 0.202f});
        INK.put("KL Noto JP", new float[]{-0.205f, 0.301f, 0.439f, -0.205f, 0.301f});
        INK.put("KL Noto KR", new float[]{-0.205f, 0.301f, 0.439f, -0.205f, 0.301f});
        INK.put("KL Noto SC", new float[]{-0.205f, 0.301f, 0.439f, -0.205f, 0.301f});
    }

    /** {width, height, vertical offset of the box centre from the \\an5 text centre} of a word's highlight box */
    public static float[] boxGeom(String text, float wordW, float fs, CaptionFonts.Font font) {
        float[] ink = font != null ? INK.get(font.family) : null;
        if (ink == null) ink = INK.get("KL Poppins");
        boolean deva = text != null && CaptionFonts.containsDevanagari(text);
        float top, bot;
        if (deva) { top = ink[3] - 0.08f; bot = ink[4] + 0.08f; }
        else { top = ink[0] - 0.13f; bot = Math.max(ink[1] + 0.13f, ink[2] + 0.04f); }
        float h = (bot - top) * fs;
        float w = wordW + fs * 0.18f;
        return new float[]{w, h, (top + bot) / 2f * fs};
    }

    /** ASS \\p1 drawing of a w x h rounded rectangle (top-left at 0,0), corners as cubic bezier quarter circles */
    public static String roundedRect(float w, float h, float r) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2f));
        float k = r * 0.4477f;   // 1 - 0.5523: control point offset for a circular quarter arc
        StringBuilder b = new StringBuilder();
        b.append("m ").append(f1(r)).append(' ').append(f1(0));
        b.append(" l ").append(f1(w - r)).append(' ').append(f1(0));
        b.append(" b ").append(f1(w - k)).append(' ').append(f1(0)).append(' ').append(f1(w)).append(' ').append(f1(k)).append(' ').append(f1(w)).append(' ').append(f1(r));
        b.append(" l ").append(f1(w)).append(' ').append(f1(h - r));
        b.append(" b ").append(f1(w)).append(' ').append(f1(h - k)).append(' ').append(f1(w - k)).append(' ').append(f1(h)).append(' ').append(f1(w - r)).append(' ').append(f1(h));
        b.append(" l ").append(f1(r)).append(' ').append(f1(h));
        b.append(" b ").append(f1(k)).append(' ').append(f1(h)).append(' ').append(f1(0)).append(' ').append(f1(h - k)).append(' ').append(f1(0)).append(' ').append(f1(h - r));
        b.append(" l ").append(f1(0)).append(' ').append(f1(r));
        b.append(" b ").append(f1(0)).append(' ').append(f1(k)).append(' ').append(f1(k)).append(' ').append(f1(0)).append(' ').append(f1(r)).append(' ').append(f1(0));
        return b.toString();
    }

    private static String f1(float v) { return String.valueOf(Math.round(v)); }
}
