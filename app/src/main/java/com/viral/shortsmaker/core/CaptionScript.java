package com.viral.shortsmaker.core;

import android.content.Context;

import com.viral.shortsmaker.captions.Word;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v6 caption language: Hinglish (Roman script), Hindi (Devanagari) or English, whatever script Whisper returned.
 *  - same language, other script (Hindi <-> Hinglish, Urdu script -> either): 1:1 word transliteration, so every
 *    word keeps its exact timing;
 *  - other language (Hindi speech -> English captions, English speech -> Hindi captions): each spoken sentence is
 *    translated and its words are spread over that sentence's own speaking time, so captions stay in sync per sentence.
 * All clips of a job are converted in ONE AI request (cached on disk, so Caption Studio's preview and the render
 * share it). If the AI fails the original words are kept and the caller gets a warning.
 */
public final class CaptionScript {
    public static final String HINGLISH = "roman", HINDI = "deva", ENGLISH = "en";

    private CaptionScript() {}

    public static String label(String s) {
        com.viral.shortsmaker.captions.CaptionLangs.L l = com.viral.shortsmaker.captions.CaptionLangs.of(s);
        if (l != null) return l.english;   // v44: the other caption languages
        return HINDI.equals(s) ? "हिंदी" : ENGLISH.equals(s) ? "English" : "Hinglish";
    }

    /** v44: true for the caption languages beyond Hinglish / हिंदी / English ("lang:bn", ...) */
    public static boolean isLang(String s) { return com.viral.shortsmaker.captions.CaptionLangs.isLang(s); }

    /**
     * v44: the caption style as a caption language needs it: its own font (where the style's font can't draw the
     * letters), spaced or run-together words, the ALL CAPS locale (Turkish i -> İ), and more words per line for the
     * spaceless scripts. Hinglish / हिंदी / English and a null target return the style as it is.
     */
    public static com.viral.shortsmaker.captions.CaptionStyle styleFor(String target, com.viral.shortsmaker.captions.CaptionStyle st) {
        com.viral.shortsmaker.captions.CaptionLangs.L l = com.viral.shortsmaker.captions.CaptionLangs.of(target);
        if (st == null || l == null) return st;
        com.viral.shortsmaker.captions.CaptionStyle c = st.copy();
        c.scriptFont = l.font == null || l.font.isEmpty() ? null : l.font;
        c.spaced = l.spaced;
        if (!l.spaced) c.maxWordsPerLine = Math.max(c.maxWordsPerLine, 8);
        c.upperLocale = "tr".equals(l.code) ? "tr" : null;
        c.rtl = l.rtl;
        return c;
    }

    /** v44: a caption setting that names a known language (Hinglish / हिंदी / English, or "lang:xx"); otherwise null */
    public static String valid(String s) {
        if (HINGLISH.equals(s) || HINDI.equals(s) || ENGLISH.equals(s)) return s;
        return isLang(s) ? s : null;
    }

    // ------------------------------------------------------------------ script detection (pure, unit tested)

    static boolean isDeva(char c) { return c >= 0x0900 && c <= 0x097F; }
    static boolean isArabic(char c) { return (c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F) || (c >= 0xFB50 && c <= 0xFEFF); }
    static boolean isLatin(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= 0xC0 && c <= 0x24F); }
    /** code 31: another script with letters (Bengali, Gurmukhi, Tamil, Telugu..., Thai, CJK, Cyrillic...): the captions' fonts can't draw it */
    static boolean isOther(char c) { return !isDeva(c) && !isArabic(c) && !isLatin(c) && c > 0x24F && Character.isLetter(c) || isIndicOther(c); }
    /** Indian scripts after Devanagari (Bengali 0980 ... Malayalam 0D7F), laid out like Devanagari (ISCII) */
    static boolean isIndicOther(char c) { return c >= 0x0980 && c <= 0x0D7F; }

    /** counts of letters per script: {devanagari, arabic, latin, other} (code 31: other = Bengali etc., counted too) */
    static int[] scripts(List<Word> words) {
        int[] n = new int[4];
        for (Word w : words) if (w != null && w.text != null) for (int i = 0; i < w.text.length(); i++) {
            char c = w.text.charAt(i);
            if (isDeva(c)) n[0]++; else if (isArabic(c)) n[1]++; else if (isLatin(c)) n[2]++; else if (isOther(c)) n[3]++;
        }
        return n;
    }

    /**
     * code 31: offline fallback for Hinglish captions of another Indian language: the Indian scripts share Devanagari's
     * layout (ISCII), so a Bengali / Gurmukhi / Gujarati / Odia / Tamil / Telugu / Kannada / Malayalam letter moves to the
     * Devanagari letter at the same place and the Devanagari -> Roman transliteration does the rest ("আমি" -> "aami").
     */
    public static String indicToDeva(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isIndicOther(c)) {
                int off = (c - 0x0900) % 0x80;
                char d = (char) (0x0900 + off);
                b.append(Character.isDefined(d) ? d : c);
            } else b.append(c);
        }
        return b.toString();
    }

    static boolean hasIndicOther(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) if (isIndicOther(s.charAt(i))) return true;
        return false;
    }

    public static boolean isDevanagariText(List<Word> words) { int[] n = scripts(words); return n[0] > (n[0] + n[1] + n[2]) * 0.5; }

    /** What has to happen to the words: "keep", "translit" or "translate". */
    public static String action(List<Word> words, String transcriptLang, String target) {
        if (target == null) target = HINGLISH;   // v7: null language (pre-v6 prefs / early call) crashed Caption Studio
        if (words == null) return "keep";
        int[] n = scripts(words);
        int total = n[0] + n[1] + n[2] + n[3];
        if (total == 0) return "keep";
        String lang = Transcript.languageCode(transcriptLang);
        // v44: the other caption languages: the words stay when they were spoken in that language (or already are in its
        // letters); otherwise they are translated by the AI, sentence by sentence, with the same timing rules
        if (isLang(target)) {
            String code = com.viral.shortsmaker.captions.CaptionLangs.codeOf(target);
            if (code.equals(lang)) return "keep";
            if (lang.isEmpty() && com.viral.shortsmaker.captions.CaptionLangs.inScript(code, joined(words))) return "keep";
            return "translate";
        }
        // code 31: another language's script (Bengali song): captions are only ever Hinglish / हिंदी / English, so
        // Hinglish = the same words in Roman letters, हिंदी / English = translated (the caption fonts can't draw Bengali)
        if (n[3] > total * 0.3) return HINGLISH.equals(target) ? "translit" : "translate";
        boolean indic = n[0] + n[1] > total * 0.15;              // Devanagari or Urdu script present
        boolean english = !indic && (lang.equals("en") || lang.isEmpty());
        switch (target) {
            case HINDI:
                if (n[1] == 0 && n[2] <= total * 0.15) return "keep";   // already Devanagari (a few English words are fine)
                return english ? "translate" : "translit";
            case ENGLISH:
                if (!indic && english) return "keep";
                return "translate";
            case HINGLISH:
            default:
                if (!indic) return "keep";                                // already Roman script
                return "translit";
        }
    }

    static String joined(List<Word> words) {
        StringBuilder sb = new StringBuilder();
        for (Word w : words) if (w != null && w.text != null) { if (sb.length() > 0) sb.append(' '); sb.append(w.text); }
        return sb.toString();
    }

    /** sentence groups: indices [from, to) — split at sentence ends, pauses > 0.8 s and every 14 words */
    static List<int[]> sentences(List<Word> w) {
        List<int[]> out = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < w.size(); i++) {
            boolean last = i == w.size() - 1;
            boolean cut = last || w.get(i).sentenceEnd || i - from + 1 >= 14
                    || w.get(i + 1).start - w.get(i).end > 0.8 || endsSentence(w.get(i).text);
            if (cut) { out.add(new int[]{from, i + 1}); from = i + 1; }
        }
        return out;
    }

    static boolean endsSentence(String t) {
        String s = t.trim();
        return s.endsWith(".") || s.endsWith("?") || s.endsWith("!") || s.endsWith("।") || s.endsWith("॥");
    }

    /** Spreads a translated sentence over the original sentence's speaking time (by word length, pauses kept). */
    static List<Word> spread(List<Word> orig, String translated) {
        List<Word> out = new ArrayList<>();
        String[] toks = translated.trim().split("\\s+");
        if (orig.isEmpty() || translated.trim().isEmpty()) return out;
        // speaking intervals of the original words (merging tiny gaps)
        double total = 0;
        for (Word w : orig) total += Math.max(0.05, w.end - w.start);
        double[] weights = new double[toks.length];
        double wsum = 0;
        for (int i = 0; i < toks.length; i++) { weights[i] = toks[i].length() + 2; wsum += weights[i]; }
        // walk the original words' timeline
        double acc = 0;
        for (int i = 0; i < toks.length; i++) {
            double a = acc / wsum * total, b = (acc + weights[i]) / wsum * total;
            acc += weights[i];
            Word w = new Word(toks[i], at(orig, a), at(orig, b));
            if (i == toks.length - 1) w.sentenceEnd = orig.get(orig.size() - 1).sentenceEnd;
            out.add(w);
        }
        return out;
    }

    /** position on the timeline after t seconds of speech (pauses between words are skipped) */
    private static double at(List<Word> orig, double t) {
        double left = t;
        for (Word w : orig) {
            double d = Math.max(0.05, w.end - w.start);
            if (left <= d + 1e-9) return w.start + Math.min(left, w.end - w.start);
            left -= d;
        }
        return orig.get(orig.size() - 1).end;
    }

    // ------------------------------------------------------------------ conversion

    /** One clip's words in the target language. Never throws (except cancel); on failure returns the input. */
    public static List<Word> convert(Context ctx, CancelToken ct, Transcript tr, List<Word> words, String target, List<String> warnings) {
        List<List<Word>> one = new ArrayList<>();
        one.add(words);
        return convertAll(ctx, ct, tr, one, target, warnings).get(0);
    }

    /** All clips in one AI request (cache hits skipped). v44: spaceless languages are cut into caption words afterwards. */
    public static List<List<Word>> convertAll(Context ctx, CancelToken ct, Transcript tr, List<List<Word>> clips, String target, List<String> warnings) {
        List<List<Word>> out = convertCore(ctx, ct, tr, clips, target, warnings);
        if (target != null && com.viral.shortsmaker.captions.CaptionLangs.spaceless(target)) {
            List<List<Word>> cut = new ArrayList<>();
            for (List<Word> w : out) cut.add(com.viral.shortsmaker.captions.CaptionLangs.splitUnits(w, target));
            return cut;
        }
        return out;
    }

    private static List<List<Word>> convertCore(Context ctx, CancelToken ct, Transcript tr, List<List<Word>> clips, String target, List<String> warnings) {
        if (target == null) target = HINGLISH;
        String lang = tr == null || tr.language == null ? "" : tr.language;
        List<List<Word>> out = new ArrayList<>(clips);
        List<Integer> trl = new ArrayList<>(), tra = new ArrayList<>();
        for (int i = 0; i < clips.size(); i++) {
            List<Word> w = clips.get(i);
            String act = action(w, lang, target);
            if (act.equals("keep")) continue;
            List<Word> cached = readCache(ctx, key(target, w));
            if (cached != null) { out.set(i, cached); continue; }
            (act.equals("translit") ? trl : tra).add(i);
        }
        if (trl.isEmpty() && tra.isEmpty()) return out;
        AiClient ai = new AiClient(ctx, ct).notices(warnings);   // 5.3: a key fallback says so in the result
        try {
            // ---- 1:1 transliteration, all clips in one array
            if (!trl.isEmpty()) {
                List<String> flat = new ArrayList<>();
                for (int i : trl) for (Word w : clips.get(i)) flat.add(w.text);
                List<String> r = ai.transliterate(flat, target);
                if (r == null && trl.size() > 1) {           // one big array came back wrong: per clip
                    for (int i : trl) apply1(ctx, out, clips, i, ai.transliterate(texts(clips.get(i)), target), target);
                } else if (r != null) {
                    int k = 0;
                    for (int i : trl) { int n = clips.get(i).size(); apply1(ctx, out, clips, i, r.subList(k, k + n), target); k += n; }
                }
            }
            // ---- sentence translation
            if (!tra.isEmpty()) {
                List<String> sents = new ArrayList<>();
                List<List<Word>> groups = new ArrayList<>();
                List<Integer> owner = new ArrayList<>();
                for (int i : tra) for (int[] g : phraseUnits(clips.get(i))) {
                    List<Word> sub = clips.get(i).subList(g[0], g[1]);
                    StringBuilder sb = new StringBuilder();
                    for (Word w : sub) { if (sb.length() > 0) sb.append(' '); sb.append(w.text.trim()); }
                    sents.add(sb.toString()); groups.add(new ArrayList<>(sub)); owner.add(i);
                }
                String tl = ENGLISH.equals(target) ? "English" : isLang(target) ? com.viral.shortsmaker.captions.CaptionLangs.prompt(target) : "Hindi (Devanagari script)";
                List<String> r = ai.translate(sents, tl);
                if (r == null || r.size() != sents.size() || !translationOk(r, target)) r = ai.translate(sents, tl);   // v8: one more try
                if (r != null && (r.size() != sents.size() || !translationOk(r, target))) r = null;
                if (r != null) {
                    java.util.Map<Integer, List<Word>> built = new java.util.LinkedHashMap<>();
                    int[] pid = {0};
                    for (int s = 0; s < sents.size(); s++) {
                        List<Word> nw = phrases(groups.get(s), r.get(s), pid);
                        if (nw.isEmpty()) nw = groups.get(s);
                        built.computeIfAbsent(owner.get(s), k -> new ArrayList<>()).addAll(nw);
                    }
                    for (java.util.Map.Entry<Integer, List<Word>> e : built.entrySet()) {
                        out.set(e.getKey(), e.getValue());
                        writeCache(ctx, key(target, clips.get(e.getKey())), e.getValue());
                    }
                }
            }
        } catch (CancelToken.Cancelled c) {
            throw c;
        } catch (Exception e) {
            AppLog.e("Caption language conversion failed: " + e.getMessage());
        }
        // v8: Hinglish never falls back to Devanagari: whatever the AI didn't do (busy / quota / wrong script) is
        // transliterated on the phone, word by word, so timing stays 1:1 with the speech
        if (HINGLISH.equals(target)) for (int i = 0; i < out.size(); i++) {
            List<Word> fixed = romanize(out.get(i));
            if (fixed != out.get(i)) { out.set(i, fixed); if (trl.contains(i) && clips.get(i) != fixed) writeCache(ctx, key(target, clips.get(i)), fixed); }
        }
        for (int i : trl) if (out.get(i) == clips.get(i)) warn(warnings, target);
        for (int i : tra) if (out.get(i) == clips.get(i)) warn(warnings, target);
        return out;
    }

    /** v8: English captions must be English letters; हिंदी captions must be Devanagari */
    static boolean translationOk(List<String> r, String target) {
        if (isLang(target)) {   // v44: the language's own letters, at least 60 % of the words' letters
            String code = com.viral.shortsmaker.captions.CaptionLangs.codeOf(target);
            if (com.viral.shortsmaker.captions.CaptionLangs.byCode(code).cased) return true;   // Latin / Cyrillic: the AI's letters
            StringBuilder all = new StringBuilder();
            for (String s : r) if (s != null) all.append(s).append(' ');
            return com.viral.shortsmaker.captions.CaptionLangs.inScript(code, all);
        }
        int deva = 0, latin = 0, other = 0;
        for (String s : r) if (s != null) for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (isDeva(c)) deva++; else if (isLatin(c)) latin++; else if (isOther(c)) other++; }
        // code 31: a "translation" still in Bengali (etc.) letters is not English / हिंदी
        if (ENGLISH.equals(target)) return deva + other <= (deva + latin + other) * 0.05;
        if (HINDI.equals(target)) return deva >= (deva + latin + other) * 0.6;
        return true;
    }

    private static void warn(List<String> warnings, String target) {
        String m = "Couldn't convert some captions to " + label(target) + " (AI busy), they were kept as spoken. Tap Captions → Apply to retry.";
        if (warnings != null && !warnings.contains(m)) warnings.add(m);
    }

    /** v8 (harness / tests): the sentence texts the app sends for translation */
    public static List<String> sentenceTexts(List<Word> w) {
        List<String> out = new ArrayList<>();
        for (int[] g : sentences(w)) {
            StringBuilder sb = new StringBuilder();
            for (Word x : w.subList(g[0], g[1])) { if (sb.length() > 0) sb.append(' '); sb.append(x.text.trim()); }
            out.add(sb.toString());
        }
        return out;
    }

    /** v8 (harness / tests): translated sentences placed on the original words' timing, exactly like convertAll */
    public static List<Word> spreadSentences(List<Word> w, List<String> translated) {
        List<Word> out = new ArrayList<>();
        List<int[]> gs = sentences(w);
        for (int s = 0; s < gs.size(); s++) {
            List<Word> sub = new ArrayList<>(w.subList(gs.get(s)[0], gs.get(s)[1]));
            List<Word> nw = spread(sub, translated.get(s));
            out.addAll(nw.isEmpty() ? sub : nw);
        }
        return out;
    }

    /**
     * v9: the AI may only change the script, never the word: one caption word per spoken word, same timing. Each
     * Hinglish word is checked against the phone's own letter-by-letter transliteration of the spoken Hindi word; a
     * different word (paraphrase / translation) is replaced by that transliteration. {@code replaced[0]} counts them.
     */
    public static List<Word> validateTranslit(List<Word> src, List<String> ai, String target, int[] replaced) {
        List<Word> nw = new ArrayList<>();
        for (int k = 0; k < src.size(); k++) {
            String a = ai == null || k >= ai.size() ? null : ai.get(k);
            String t = a == null || a.trim().isEmpty() || !scriptOk(a, target) ? src.get(k).text : a.trim();
            if (HINGLISH.equals(target) && Translit.hasDevanagari(src.get(k).text)) {
                String local = Translit.word(src.get(k).text);
                if (!sameSpokenWord(t, local)) { t = local; if (replaced != null) replaced[0]++; }
            }
            nw.add(src.get(k).withText(t));
        }
        return nw;
    }

    /**
     * v9: is {@code ai} the same spoken word as the offline transliteration {@code local}? Compares consonant skeletons
     * (vowels, h, doubled letters and common spelling variants ignored) with a small edit-distance allowance, so
     * "pyaar"/"pyar", "kya"/"kyaa", "hai"/"he" pass while "married"/"shaadi" fails.
     */
    public static boolean sameSpokenWord(String ai, String local) {
        if (ai == null || local == null) return false;
        String a = skeleton(ai), b = skeleton(local);
        if (a.equals(b)) return true;
        if (a.isEmpty() || b.isEmpty()) return vowelsOnly(ai).equals(vowelsOnly(local)) || (a.isEmpty() && b.isEmpty());
        int d = lev(a, b), m = Math.max(a.length(), b.length());
        return m <= 2 ? d == 0 : m <= 4 ? d <= 1 : d <= Math.max(1, m / 3);
    }

    static String skeleton(String s) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        t = t.replace("ph", "f").replace("kh", "k").replace("gh", "g").replace("ch", "c").replace("jh", "j")
                .replace("th", "t").replace("dh", "d").replace("bh", "b").replace("sh", "s").replace("zh", "j")
                .replace("q", "k").replace("x", "ks").replace("w", "v").replace("z", "j").replace("ck", "k");
        t = t.replaceAll("[aeiouyh]", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) if (i == 0 || t.charAt(i) != t.charAt(i - 1)) sb.append(t.charAt(i));
        return sb.toString().replace("n", "n");
    }

    private static String vowelsOnly(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^aeiou]", "").replaceAll("(.)\\1+", "$1"); }

    static int lev(String a, String b) {
        int[] p = new int[b.length() + 1], c = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) p[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            c[0] = i;
            for (int j = 1; j <= b.length(); j++)
                c[j] = Math.min(Math.min(c[j - 1] + 1, p[j] + 1), p[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = p; p = c; c = t;
        }
        return p[b.length()];
    }

    /**
     * v9: units for translated captions — split at sentence ends, pauses of 0.35 s or more, and every 8 spoken words,
     * so each translated phrase is short and sits exactly on the time its own words were spoken.
     */
    public static List<int[]> phraseUnits(List<Word> w) {
        List<int[]> out = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < w.size(); i++) {
            boolean last = i == w.size() - 1;
            boolean cut = last || w.get(i).sentenceEnd || i - from + 1 >= 8
                    || w.get(i + 1).start - w.get(i).end >= 0.35 || endsSentence(w.get(i).text);
            if (cut) { out.add(new int[]{from, i + 1}); from = i + 1; }
        }
        return out;
    }

    /**
     * v9: a translated phrase shown on screen for exactly the time its source words were spoken (first word start to
     * last word end). Long translations are split into chunks of at most 6 words / 34 letters, each getting its share
     * of that time by length. A translation can't be word-for-word, so no word inside it is highlighted on its own.
     */
    public static List<Word> phrases(List<Word> orig, String translated, int[] nextId) {
        List<Word> out = new ArrayList<>();
        if (orig.isEmpty() || translated == null || translated.trim().isEmpty()) return out;
        String[] toks = translated.trim().split("\\s+");
        List<List<String>> chunks = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int len = 0;
        for (String t : toks) {
            if (!cur.isEmpty() && (cur.size() >= 6 || len + 1 + t.length() > 34)) { chunks.add(cur); cur = new ArrayList<>(); len = 0; }
            cur.add(t); len += (len == 0 ? 0 : 1) + t.length();
        }
        if (!cur.isEmpty()) chunks.add(cur);
        double s0 = orig.get(0).start, e0 = orig.get(orig.size() - 1).end;
        if (e0 - s0 < 0.3) e0 = s0 + 0.3;
        int total = 0;
        for (List<String> c : chunks) total += String.join(" ", c).length();
        double acc = 0;
        for (int ci = 0; ci < chunks.size(); ci++) {
            List<String> c = chunks.get(ci);
            double a = s0 + (e0 - s0) * acc / total;
            acc += String.join(" ", c).length();
            double b = ci == chunks.size() - 1 ? e0 : s0 + (e0 - s0) * acc / total;
            int id = nextId[0]++;
            for (int k = 0; k < c.size(); k++) {
                Word w = new Word(c.get(k), a, b);
                w.phrase = id;
                if (ci == chunks.size() - 1 && k == c.size() - 1) w.sentenceEnd = orig.get(orig.size() - 1).sentenceEnd;
                out.add(w);
            }
        }
        return out;
    }

    /** v9 (harness / tests): translated phrases placed exactly like convertAll does */
    public static List<Word> phraseTranslation(List<Word> w, List<String> translated) {
        List<Word> out = new ArrayList<>();
        List<int[]> gs = phraseUnits(w);
        int[] pid = {0};
        for (int s = 0; s < gs.size(); s++) {
            List<Word> sub = new ArrayList<>(w.subList(gs.get(s)[0], gs.get(s)[1]));
            List<Word> nw = phrases(sub, translated.get(s), pid);
            out.addAll(nw.isEmpty() ? sub : nw);
        }
        return out;
    }

    public static List<String> phraseTexts(List<Word> w) {
        List<String> out = new ArrayList<>();
        for (int[] g : phraseUnits(w)) {
            StringBuilder sb = new StringBuilder();
            for (Word x : w.subList(g[0], g[1])) { if (sb.length() > 0) sb.append(' '); sb.append(x.text.trim()); }
            out.add(sb.toString());
        }
        return out;
    }

    /** v8: every Devanagari word -> Roman (offline); the same list when nothing needed changing */
    public static List<Word> romanize(List<Word> w) {
        if (w == null) return null;
        boolean any = false;
        for (Word x : w) if (Translit.hasDevanagari(x.text) || hasIndicOther(x.text)) { any = true; break; }
        if (!any) return w;
        List<Word> nw = new ArrayList<>();
        for (Word x : w) {
            String t = hasIndicOther(x.text) ? indicToDeva(x.text) : x.text;   // code 31: Bengali etc. via the shared layout
            nw.add(Translit.hasDevanagari(t) ? x.withText(Translit.word(t)) : x);
        }
        return nw;
    }

    /** v8: is this AI word in the target script? (a Hinglish word must be Roman, a हिंदी word must not be Urdu script) */
    static boolean scriptOk(String t, String target) {
        if (t == null) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (HINGLISH.equals(target) && (isDeva(c) || isArabic(c) || isOther(c))) return false;
            if (HINDI.equals(target) && isArabic(c)) return false;
        }
        return true;
    }

    private static void apply1(Context ctx, List<List<Word>> out, List<List<Word>> clips, int i, List<String> r, String target) {
        List<Word> src = clips.get(i);
        if (r == null || r.size() != src.size()) return;
        int[] replaced = {0};
        List<Word> nw = validateTranslit(src, r, target, replaced);
        if (replaced[0] > 0) AppLog.i("Hinglish: " + replaced[0] + " of " + src.size() + " AI words did not match the spoken word, used the exact transliteration");
        out.set(i, nw);
        writeCache(ctx, key(target, src), nw);
    }

    private static List<String> texts(List<Word> w) { List<String> l = new ArrayList<>(); for (Word x : w) l.add(x.text); return l; }

    // ------------------------------------------------------------------ disk cache

    static String key(String target, List<Word> w) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(("v9|" + target).getBytes("UTF-8"));   // v9: phrase-timed translations, validated transliteration
            for (Word x : w) md.update((x.text + "|" + Math.round(x.start * 100) + ";").getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) { return String.valueOf(w.hashCode()); }
    }

    private static File cacheFile(Context ctx, String key) {
        File d = new File(ctx.getCacheDir(), "caption_lang");
        d.mkdirs();
        return new File(d, key + ".json");
    }

    static List<Word> readCache(Context ctx, String key) {
        try {
            File f = cacheFile(ctx, key);
            if (!f.exists()) return null;
            JSONArray a = new JSONArray(Project.readText(f));
            List<Word> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Word w = new Word(o.getString("t"), o.getDouble("s"), o.getDouble("e"));
                w.sentenceEnd = o.optBoolean("x", false);
                w.phrase = o.optInt("p", -1);
                out.add(w);
            }
            return out;
        } catch (Exception e) { return null; }
    }

    static void writeCache(Context ctx, String key, List<Word> words) {
        try {
            JSONArray a = new JSONArray();
            for (Word w : words) a.put(new JSONObject().put("t", w.text).put("s", w.start).put("e", w.end).put("x", w.sentenceEnd).put("p", w.phrase));
            Project.writeText(cacheFile(ctx, key), a.toString());
        } catch (Exception ignored) {}
    }
}
