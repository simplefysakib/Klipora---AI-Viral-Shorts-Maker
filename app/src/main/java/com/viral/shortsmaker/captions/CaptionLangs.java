package com.viral.shortsmaker.captions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v44 (5.7): the caption languages beyond Hinglish / हिंदी / English, the same 26 that Capify AI offers. One entry per
 * language: its font (bundled, draws the script), whether it is written right to left, whether its words are spaced
 * (Japanese, Chinese and Thai are not), whether it has letter case (ALL CAPS only where it does), the name the AI is
 * asked to translate into, and a sample line for the preview. Pure Java: no Android types (unit tested on the desktop).
 *
 * The caption setting stores these as "lang:<code>" (for example "lang:bn").
 */
public final class CaptionLangs {
    public static final String PREFIX = "lang:";

    public static final class L {
        public final String code, english, nativeName, font, prompt, sample;
        public final boolean rtl, spaced, cased;
        /** spaceless scripts: how many letters make one caption word (CJK), Thai: how many clusters */
        public final int chunk;
        /** Unicode ranges (inclusive pairs) that the language's own letters use; empty = no check (Latin) */
        final int[] ranges;
        L(String code, String english, String nativeName, String font, boolean rtl, boolean spaced, boolean cased,
          int chunk, String prompt, int[] ranges, String sample) {
            this.code = code; this.english = english; this.nativeName = nativeName; this.font = font; this.rtl = rtl;
            this.spaced = spaced; this.cased = cased; this.chunk = chunk; this.prompt = prompt; this.ranges = ranges; this.sample = sample;
        }
        public String script() { return PREFIX + code; }
    }

    private static final List<L> ALL = new ArrayList<>();
    private static final Map<String, L> BY = new HashMap<>();
    private static final int[] NONE = new int[0];
    private static final int[] CYR = {0x0400, 0x04FF}, ARA = {0x0600, 0x06FF, 0x0750, 0x077F, 0xFB50, 0xFDFF, 0xFE70, 0xFEFF},
            DEV = {0x0900, 0x097F}, BEN = {0x0980, 0x09FF}, GUR = {0x0A00, 0x0A7F}, GUJ = {0x0A80, 0x0AFF}, TAM = {0x0B80, 0x0BFF},
            TEL = {0x0C00, 0x0C7F}, KAN = {0x0C80, 0x0CFF}, MAL = {0x0D00, 0x0D7F}, THA = {0x0E00, 0x0E7F},
            HAN = {0x4E00, 0x9FFF, 0x3400, 0x4DBF}, KANA = {0x3040, 0x30FF}, HANGUL = {0xAC00, 0xD7AF, 0x1100, 0x11FF};

    private static void add(String code, String en, String nat, String font, boolean rtl, boolean spaced, boolean cased,
                            int chunk, String prompt, int[] ranges, String sample) {
        L l = new L(code, en, nat, font, rtl, spaced, cased, chunk, prompt, ranges, sample);
        ALL.add(l); BY.put(code, l);
    }

    static {
        // Latin letters (es, fr, de, pt, it, nl, tr, id, pl) use the caption style's own font: it draws them.
        add("es", "Spanish", "Espa\u00f1ol", "", false, true, true, 0, "Spanish", NONE, "As\u00ed se ver\u00e1n tus subt\u00edtulos");
        add("fr", "French", "Fran\u00e7ais", "", false, true, true, 0, "French", NONE, "Voici \u00e0 quoi ressembleront vos sous-titres");
        add("de", "German", "Deutsch", "", false, true, true, 0, "German", NONE, "So sehen deine Untertitel aus");
        add("pt", "Portuguese", "Portugu\u00eas", "", false, true, true, 0, "Portuguese (Brazil)", NONE, "Assim ficam as suas legendas");
        add("it", "Italian", "Italiano", "", false, true, true, 0, "Italian", NONE, "Ecco come appariranno i tuoi sottotitoli");
        add("nl", "Dutch", "Nederlands", "", false, true, true, 0, "Dutch", NONE, "Zo zien je ondertitels eruit");
        add("tr", "Turkish", "T\u00fcrk\u00e7e", "", false, true, true, 0, "Turkish", NONE, "Altyaz\u0131lar\u0131n b\u00f6yle g\u00f6r\u00fcnecek");
        add("id", "Indonesian", "Bahasa Indonesia", "", false, true, true, 0, "Indonesian", NONE, "Beginilah tampilan teksmu");
        add("pl", "Polish", "Polski", "", false, true, true, 0, "Polish", NONE, "Tak b\u0119d\u0105 wygl\u0105da\u0107 Twoje napisy");
        add("vi", "Vietnamese", "Ti\u1ebfng Vi\u1ec7t", "noto_sans", false, true, true, 0, "Vietnamese", NONE, "Ph\u1ee5 \u0111\u1ec1 c\u1ee7a b\u1ea1n s\u1ebd nh\u01b0 th\u1ebf n\u00e0y");
        add("ru", "Russian", "\u0420\u0443\u0441\u0441\u043a\u0438\u0439", "noto_sans", false, true, true, 0, "Russian (Cyrillic script)", CYR,
                "\u0412\u043e\u0442 \u0442\u0430\u043a \u0431\u0443\u0434\u0443\u0442 \u0432\u044b\u0433\u043b\u044f\u0434\u0435\u0442\u044c \u0432\u0430\u0448\u0438 \u0441\u0443\u0431\u0442\u0438\u0442\u0440\u044b");
        add("uk", "Ukrainian", "\u0423\u043a\u0440\u0430\u0457\u043d\u0441\u044c\u043a\u0430", "noto_sans", false, true, true, 0, "Ukrainian (Cyrillic script)", CYR,
                "\u041e\u0441\u044c \u044f\u043a \u0432\u0438\u0433\u043b\u044f\u0434\u0430\u0442\u0438\u043c\u0443\u0442\u044c \u0432\u0430\u0448\u0456 \u0441\u0443\u0431\u0442\u0438\u0442\u0440\u0438");
        add("ar", "Arabic", "\u0627\u0644\u0639\u0631\u0628\u064a\u0629", "noto_arabic", true, true, false, 0, "Arabic (Arabic script)", ARA,
                "\u0647\u0643\u0630\u0627 \u0633\u062a\u0638\u0647\u0631 \u062a\u0631\u062c\u0645\u062a\u0643");
        add("ur", "Urdu", "\u0627\u0631\u062f\u0648", "noto_arabic", true, true, false, 0, "Urdu (Arabic script, Nastaliq-style Urdu wording)", ARA,
                "\u0622\u067e \u06a9\u06d2 \u06a9\u06cc\u067e\u0634\u0646\u0632 \u0627\u0633 \u0637\u0631\u062d \u062f\u06a9\u06be\u0627\u0626\u06cc \u062f\u06cc\u06ba \u06af\u06d2");
        add("ja", "Japanese", "\u65e5\u672c\u8a9e", "noto_jp", false, false, false, 2, "Japanese (Japanese script, kanji and kana)", new int[]{0x3040, 0x30FF, 0x4E00, 0x9FFF},
                "\u5b57\u5e55\u306f\u3053\u3046\u8868\u793a\u3055\u308c\u307e\u3059");
        add("ko", "Korean", "\ud55c\uad6d\uc5b4", "noto_kr", false, true, false, 0, "Korean (Hangul)", HANGUL,
                "\uc790\ub9c9\uc774 \uc774\ub807\uac8c \ud45c\uc2dc\ub429\ub2c8\ub2e4");
        add("zh", "Chinese", "\u4e2d\u6587", "noto_sc", false, false, false, 2, "Chinese (Simplified Chinese characters)", HAN,
                "\u4f60\u7684\u5b57\u5e55\u5c06\u8fd9\u6837\u663e\u793a");
        add("th", "Thai", "\u0e44\u0e17\u0e22", "noto_thai", false, false, false, 3, "Thai (Thai script)", THA,
                "\u0e04\u0e33\u0e1a\u0e23\u0e23\u0e22\u0e32\u0e22\u0e08\u0e30\u0e2d\u0e22\u0e39\u0e48\u0e41\u0e1a\u0e1a\u0e19\u0e35\u0e49");
        add("bn", "Bengali", "\u09ac\u09be\u0982\u09b2\u09be", "noto_bengali", false, true, false, 0, "Bengali (Bengali script)", BEN,
                "\u0986\u09aa\u09a8\u09be\u09b0 \u0995\u09cd\u09af\u09be\u09aa\u09b6\u09a8 \u098f\u09ad\u09be\u09ac\u09c7 \u09a6\u09c7\u0996\u09be\u09ac\u09c7");
        add("ta", "Tamil", "\u0ba4\u0bae\u0bbf\u0bb4\u0bcd", "noto_tamil", false, true, false, 0, "Tamil (Tamil script)", TAM,
                "\u0b89\u0b99\u0bcd\u0b95\u0bb3\u0bcd \u0b95\u0bc7\u0baa\u0bcd\u0bb7\u0ba9\u0bcd \u0b87\u0baa\u0bcd\u0baa\u0b9f\u0bbf \u0ba4\u0bc6\u0bb0\u0bbf\u0baf\u0bc1\u0bae\u0bcd");
        add("te", "Telugu", "\u0c24\u0c46\u0c32\u0c41\u0c17\u0c41", "noto_telugu", false, true, false, 0, "Telugu (Telugu script)", TEL,
                "\u0c2e\u0c40 \u0c15\u0c4d\u0c2f\u0c3e\u0c2a\u0c4d\u0c37\u0c28\u0c4d\u0c32\u0c41 \u0c07\u0c32\u0c3e \u0c15\u0c28\u0c3f\u0c2a\u0c3f\u0c38\u0c4d\u0c24\u0c3e\u0c2f\u0c3f");
        add("mr", "Marathi", "\u092e\u0930\u093e\u0920\u0940", "noto_deva", false, true, false, 0, "Marathi (Devanagari script)", DEV,
                "\u0924\u0941\u092e\u091a\u0947 \u0915\u0945\u092a\u094d\u0936\u0928 \u0905\u0938\u0947 \u0926\u093f\u0938\u0924\u0940\u0932");
        add("gu", "Gujarati", "\u0a97\u0ac1\u0a9c\u0ab0\u0abe\u0aa4\u0ac0", "noto_gujarati", false, true, false, 0, "Gujarati (Gujarati script)", GUJ,
                "\u0aa4\u0aae\u0abe\u0ab0\u0abe \u0a95\u0ac5\u0aaa\u0acd\u0ab6\u0aa8 \u0a86\u0ab5\u0abe \u0aa6\u0ac7\u0a96\u0abe\u0ab6\u0ac7");
        add("kn", "Kannada", "\u0c95\u0ca8\u0ccd\u0ca8\u0ca1", "noto_kannada", false, true, false, 0, "Kannada (Kannada script)", KAN,
                "\u0ca8\u0cbf\u0cae\u0ccd\u0cae \u0c95\u0ccd\u0caf\u0cbe\u0caa\u0ccd\u0cb7\u0ca8\u0ccd \u0c88 \u0cb0\u0cc0\u0ca4\u0cbf \u0c95\u0cbe\u0ca3\u0cc1\u0ca4\u0ccd\u0ca4\u0ca6\u0cc6");
        add("ml", "Malayalam", "\u0d2e\u0d32\u0d2f\u0d3e\u0d33\u0d02", "noto_malayalam", false, true, false, 0, "Malayalam (Malayalam script)", MAL,
                "\u0d28\u0d3f\u0d19\u0d4d\u0d19\u0d33\u0d41\u0d1f\u0d46 \u0d15\u0d4d\u0d2f\u0d3e\u0d2a\u0d4d\u0d37\u0d28\u0d4d\u0d2a\u0d4b\u0d32\u0d47 \u0d15\u0d3e\u0d23\u0d3e\u0d02");
        add("pa", "Punjabi", "\u0a2a\u0a70\u0a1c\u0a3e\u0a2c\u0a40", "noto_gurmukhi", false, true, false, 0, "Punjabi (Gurmukhi script)", GUR,
                "\u0a24\u0a41\u0a39\u0a3e\u0a21\u0a47 \u0a15\u0a48\u0a2a\u0a36\u0a28 \u0a07\u0a38 \u0a24\u0a30\u0a4d\u0a39\u0a3e\u0a02 \u0a26\u0a3f\u0a16\u0a23\u0a17\u0a47");
    }

    private CaptionLangs() {}

    /** the 26 languages beyond Hinglish / हिंदी / English, in the picker's order */
    public static List<L> all() { return Collections.unmodifiableList(ALL); }

    public static L byCode(String code) { return code == null ? null : BY.get(code); }

    /** true for "lang:xx" with a known code */
    public static boolean isLang(String script) { return codeOf(script) != null; }

    /** the code of a "lang:xx" value, or null */
    public static String codeOf(String script) {
        if (script == null || !script.startsWith(PREFIX)) return null;
        String c = script.substring(PREFIX.length());
        return BY.containsKey(c) ? c : null;
    }

    public static L of(String script) { String c = codeOf(script); return c == null ? null : BY.get(c); }

    /** the bundled font key for a language's own letters ("" = the caption style's font draws them) */
    public static String fontFor(String script) { L l = of(script); return l == null ? null : l.font; }

    /** true when the script's text is laid out without spaces (Japanese, Chinese, Thai) */
    public static boolean spaceless(String script) { L l = of(script); return l != null && !l.spaced; }

    public static boolean rtl(String script) { L l = of(script); return l != null && l.rtl; }

    /**
     * Whether a word's letters are the language's own: at least 60 % of its letters fall in the language's ranges.
     * Latin-script languages have no range (true), and an empty or digit-only text is true.
     */
    public static boolean inScript(String code, CharSequence text) {
        L l = byCode(code);
        if (l == null || l.ranges.length == 0 || text == null) return true;
        int in = 0, letters = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            letters++;
            for (int k = 0; k + 1 < l.ranges.length; k += 2) if (cp >= l.ranges[k] && cp <= l.ranges[k + 1]) { in++; break; }
        }
        return letters == 0 || in >= letters * 0.6;
    }

    /** the sample line for the preview (native script) */
    public static String sample(String script) { L l = of(script); return l == null ? null : l.sample; }

    /**
     * Spaceless languages: one spoken word (Whisper may give a phrase) is cut into caption words of {@code chunk}
     * letters (CJK) or clusters (Thai), with the word's time shared by how many letters each piece has. Punctuation
     * stays with the piece before it. Spaced languages are returned as they are.
     */
    public static List<Word> splitUnits(List<Word> words, String script) {
        L l = of(script);
        if (l == null || l.spaced || words == null) return words;
        List<Word> out = new ArrayList<>();
        for (Word w : words) {
            if (w == null || w.text == null) { out.add(w); continue; }
            List<String> units = units(w.text, "th".equals(l.code));
            if (units.size() <= l.chunk) { out.add(w); continue; }
            int n = units.size(), per = l.chunk;
            int pieces = (n + per - 1) / per;
            double span = Math.max(0.0, w.end - w.start), t = w.start;
            int done = 0;
            for (int p = 0; p < pieces; p++) {
                int upto = Math.min(n, done + per);
                StringBuilder sb = new StringBuilder();
                for (int k = done; k < upto; k++) sb.append(units.get(k));
                double part = span * (upto - done) / (double) n;
                Word nw = new Word(sb.toString(), t, p == pieces - 1 ? w.end : t + part);
                nw.sentenceEnd = p == pieces - 1 && w.sentenceEnd;
                nw.phrase = w.phrase;
                out.add(nw);
                t += part; done = upto;
            }
        }
        return out;
    }

    /** the letter-units of a spaceless text: a letter (with its marks) and the punctuation after it are one unit */
    static List<String> units(String s, boolean thai) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) { if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); } continue; }
            // a letter or digit starts a new unit; marks, trailing Thai vowels and small kana stay with the one before
            boolean attach = thai ? isThaiTrailing(cp) : isSmallKana(cp);
            if (Character.isLetterOrDigit(cp) && !attach && cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            cur.appendCodePoint(cp);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Thai vowels written after a consonant (sara a, am, vowels above and below, tone marks, maiyamok, ...) */
    static boolean isThaiTrailing(int cp) { return (cp >= 0x0E30 && cp <= 0x0E3A) || (cp >= 0x0E45 && cp <= 0x0E4E); }

    /** Japanese small kana (ゃ ょ っ ぁ ...) and the prolonged sound mark belong to the kana before them */
    static boolean isSmallKana(int cp) {
        return cp == 0x3041 || cp == 0x3043 || cp == 0x3045 || cp == 0x3047 || cp == 0x3049 || cp == 0x3063 || cp == 0x3083
                || cp == 0x3085 || cp == 0x3087 || cp == 0x308E || cp == 0x30A1 || cp == 0x30A3 || cp == 0x30A5 || cp == 0x30A7
                || cp == 0x30A9 || cp == 0x30C3 || cp == 0x30E3 || cp == 0x30E5 || cp == 0x30E7 || cp == 0x30EE || cp == 0x30F5
                || cp == 0x30F6 || cp == 0x30FC;
    }


    /** the AI's name for the language: "Bengali (Bengali script)" */
    public static String prompt(String script) { L l = of(script); return l == null ? null : l.prompt; }

    /** the picker's label: "Bengali · বাংলা" */
    public static String pickerLabel(String script) { L l = of(script); return l == null ? null : l.english + " · " + l.nativeName; }
}
