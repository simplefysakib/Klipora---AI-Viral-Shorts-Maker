package com.viral.shortsmaker.core;

import com.viral.shortsmaker.captions.Word;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Whisper result: segments (for clip alignment + Gemini) and word timings (for captions). Absolute seconds. */
public class Transcript {
    public static class Segment {
        public final double start, end;
        public final String text;
        public Segment(double start, double end, String text) { this.start = start; this.end = end; this.text = text; }
    }

    public final List<Segment> segments = new ArrayList<>();
    /** time ranges whisper "looped" on (the same phrase repeated over and over: a known Whisper hallucination) */
    public final List<double[]> loops = new ArrayList<>();
    public final List<Word> words = new ArrayList<>();
    public String language = "";

    /** Parses Groq/OpenAI verbose_json. Words may be top-level ("words") or nested per segment. */
    public static Transcript parseVerboseJson(String json, double offset) throws Exception {
        JSONObject o = new JSONObject(json);
        Transcript t = new Transcript();
        t.language = o.optString("language", "");
        JSONArray segs = o.optJSONArray("segments");
        if (segs != null) {
            for (int i = 0; i < segs.length(); i++) {
                JSONObject s = segs.getJSONObject(i);
                String text = s.optString("text", "").trim();
                if (isLoopText(text, s.optDouble("compression_ratio", 0))) {
                    t.loops.add(new double[]{s.optDouble("start") + offset, s.optDouble("end") + offset});
                    continue;
                }
                if (!text.isEmpty()) t.segments.add(new Segment(s.optDouble("start") + offset, s.optDouble("end") + offset, text));
                JSONArray sw = s.optJSONArray("words");
                if (sw != null && o.optJSONArray("words") == null) addWords(t, sw, offset);
            }
        }
        JSONArray ws = o.optJSONArray("words");
        if (ws != null) addWords(t, ws, offset);
        if (t.segments.isEmpty() && !o.optString("text", "").trim().isEmpty() && !t.words.isEmpty()) {
            t.segments.add(new Segment(t.words.get(0).start, t.words.get(t.words.size() - 1).end, o.optString("text").trim()));
        }
        if (t.segments.isEmpty()) throw new Exception("No speech found in this video (empty transcript)");
        return t;
    }

    private static void addWords(Transcript t, JSONArray ws, double offset) {
        for (int i = 0; i < ws.length(); i++) {
            JSONObject w = ws.optJSONObject(i);
            if (w == null) continue;
            String text = w.optString("word", w.optString("text", "")).trim();
            if (text.isEmpty()) continue;
            double s = w.optDouble("start") + offset, e = w.optDouble("end") + offset;
            boolean inLoop = false;
            for (double[] l : t.loops) if (s >= l[0] - 0.05 && s <= l[1] + 0.05) { inLoop = true; break; }
            if (inLoop) continue;
            t.words.add(new Word(text, s, e));
        }
    }

    /**
     * Whisper sometimes gets stuck repeating one phrase ("ready to take off and they're ready to take off ..."), with a
     * high compression ratio. Those segments are not speech the person said and would end up in captions and in the AI
     * prompt, so they are dropped (and the piece retried once with a higher temperature by the pipeline).
     */
    public static boolean isLoopText(String text, double compressionRatio) {
        if (compressionRatio > 2.6) return true;
        String[] w = text.toLowerCase(Locale.ROOT).split("\\s+");
        if (w.length < 24) return false;
        java.util.Map<String, Integer> grams = new java.util.HashMap<>();
        int max = 0;
        for (int i = 0; i + 3 <= w.length; i++) {
            String g = w[i] + " " + w[i + 1] + " " + w[i + 2];
            int c = grams.merge(g, 1, Integer::sum);
            if (c > max) max = c;
        }
        return max >= 6 && max * 3 >= w.length / 2;
    }

    public boolean looping() { return !loops.isEmpty(); }

    /** v17: some parts could not be transcribed (left as a gap). A partial transcript is never cached. */
    public boolean partial;
    /** v17: the Gemini answer was cut off / malformed and was salvaged object by object */
    public boolean salvaged;

    /** v17: end of the last segment or word (0 when empty) */
    public double lastEnd() {
        double e = 0;
        for (Segment s : segments) e = Math.max(e, s.end);
        for (Word w : words) e = Math.max(e, w.end);
        return e;
    }

    /** v17: loop ranges merged when closer than {@code gap} seconds, in time order */
    public List<double[]> loopSpans(double gap) {
        List<double[]> ls = new ArrayList<>();
        for (double[] l : loops) ls.add(new double[]{l[0], l[1]});
        ls.sort((a, b) -> Double.compare(a[0], b[0]));
        List<double[]> out = new ArrayList<>();
        for (double[] l : ls) {
            if (!out.isEmpty() && l[0] - out.get(out.size() - 1)[1] < gap) out.get(out.size() - 1)[1] = Math.max(out.get(out.size() - 1)[1], l[1]);
            else out.add(l);
        }
        return out;
    }

    /**
     * v17: replaces everything that starts inside [from, to) with {@code sub}'s words/segments there. {@code sub} was
     * transcribed from audio cut at {@code off} (its 0 s = {@code off} on this transcript's timeline), so its times are
     * shifted by exactly {@code off}. Used for a re-check of only the span Whisper looped on, and for a re-asked tail.
     */
    public void spliceSpan(Transcript sub, double off, double from, double to) {
        segments.removeIf(s -> s.start >= from && s.start < to);
        words.removeIf(w -> w.start >= from && w.start < to);
        for (Segment s : sub.segments) { double st = s.start + off; if (st >= from && st < to) segments.add(new Segment(st, s.end + off, s.text)); }
        for (Word w : sub.words) { double st = w.start + off; if (st >= from && st < to) words.add(w.shift(off)); }
        segments.sort((a, b) -> Double.compare(a.start, b.start));
        words.sort((a, b) -> Double.compare(a.start, b.start));
        loops.removeIf(l -> l[0] >= from - 0.05 && l[1] <= to + 0.05);
    }

    /**
     * v17: Gemini transcript answer, salvaged. Every complete segment object is kept even when the answer was cut off
     * or malformed (2.7 failed the whole job on "Unterminated object at character 8603" — the last object had no
     * closing brace). Accepts {"s","e","t"} and {"start","end","text"} objects; segments outside 0..maxSec are dropped.
     * Word times are spread over each segment by length (Gemini gives no word timings).
     */
    public static Transcript parseGeminiSegments(String raw, String lang, double maxSec) {
        Transcript t = new Transcript();
        t.language = lang == null ? "" : lang;
        if (raw == null) return t;
        int i = 0, n = raw.length(), bad = 0;
        double lastStart = -1;
        while (i < n) {
            int a = raw.indexOf('{', i);
            if (a < 0) break;
            // find the matching '}' (strings respected); -1 when the answer stops inside this object
            int depth = 0, b = -1;
            boolean str = false, esc = false;
            for (int j = a; j < n; j++) {
                char c = raw.charAt(j);
                if (str) { if (esc) esc = false; else if (c == '\\') esc = true; else if (c == '"') str = false; continue; }
                if (c == '"') str = true;
                else if (c == '{') depth++;
                else if (c == '}') { if (--depth == 0) { b = j; break; } }
                else if (c == '[' || c == ']') { if (depth == 1 && c == ']') break; }   // array closed inside an object: malformed
            }
            String obj = b > 0 ? raw.substring(a, b + 1) : raw.substring(a, nextObjectStart(raw, a + 1));
            double[] se = new double[2];
            String tx = segmentFields(obj, se);
            if (b < 0) { t.salvaged = true; }
            i = b > 0 ? b + 1 : Math.max(a + 1, nextObjectStart(raw, a + 1));
            if (tx == null) { bad++; continue; }
            double s = se[0], e = se[1];
            tx = tx.trim();
            if (s < 0 || e <= s || tx.isEmpty() || s > maxSec + 1) { bad++; continue; }
            if (s < lastStart - 0.5) { bad++; continue; }   // out of order: a repeated / garbled tail
            e = Math.min(e, maxSec + 1);
            lastStart = s;
            t.segments.add(new Segment(s, e, tx));
            String[] ws = tx.split("\\s+");
            int chars = 0;
            for (String w : ws) chars += w.length() + 1;
            double pos = s;
            for (String w : ws) {
                double len = (e - s) * (w.length() + 1) / Math.max(1, chars);
                t.words.add(new Word(w, pos, pos + len * 0.92));
                pos += len;
            }
        }
        if (bad > 0 && !t.segments.isEmpty()) t.salvaged = true;
        return t;
    }

    private static int nextObjectStart(String raw, int from) {
        boolean str = false, esc = false;
        for (int j = from; j < raw.length(); j++) {
            char c = raw.charAt(j);
            if (str) { if (esc) esc = false; else if (c == '\\') esc = true; else if (c == '"') str = false; continue; }
            if (c == '"') str = true;
            else if (c == '{') return j;
        }
        return raw.length();
    }

    private static final java.util.regex.Pattern NUM_S = java.util.regex.Pattern.compile("\"(?:s|start)\"\\s*:\\s*\"?(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final java.util.regex.Pattern NUM_E = java.util.regex.Pattern.compile("\"(?:e|end)\"\\s*:\\s*\"?(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final java.util.regex.Pattern TXT = java.util.regex.Pattern.compile("\"(?:t|text)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** start/end into se, returns the (unescaped) text, or null when the object lacks one of the three */
    static String segmentFields(String obj, double[] se) {
        try {
            if (obj.endsWith("}")) {
                JSONObject o = new JSONObject(obj);
                se[0] = o.has("s") ? o.optDouble("s", -1) : o.optDouble("start", -1);
                se[1] = o.has("e") ? o.optDouble("e", -1) : o.optDouble("end", -1);
                String tx = o.has("t") ? o.optString("t", null) : o.optString("text", null);
                if (tx != null && se[0] >= 0 && se[1] >= 0) return tx;
            }
        } catch (Exception ignored) {}
        java.util.regex.Matcher ms = NUM_S.matcher(obj), me = NUM_E.matcher(obj), mt = TXT.matcher(obj);
        if (!ms.find() || !me.find() || !mt.find()) return null;
        se[0] = Double.parseDouble(ms.group(1));
        se[1] = Double.parseDouble(me.group(1));
        try { return new JSONArray("[\"" + mt.group(1) + "\"]").getString(0); } catch (Exception e) { return mt.group(1); }
    }

    /**
     * Whisper reports the language as a name ("hindi"); the API takes ISO codes. code 32: Whisper's whole list (99 names +
     * aliases; 4.9 knew 24, so Vietnamese, Persian, Ukrainian, Thai, Polish... samples counted as "nothing heard" and the
     * job fell back to English = a translated transcript), and region codes ("pt-BR", "es-419", "hi-IN": yt-dlp's form
     * for a video's / a dub's language) are reduced to the language.
     */
    public static String languageCode(String name) {
        if (name == null) return "";
        String n = name.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        int dash = n.indexOf('-');
        if ((dash == 2 || dash == 3) && n.substring(0, dash).matches("[a-z]+")) n = n.substring(0, dash);
        if (n.length() == 2 || (n.length() == 3 && WHISPER.contains(n))) return n;
        for (String[] p : NAMES) if (p[0].equals(n)) return p[1];
        return "";
    }

    static final String[][] NAMES = {{"english", "en"}, {"chinese", "zh"}, {"german", "de"}, {"spanish", "es"}, {"russian", "ru"}, {"korean", "ko"}, {"french", "fr"}, {"japanese", "ja"}, {"portuguese", "pt"}, {"turkish", "tr"}, {"polish", "pl"}, {"catalan", "ca"}, {"dutch", "nl"}, {"arabic", "ar"}, {"swedish", "sv"}, {"italian", "it"}, {"indonesian", "id"}, {"hindi", "hi"}, {"finnish", "fi"}, {"vietnamese", "vi"}, {"hebrew", "he"}, {"ukrainian", "uk"}, {"greek", "el"}, {"malay", "ms"}, {"czech", "cs"}, {"romanian", "ro"}, {"danish", "da"}, {"hungarian", "hu"}, {"tamil", "ta"}, {"norwegian", "no"}, {"thai", "th"}, {"urdu", "ur"}, {"croatian", "hr"}, {"bulgarian", "bg"}, {"lithuanian", "lt"}, {"latin", "la"}, {"maori", "mi"}, {"malayalam", "ml"}, {"welsh", "cy"}, {"slovak", "sk"}, {"telugu", "te"}, {"persian", "fa"}, {"latvian", "lv"}, {"bengali", "bn"}, {"serbian", "sr"}, {"azerbaijani", "az"}, {"slovenian", "sl"}, {"kannada", "kn"}, {"estonian", "et"}, {"macedonian", "mk"}, {"breton", "br"}, {"basque", "eu"}, {"icelandic", "is"}, {"armenian", "hy"}, {"nepali", "ne"}, {"mongolian", "mn"}, {"bosnian", "bs"}, {"kazakh", "kk"}, {"albanian", "sq"}, {"swahili", "sw"}, {"galician", "gl"}, {"marathi", "mr"}, {"punjabi", "pa"}, {"sinhala", "si"}, {"khmer", "km"}, {"shona", "sn"}, {"yoruba", "yo"}, {"somali", "so"}, {"afrikaans", "af"}, {"occitan", "oc"}, {"georgian", "ka"}, {"belarusian", "be"}, {"tajik", "tg"}, {"sindhi", "sd"}, {"gujarati", "gu"}, {"amharic", "am"}, {"yiddish", "yi"}, {"lao", "lo"}, {"uzbek", "uz"}, {"faroese", "fo"}, {"haitian creole", "ht"}, {"pashto", "ps"}, {"turkmen", "tk"}, {"nynorsk", "nn"}, {"maltese", "mt"}, {"sanskrit", "sa"}, {"luxembourgish", "lb"}, {"myanmar", "my"}, {"tibetan", "bo"}, {"tagalog", "tl"}, {"malagasy", "mg"}, {"assamese", "as"}, {"tatar", "tt"}, {"hawaiian", "haw"}, {"lingala", "ln"}, {"hausa", "ha"}, {"bashkir", "ba"}, {"javanese", "jw"}, {"sundanese", "su"}, {"cantonese", "yue"}, {"burmese", "my"}, {"valencian", "ca"}, {"flemish", "nl"}, {"haitian", "ht"}, {"letzeburgesch", "lb"}, {"pushto", "ps"}, {"panjabi", "pa"}, {"moldavian", "ro"}, {"moldovan", "ro"}, {"sinhalese", "si"}, {"castilian", "es"}, {"mandarin", "zh"}, {"filipino", "tl"}, {"odia", "or"}, {"oriya", "or"}, {"bangla", "bn"}};

    /** code 32: the language codes Whisper (Groq) transcribes; another code (e.g. Odia "or") is refused with HTTP 400 */
    public static final java.util.Set<String> WHISPER = new java.util.HashSet<>(java.util.Arrays.asList("en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no", "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk", "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw", "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl", "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su", "yue"));

    public static boolean whisperKnows(String code) { return code != null && WHISPER.contains(code); }

    /**
     * Whisper's word list carries no punctuation; the segment text does. Copy the segment's punctuation (and casing) onto
     * the matching words, so captions can break pages at sentence ends and subtitle styles show proper punctuation.
     */
    public void punctuateWords() {
        int wi = 0, n = words.size();
        for (Segment g : segments) {
            while (wi < n && mid(words.get(wi)) < g.start - 0.05) wi++;
            int from = wi;
            while (wi < n && mid(words.get(wi)) < g.end + 0.05) wi++;
            int cnt = wi - from;
            if (cnt == 0) continue;
            String[] tt = g.text.trim().split("\\s+");
            for (int i = 0; i < tt.length; i++) {
                String tok = tt[i], nt = ClipAligner.norm(tok);
                if (nt.isEmpty()) continue;
                int k = tt.length == cnt ? i : (int) Math.round((i + 0.5) * cnt / (double) tt.length - 0.5);
                k = Math.max(0, Math.min(cnt - 1, k));
                int hit = -1;
                for (int d = 0; d <= 3 && hit < 0; d++) {
                    if (k - d >= 0 && nt.equals(ClipAligner.norm(words.get(from + k - d).text))) hit = k - d;
                    else if (k + d < cnt && nt.equals(ClipAligner.norm(words.get(from + k + d).text))) hit = k + d;
                }
                if (hit < 0) continue;
                Word w = words.get(from + hit);
                if (!w.text.equals(tok) && ClipAligner.norm(w.text).equals(nt)) words.set(from + hit, w.withText(tok));
            }
        }
    }

    private static double mid(Word w) { return (w.start + w.end) / 2; }

    /** Same transcript with every time multiplied by {@code f} (speech was sent sped up by f). */
    public Transcript scaled(double f) {
        Transcript o = new Transcript();
        o.language = language;
        for (Segment s : segments) o.segments.add(new Segment(s.start * f, s.end * f, s.text));
        for (Word w : words) o.words.add(new Word(w.text, w.start * f, w.end * f));
        return o;
    }

    /**
     * Joins transcripts of overlapping audio chunks. Part k was cut from {@code cutStart[k]} and owns the time range
     * [keepFrom[k], keepTo[k]); words/segments are kept by the part that owns their start time, so a word spoken across a
     * chunk boundary is taken whole from the earlier chunk (which runs past the boundary) and never duplicated.
     */
    public static Transcript merge(List<Transcript> parts, double[] cutStart, double[] keepFrom, double[] keepTo) {
        int n = parts.size();
        // v8: per boundary, cut where both neighbours are reliable. Whisper often skips the first seconds of an audio
        // piece (seen on this box: the first 7 s of a Hindi clip dropped with language=hi); the previous piece also
        // covers that overlap, so its words are used up to the next piece's first reliable word.
        double[] from = new double[n], to = new double[n];
        for (int k = 0; k < n; k++) { from[k] = keepFrom[k]; to[k] = keepTo[k]; }
        for (int k = 1; k < n; k++) {
            Transcript p = parts.get(k - 1), q = parts.get(k);
            if (p == null || q == null) continue;
            double b = keepFrom[k], prevEnd = Math.min(keepTo[k - 1], b) + (keepFrom[k] - cutStart[k]);   // prev piece's audio end
            double safePrev = prevEnd - 1.5;            // prev piece is trustworthy until shortly before its own cut
            double nf = Double.NaN;                     // next piece's first reliable word (not in its clipped first second)
            for (Word w : q.words) { double st = w.start + cutStart[k]; if (st >= cutStart[k] + 1.0) { nf = st; break; } }
            double lp = Double.NaN;                     // prev piece's last word end inside its trustworthy span
            for (Word w : p.words) { double st = w.start + cutStart[k - 1]; if (st < safePrev) lp = w.end + cutStart[k - 1]; }
            double c = b;
            if (!Double.isNaN(nf) && nf > b) c = Math.min(nf, safePrev);                         // next skipped its opening
            else if (!Double.isNaN(nf) && !Double.isNaN(lp) && lp < b - 2.0 && nf < b) c = Math.max(nf, lp); // prev lost its tail
            from[k] = c; to[k - 1] = c;
        }
        Transcript out = new Transcript();
        for (int k = 0; k < n; k++) {
            Transcript p = parts.get(k);
            if (p == null) continue;
            if (out.language.isEmpty()) out.language = p.language;
            double off = cutStart[k];
            for (Segment s : p.segments) {
                double st = s.start + off;
                if (st >= from[k] && st < to[k]) out.segments.add(new Segment(st, s.end + off, s.text));
            }
            boolean first = true;
            for (Word w : p.words) {
                double st = w.start + off;
                if (st < from[k] || st >= to[k]) continue;
                if (first && !out.words.isEmpty()) {
                    // the same word heard by both pieces right at the cut: keep one
                    Word last = out.words.get(out.words.size() - 1);
                    if (norm(last.text).equals(norm(w.text)) && st < last.end + 0.3) { first = false; continue; }
                }
                first = false;
                out.words.add(new Word(w.text, st, w.end + off));
            }
        }
        return out;
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{P}\\s]+", "");
    }

    /**
     * Words inside [start, end], re-timed so 0 = clip start. Falls back to spreading segment text
     * across the segment duration (weighted by word length) when word timestamps are missing.
     */
    public List<Word> wordsForClip(double start, double end) {
        List<Word> out = new ArrayList<>();
        boolean haveWords = false;
        for (Word w0 : words) {
            if (w0.end <= start || w0.start >= end) continue;
            // v8: a broken character from the recognizer ("�") never reaches a caption
            String clean = w0.text == null ? "" : w0.text.replace("\uFFFD", "").trim();
            if (clean.isEmpty()) continue;
            Word w = clean.equals(w0.text) ? w0 : new Word(clean, w0.start, w0.end);
            haveWords = true;
            double s = Math.max(w.start, start) - start, e = Math.min(w.end, end) - start;
            // whisper often gives a word zero length ("rocket" 15.40-15.40): keep it with a short duration instead of losing it
            if (e - s < 0.05) e = Math.min(s + 0.12, end - start);
            if (e - s >= 0.01) { Word nw = new Word(w.text, s, e); nw.sentenceEnd = w0.sentenceEnd; out.add(nw); }
        }
        for (int i = 0; i + 1 < out.size(); i++) {
            Word a = out.get(i), b = out.get(i + 1);
            if (a.end > b.start) out.set(i, a.retimed(a.start, Math.max(a.start + 0.01, b.start)));
        }
        if (haveWords || words.size() > 0) return out;
        for (Segment sg : segments) {
            if (sg.end <= start || sg.start >= end) continue;
            String[] parts = sg.text.trim().split("\\s+");
            int total = 0;
            for (String p : parts) total += Math.max(1, p.length());
            double dur = sg.end - sg.start, cur = sg.start;
            for (String p : parts) {
                double d = dur * Math.max(1, p.length()) / Math.max(1, total);
                double s = cur, e = cur + d;
                cur = e;
                if (e <= start || s >= end) continue;
                out.add(new Word(p, Math.max(s, start) - start, Math.min(e, end) - start));
            }
        }
        return out;
    }

    /** Compact "[start-end] text" transcript for Gemini, trimmed to fit the prompt budget. */
    public String forPrompt(int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) sb.append(String.format(Locale.US, "[%.1f-%.1f] %s\n", s.start, s.end, s.text));
        String full = sb.toString();
        if (full.length() <= maxChars) return full;
        String[] lines = full.split("\n");
        int step = (int) Math.ceil((double) full.length() / (maxChars * 0.92));
        StringBuilder c = new StringBuilder();
        for (int i = 0; i < lines.length; i += Math.max(1, step)) c.append(lines[i]).append('\n');
        return c.toString();
    }

    /**
     * Numbered "L12 [120.5-125.2] text" lines for Gemini, so it can name the exact line a clip starts/ends on.
     * Over budget, neighbouring lines are joined (never dropped: v3 skipped lines on long videos, so the AI could not
     * see whole parts of a podcast).
     */
    public String forPromptNumbered(int maxChars) {
        int n = segments.size();
        long total = 0;
        for (Segment s : segments) total += s.text.length() + 22;
        int group = (int) Math.max(1, Math.ceil((double) total / Math.max(1000, maxChars)));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i += group) {
            int j = Math.min(n - 1, i + group - 1);
            StringBuilder t = new StringBuilder();
            for (int k = i; k <= j; k++) { if (k > i) t.append(' '); t.append(segments.get(k).text); }
            String label = i == j ? "L" + i : "L" + i + "-L" + j;
            sb.append(String.format(Locale.US, "%s [%.1f-%.1f] %s\n", label, segments.get(i).start, segments.get(j).end, t));
        }
        return sb.toString();
    }

    /**
     * v6 compact prompt form: "12 [120-126] text" (line number, whole seconds). ~17% fewer tokens than v4's
     * "L12 [120.5-126.3] text" with the same information the AI needs (ClipAligner snaps to exact times anyway).
     */
    public String forPromptCompact(int maxChars) {
        int n = segments.size();
        long total = 0;
        for (Segment s : segments) total += s.text.length() + 14;
        int group = (int) Math.max(1, Math.ceil((double) total / Math.max(1000, maxChars)));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i += group) {
            int j = Math.min(n - 1, i + group - 1);
            sb.append(i).append(" [").append(Math.round(segments.get(i).start)).append('-').append(Math.round(segments.get(j).end)).append("] ");
            for (int k = i; k <= j; k++) { if (k > i) sb.append(' '); sb.append(segments.get(k).text.trim()); }
            sb.append('\n');
        }
        return sb.toString();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("language", language);
            JSONArray s = new JSONArray();
            for (Segment g : segments) s.put(new JSONObject().put("start", g.start).put("end", g.end).put("text", g.text));
            o.put("segments", s);
            JSONArray w = new JSONArray();
            for (Word x : words) w.put(new JSONObject().put("word", x.text).put("start", x.start).put("end", x.end));
            o.put("words", w);
        } catch (Exception ignored) {}
        return o;
    }

    public static Transcript fromJson(String json) {
        try { return parseVerboseJson(json, 0); } catch (Exception e) { return new Transcript(); }
    }
}
