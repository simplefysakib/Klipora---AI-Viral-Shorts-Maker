package com.viral.shortsmaker.core;

import com.viral.shortsmaker.captions.Word;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the AI's picked range into a clean short: it starts exactly where a sentence starts (never mid-sentence or
 * mid-word), ends after a complete sentence, stays inside 15-59.5 s and inside the real video duration.
 *
 * Whisper segments are phrases, not sentences (one segment often holds the end of one sentence and the start of the
 * next), so the transcript is first turned into word tokens that know where sentences start/end: punctuation from the
 * segment text (., ?, !, ।), plus clear pauses. Segment edges are used as weaker boundaries (Hindi transcripts often have
 * no punctuation).
 */
public final class ClipAligner {
    private ClipAligner() {}

    /**
     * v12: a short may run long so a question's answer / a story ends where it really ends. v18 (2.9, Sakib): max 1:30;
     * a topic longer than that ends at the last point that concludes before 1:30 (never mid-sentence).
     */
    public static final double MIN_LEN = 20.0, MAX_LEN = 60.0, SOFT_LEN = 50.0;
    /** v19 (3.0, Sakib): most shorts 45 s - 1:30; a podcast short is never under 35 s unless the whole story is shorter */
    public static final double TARGET_LEN = 45.0, POD_MIN_LEN = 35.0;
    static final double PAUSE = 0.7;

    static final class Tok {
        final double start, end;
        boolean sStart, sEnd;      // strong sentence boundary
        boolean punct;             // v18: the word itself ends with . ? ! । (not just a pause)
        boolean richPunct;         // v18: this transcript is well punctuated (then a sentence end needs its full stop)
        boolean wStart, wEnd;      // weak boundary (segment edge)
        String text = "";
        Tok(double s, double e) { start = s; end = e; }
    }

    static boolean endsSentence(String t) {
        if (t == null) return false;
        t = t.trim();
        while (!t.isEmpty() && "\"')\u201D\u2019]".indexOf(t.charAt(t.length() - 1)) >= 0) t = t.substring(0, t.length() - 1).trim();
        return t.endsWith(".") || t.endsWith("?") || t.endsWith("!") || t.endsWith("\u0964") || t.endsWith("\u0965") || t.endsWith("\u2026");
    }

    /**
     * code 32 (5.0): the tokens of the last transcript asked for. Every ClipAligner rule rebuilt the tokens of the WHOLE
     * transcript (a regex split per line, an object per word); the top-up search calls three rules per candidate start, so
     * "Finding viral moments" took ~19 s per pass on a 3 h video on a desktop CPU (minutes on a budget phone, no progress).
     * Keyed by the two lists and a fingerprint of their contents, so an edited transcript is never served stale tokens.
     */
    private static final class TokCache { Object segs, words; long fp; List<Tok> toks; }
    private static volatile TokCache tokCache;

    static long fingerprint(List<Transcript.Segment> segs, List<Word> words) {
        long h = 1125899906842597L;
        if (segs != null) for (Transcript.Segment g : segs) { h = 31 * h + Double.doubleToLongBits(g.start); h = 31 * h + Double.doubleToLongBits(g.end); h = 31 * h + (g.text == null ? 0 : g.text.hashCode()); }
        h = 31 * h + (segs == null ? -1 : segs.size());
        if (words != null) for (Word w : words) { h = 31 * h + Double.doubleToLongBits(w.start); h = 31 * h + Double.doubleToLongBits(w.end); h = 31 * h + (w.text == null ? 0 : w.text.hashCode()); }
        return 31 * h + (words == null ? -1 : words.size());
    }

    /** word tokens with sentence boundaries (falls back to one token per segment without word timings) */
    /** code 32: a transcript held fixed for a search (no fingerprint per call; see pin) */
    private static final ThreadLocal<TokCache> PIN = new ThreadLocal<>();

    /**
     * code 32: while the returned handle is open, this thread's calls for exactly these lists reuse one token list without
     * re-checking the content (the top-up search calls three aligner functions per candidate start; hashing a 3 h
     * transcript each time was most of its cost). The lists must not change while pinned.
     */
    static AutoCloseable pin(List<Transcript.Segment> segs, List<Word> words) {
        final TokCache prev = PIN.get();
        TokCache c = new TokCache();
        c.segs = segs; c.words = words; c.toks = tokens(segs, words);
        PIN.set(c);
        return () -> { if (prev == null) PIN.remove(); else PIN.set(prev); };
    }

    static List<Tok> tokens(List<Transcript.Segment> segs, List<Word> words) {
        TokCache pinned = PIN.get();
        if (pinned != null && pinned.segs == segs && pinned.words == words) return pinned.toks;
        long fp = fingerprint(segs, words);
        TokCache c = tokCache;
        if (c != null && c.segs == segs && c.words == words && c.fp == fp) return c.toks;
        List<Tok> out = buildTokens(segs, words);
        TokCache n = new TokCache();
        n.segs = segs; n.words = words; n.fp = fp; n.toks = java.util.Collections.unmodifiableList(out);
        tokCache = n;
        return n.toks;
    }

    private static List<Tok> buildTokens(List<Transcript.Segment> segs, List<Word> words) {
        List<Tok> out = new ArrayList<>();
        if (words == null || words.isEmpty()) {
            for (Transcript.Segment g : segs) {
                Tok t = new Tok(g.start, g.end);
                t.text = g.text;
                t.sEnd = t.punct = endsSentence(g.text);
                t.wStart = t.wEnd = true;
                out.add(t);
            }
        } else {
            int wi = 0, n = words.size();
            List<String> wordText = new ArrayList<>();
            for (Transcript.Segment g : segs) {
                while (wi < n && mid(words.get(wi)) < g.start - 0.05) { { Tok tw = new Tok(words.get(wi).start, words.get(wi).end); tw.text = words.get(wi).text; out.add(tw); } wordText.add(words.get(wi).text); wi++; }
                int from = out.size();
                while (wi < n && mid(words.get(wi)) < g.end + 0.05) { { Tok tw = new Tok(words.get(wi).start, words.get(wi).end); tw.text = words.get(wi).text; out.add(tw); } wordText.add(words.get(wi).text); wi++; }
                int cnt = out.size() - from;
                final List<String> wt = wordText;
                if (cnt == 0) continue;
                out.get(from).wStart = true;
                out.get(out.size() - 1).wEnd = true;
                String[] tt = g.text.trim().split("\\s+");
                for (int i = 0; i < tt.length; i++) {
                    if (!endsSentence(tt[i])) continue;
                    int k = tt.length == cnt ? i : (int) Math.round((i + 1) * (double) cnt / tt.length) - 1;
                    k = Math.max(0, Math.min(cnt - 1, k));
                    // word counts can differ from the text's token count: find the same word near the estimate
                    String want = norm(tt[i]);
                    if (!want.isEmpty() && !want.equals(norm(wordText.get(from + k)))) {
                        for (int d = 1; d <= 3; d++) {
                            if (k - d >= 0 && want.equals(norm(wordText.get(from + k - d)))) { k = k - d; break; }
                            if (k + d < cnt && want.equals(norm(wordText.get(from + k + d)))) { k = k + d; break; }
                        }
                    }
                    out.get(from + k).sEnd = true;
                    out.get(from + k).punct = true;
                }
            }
            while (wi < n) { { Tok tw = new Tok(words.get(wi).start, words.get(wi).end); tw.text = words.get(wi).text; out.add(tw); } wordText.add(words.get(wi).text); wi++; }
        }
        int np = 0;
        for (Tok t : out) if (endsSentence(t.text)) { t.punct = true; t.sEnd = true; }
        for (Tok t : out) if (t.punct) np++;
        boolean rich = out.size() >= 60 && np * 30 >= out.size();
        for (Tok t : out) t.richPunct = rich;
        for (int i = 0; i < out.size(); i++) {
            Tok t = out.get(i);
            if (i == 0) { t.sStart = true; continue; }
            Tok p = out.get(i - 1);
            if (t.start - p.end >= PAUSE) { p.sEnd = true; t.sStart = true; }
            if (p.sEnd) t.sStart = true;
            if (p.wEnd) t.wStart = true;
        }
        if (!out.isEmpty()) out.get(out.size() - 1).sEnd = true;
        return out;
    }

    static String norm(String s) {
        StringBuilder b = new StringBuilder();
        if (s != null) for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK || Character.getType(c) == Character.COMBINING_SPACING_MARK) b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    private static double mid(Word w) { return (w.start + w.end) / 2; }

    /** v1-compatible entry point (segments only, no line hints) */
    public static double[] align(List<Transcript.Segment> segs, double rawStart, double rawEnd, double maxDur) {
        return align(segs, null, -1, -1, rawStart, rawEnd, maxDur);
    }

    public static double[] align(List<Transcript.Segment> segs, List<Word> words, int startLine, int endLine,
                                 double rawStart, double rawEnd, double maxDur) {
        if (maxDur <= 0) maxDur = Double.MAX_VALUE;
        int ns = segs == null ? 0 : segs.size();
        // the AI's line numbers are a cross-check for its times (it sometimes mistypes a timestamp)
        if (startLine >= 0 && startLine < ns && (rawStart < 0 || Math.abs(segs.get(startLine).start - rawStart) > 20)) rawStart = segs.get(startLine).start;
        if (endLine >= 0 && endLine < ns && (rawEnd <= rawStart || Math.abs(segs.get(endLine).end - rawEnd) > 20)) rawEnd = segs.get(endLine).end;
        if (rawEnd <= rawStart) rawEnd = rawStart + 30;
        if (ns == 0) return clampOnly(rawStart, rawEnd, maxDur);
        List<Tok> tk = tokens(segs, words);
        int n = tk.size();
        if (n == 0) return clampOnly(rawStart, rawEnd, maxDur);

        // ---- start: the sentence start nearest the AI's start (a start just ahead skips a trailing fragment)
        int ti = n - 1;
        for (int i = 0; i < n; i++) if (tk.get(i).end > rawStart - 0.15) { ti = i; break; }
        int si = pickStart(tk, ti, rawStart, true);
        if (si < 0) si = pickStart(tk, ti, rawStart, false);
        if (si < 0) si = ti;
        double st = tk.get(si).start;

        // ---- end: the sentence end nearest the AI's end that keeps the clip inside the length limits
        int tj = si;
        for (int i = si; i < n; i++) { if (tk.get(i).start < rawEnd - 0.1) tj = i; else break; }
        int ei = pickEnd(tk, si, tj, rawEnd, st, true);
        if (ei < 0) ei = pickEnd(tk, si, tj, rawEnd, st, false);
        if (ei < 0) {   // nothing ends in range: last token that fits near the AI's end (not a 2-minute run-on)
            ei = si;
            double lim = Math.min(MAX_LEN, Math.max(SOFT_LEN, rawEnd - st + 6));
            for (int i = si; i < n && tk.get(i).end - st <= lim; i++) ei = i;
        }
        ei = finishThought(tk, si, ei, st);

        double s = st, e;
        double prevEnd = si > 0 ? tk.get(si - 1).end : 0;
        s = Math.max(Math.max(0, s - 0.12), Math.min(s, prevEnd + 0.02));
        // v18: past 1:30 -> the last point that concludes before the limit (2.8 hard-cut at the limit, mid-sentence)
        if (tk.get(ei).end - st > MAX_LEN) e = endFor(segs, words, st, st + MAX_LEN + 5, maxDur);
        else e = padEnd(tk, ei, maxDur);
        if (e > maxDur) e = maxDur;
        if (e - s > MAX_LEN + 0.5) e = s + MAX_LEN;
        if (e - s < 3.0) return clampOnly(rawStart, rawEnd, maxDur);
        return new double[]{s, e};
    }


    /**
     * v12: never end mid-thought. v18: "a thought end" is scored (endStrength >= 3): punctuation, a real pause after the
     * word, a Hindi sentence-final verb, and never a word that cannot end a sentence (aur / ki / ke / se / "and" / "the").
     * If the chosen end is not one, run on to the next one within 10 s (and the length limit); if there is none, step
     * back to the last one that keeps the clip long enough; else the strongest end in reach.
     */
    static int finishThought(List<Tok> tk, int si, int ei, double st) {
        int n = tk.size();
        if (ei < 0 || ei >= n || thoughtEnd(tk, ei)) return ei;
        for (int i = ei + 1; i < n && tk.get(i).end - tk.get(ei).end <= 10 && tk.get(i).end - st <= MAX_LEN; i++)
            if (thoughtEnd(tk, i)) return i;
        for (int i = ei - 1; i > si; i--) {
            if (tk.get(i).end - st < MIN_LEN * 0.75) break;
            if (thoughtEnd(tk, i)) return i;
        }
        int best = ei; double bs = endStrength(tk, ei);
        for (int i = Math.max(si + 1, ei - 40); i < n && tk.get(i).end - st <= MAX_LEN && tk.get(i).end - tk.get(ei).end <= 10; i++) {
            if (tk.get(i).end - st < MIN_LEN * 0.75) continue;
            double s = endStrength(tk, i);
            if (s > bs) { bs = s; best = i; }
        }
        return best;
    }

    /** v18: a place where a clip may end: a complete sentence and a pause (score >= 3) */
    static boolean thoughtEnd(List<Tok> tk, int i) { return endStrength(tk, i) >= 3.0; }

    /** words a sentence cannot end on (Hindi Devanagari, Hinglish roman, English): connectors, postpositions, articles */
    static final java.util.Set<String> NO_END = new java.util.HashSet<>(java.util.Arrays.asList(
            "\u0914\u0930", "\u0915\u093f", "\u0915\u093e", "\u0915\u0947", "\u0915\u094b", "\u092e\u0947\u0902", "\u0938\u0947", "\u0928\u0947",
            "\u092a\u0930", "\u0924\u094b", "\u0932\u0947\u0915\u093f\u0928", "\u092e\u0917\u0930", "\u0915\u094d\u092f\u094b\u0902\u0915\u093f", "\u091c\u094b", "\u091c\u092c", "\u0905\u0917\u0930",
            "\u0909\u0938\u0915\u093e", "\u0907\u0938\u0915\u093e", "\u0909\u0938\u0915\u0940", "\u0909\u0938\u0915\u0947", "\u0907\u0938\u0915\u0947", "\u0905\u092a\u0928\u0947", "\u0905\u092a\u0928\u093e", "\u0939\u092e\u093e\u0930\u093e", "\u092e\u0947\u0930\u093e",
            "\u090f\u0915", "\u092f\u0947", "\u092f\u0939", "\u0935\u094b", "\u0935\u0939", "\u0907\u0938", "\u0909\u0938", "\u0907\u0928", "\u0909\u0928", "\u092f\u093e",
            "\u0938\u092c\u0938\u0947", "\u091c\u0948\u0938\u0947", "\u0915\u094b\u0908", "\u0924\u0915\u093f", "\u092c\u091f", "\u090f\u0902\u0921", "\u0926",
            "aur", "ki", "ka", "ke", "ko", "mein", "se", "ne", "par", "toh", "lekin", "magar", "kyunki", "kyuki", "kyonki", "jo", "jab",
            "agar", "ek", "ye", "yeh", "vo", "wo", "woh", "un", "ya", "sabse", "jaise", "koi", "taki",
            "and", "but", "or", "because", "the", "a", "an", "of", "to", "in", "on", "at", "for", "with", "that", "which", "who",
            "whose", "if", "when", "while", "my", "your", "his", "her", "their", "our", "i", "we",
            "they", "he", "she", "than", "as", "from", "into", "very", "like", "its", "it's", "i'm", "we're"));
    /** words that open a sentence which carries the previous thought on */
    static final java.util.Set<String> CONTINUES = new java.util.HashSet<>(java.util.Arrays.asList(
            "so", "and", "because", "but", "which", "then", "\u0924\u094b", "\u0914\u0930", "\u0915\u094d\u092f\u094b\u0902\u0915\u093f", "\u0932\u0947\u0915\u093f\u0928",
            "\u092e\u0917\u0930", "\u0907\u0938\u0932\u093f\u090f", "\u092b\u093f\u0930", "\u0924\u092c", "aur", "toh", "lekin", "kyunki", "phir", "isliye", "magar"));
    public static boolean isNoEndWordForTest(String w) { return NO_END.contains(norm(w)); }

    /** Hindi sentence-final verbs / particles (a sentence very often ends on these) */
    static final java.util.Set<String> VERB_END = new java.util.HashSet<>(java.util.Arrays.asList(
            "\u0939\u0948", "\u0939\u0948\u0902", "\u0925\u093e", "\u0925\u0940", "\u0925\u0947", "\u0939\u0942\u0902", "\u0939\u0942\u0901", "\u0939\u094b", "\u0917\u092f\u093e", "\u0917\u0908", "\u0917\u090f",
            "\u0926\u093f\u092f\u093e", "\u0932\u093f\u092f\u093e", "\u0930\u0939\u093e", "\u0930\u0939\u0940", "\u0930\u0939\u0947", "\u091a\u093e\u0939\u093f\u090f", "\u0939\u094b\u0917\u093e", "\u0939\u094b\u0917\u0940", "\u0928\u0939\u0940\u0902", "\u0925\u0947\u0964", "\u092f\u093e\u0930", "\u092d\u093e\u0908",
            "hai", "hain", "tha", "thi", "hu", "hoon", "hun", "ho", "gaya", "gayi", "gaye", "diya", "liya", "raha", "rahi", "rahe", "chahiye",
            "hoga", "hogi", "nahi", "nahin", "yaar", "bhai"));

    /**
     * v18: how good an end this word is: punctuation +3, the pause after it (+3 / +2 / +1, a word run straight into the
     * next one -1), Whisper segment edge +1, a Hindi sentence-final verb +1, a word a sentence cannot end on -5.
     */
    static double endStrength(List<Tok> tk, int i) {
        int n = tk.size();
        if (i < 0 || i >= n) return -9;
        if (i == n - 1) return 6;
        Tok t = tk.get(i);
        double gap = tk.get(i + 1).start - t.end, s = 0;
        if (t.punct) s += 3;
        if (gap >= 0.7) s += 3; else if (gap >= 0.45) s += 2; else if (gap >= 0.25) s += 1; else if (gap < 0.08) s -= 1;
        if (t.wEnd) s += 1;
        String w = norm(t.text);
        if (VERB_END.contains(w)) s += 1;
        if (NO_END.contains(w)) s -= 5;
        if (w.length() <= 1 && !w.matches("[0-9]") && !w.equals("i") && !w.equals("a")) s -= 4;    // a cut-off fragment ("थ", "ब")
        if (t.richPunct && !t.punct) s -= 2;                    // punctuated transcript: no full stop = no sentence end
        // the next sentence carries the same thought on ("So I left Barclays…", "और फिर…", "because…"): not the end yet
        if (CONTINUES.contains(norm(tk.get(i + 1).text))) s -= 3.5;
        // ending on a question means its answer comes next: never the end of a clip
        if (t.text.trim().endsWith("?") || isQuestion(sentenceEndingAt(tk, i))) s -= 5;
        return s;
    }

    /** public for tests: the end strength at the word that ends nearest (at or before) {@code end} */
    public static double endStrengthAt(List<Transcript.Segment> segs, List<Word> words, double end) {
        List<Tok> tk = tokens(segs, words);
        int best = -1; double bd = 1e9;
        for (int i = 0; i < tk.size(); i++) { double d = Math.abs(tk.get(i).end - end); if (tk.get(i).end <= end + 0.05 && d < bd) { bd = d; best = i; } }
        return best < 0 ? -9 : endStrength(tk, best);
    }

    /**
     * v18 (2.9): the end of a clip that starts at {@code start} and whose topic ends at {@code want} (the AI's end).
     * Snaps to the best thought end near it; if the topic runs past MAX_LEN, the clip ends at the LAST strong point
     * conclusion before the limit. Returns the end time with a small tail pad (never into the next word).
     */
    public static double endFor(List<Transcript.Segment> segs, List<Word> words, double start, double want, double maxDur) {
        return endFor(segs, words, start, want, maxDur, MIN_LEN, false);
    }

    /**
     * v19 (3.0): length-aware end. 2.9 made a 17 s podcast short in his log: the topic ran past 1:30, no strong end was
     * found between 20 s and 1:30 on his unpunctuated Hindi transcript, and the last fallback took the single strongest
     * word end anywhere after 15 s (at 17 s). Now every branch prefers ends that make a 45-90 s short, never goes below
     * {@code minLen} (35 s for podcasts) while a usable end exists after it, and only allows a shorter clip when the AI
     * said the story really ends there ({@code storyEndsAtWant}).
     */
    public static double endFor(List<Transcript.Segment> segs, List<Word> words, double start, double want, double maxDur,
                                double minLen, boolean storyEndsAtWant) {
        List<Tok> tk = tokens(segs, words);
        int n = tk.size();
        if (n == 0) return Math.min(want, start + MAX_LEN);
        double lim = Math.min(start + MAX_LEN, maxDur > 0 ? maxDur : Double.MAX_VALUE);
        double room = lim - start;
        // the shortest acceptable clip: minLen, unless the story really ends earlier (then the old 20 s floor)
        // (the AI marks almost every story "complete", so that only lowers a podcast's floor from 35 s to 30 s)
        double floor = storyEndsAtWant ? Math.max(MIN_LEN, minLen - 5) : minLen;
        floor = Math.max(MIN_LEN * 0.75, Math.min(floor, room - 2));
        double target = Math.min(TARGET_LEN, Math.max(floor, room - 2));
        int si = 0;
        while (si < n - 1 && tk.get(si).end <= start + 0.05) si++;
        int pick = -1;
        if (want > lim - 0.3) {
            // the topic runs past 1:30: the LAST place a point concludes before the limit, in a 45-90 s clip when possible
            outer:
            for (double minL : new double[]{target, floor}) {
                for (double need : new double[]{5, 4, 3}) {
                    for (int i = n - 1; i > si; i--) {
                        Tok t = tk.get(i);
                        if (t.end > lim - 0.3) continue;
                        if (t.end - start < minL) break;
                        if (endStrength(tk, i) >= need) { pick = i; break outer; }
                    }
                }
            }
        } else {
            // a clip shorter than the floor is not a finished short: look for the first good end after it
            boolean wasShort = want - start < floor;
            if (wasShort) want = start + floor;
            double reach = wasShort ? want + 15 : want + 8;
            double best = -1e9;
            for (int i = si; i < n; i++) {
                Tok t = tk.get(i);
                if (t.end > lim - 0.3) break;
                if (t.end < want - 6 || t.end - start < floor) continue;
                if (t.end > reach) break;
                double s = endStrength(tk, i);
                if (s < 3) continue;
                double len = t.end - start;
                double sc = s - 0.35 * Math.abs(t.end - want) - (t.end < want ? 0.25 * (want - t.end) : 0) + (len >= target ? 0.5 : 0);
                if (sc > best) { best = sc; pick = i; }
            }
            if (pick < 0) {
                // nothing good near the AI's end: the nearest complete sentence + pause inside the limits (above the floor first)
                for (double minL : new double[]{floor, MIN_LEN * 0.75}) {
                    double bd = 1e9;
                    for (int i = si; i < n; i++) {
                        Tok t = tk.get(i);
                        if (t.end > lim - 0.3) break;
                        if (t.end - start < minL) continue;
                        if (endStrength(tk, i) < 3) continue;
                        double d = Math.abs(t.end - want) * (t.end < want ? 1.3 : 1.0);
                        if (d < bd) { bd = d; pick = i; }
                    }
                    if (pick >= 0) break;
                }
            }
            if (pick < 0) {
                int wi = si;
                for (int i = si; i < n && tk.get(i).end <= want + 0.05; i++) wi = i;
                pick = finishThought(tk, si, wi, start);
                if (pick >= 0 && tk.get(pick).end - start < floor) pick = -1;
            }
        }
        if (pick < 0 && floor > 25) {
            // nothing complete between the floor and the limit (an unpunctuated stretch): a real sentence end at 25 s+ is
            // better than a 45 s clip that stops mid-sentence (his "never mid-sentence" beats "45 s" when both can't hold)
            int best = -1;
            for (int i = si; i < n; i++) {
                Tok t = tk.get(i);
                if (t.end > start + floor || t.end > lim - 0.3) break;
                if (t.end - start < 25) continue;
                if (endStrength(tk, i) >= 3) best = i;   // the latest one
            }
            boolean anyOk = false;
            for (int i = si; i < n && !anyOk; i++) {
                Tok t = tk.get(i);
                if (t.end > lim - 0.3) break;
                if (t.end - start >= floor && endStrength(tk, i) >= 3) anyOk = true;
            }
            if (best >= 0 && !anyOk) pick = best;
        }
        if (pick < 0) {
            // no clear conclusion before the limit (no punctuation, no pauses): the best word end in a clip of the right
            // length (strength first, then a 45-90 s length), latest of equals - never the strongest end at 17 s
            for (double minL : new double[]{floor, MIN_LEN * 0.75}) {
                double bs = -1e9;
                for (int i = si; i < n; i++) {
                    Tok t = tk.get(i);
                    if (t.end > lim - 0.3) break;
                    double len = t.end - start;
                    if (len < minL) continue;
                    // strength first; among similar ends the one nearest the AI's end (it read the meaning), with a
                    // small nudge towards a 45 s+ short
                    double s = endStrength(tk, i) + (len >= target ? 0.5 : 0) - 0.04 * Math.abs(t.end - Math.min(want, lim));
                    if (s >= bs) { bs = s; pick = i; }
                }
                if (pick >= 0) break;
            }
        }
        if (pick < 0) return Math.min(want, lim);
        return padEnd(tk, pick, lim);
    }

    /** a small tail after the last word (the word's sound often runs past Whisper's end time), never into the next word */
    static double padEnd(List<Tok> tk, int i, double lim) {
        double e = tk.get(i).end;
        double next = i + 1 < tk.size() ? tk.get(i + 1).start : Double.MAX_VALUE;
        double padded = Math.min(e + 0.45, Math.max(e, next - 0.06));
        return Math.min(padded, Math.max(e, lim));
    }

    /**
     * v18: the transcript as short phrases (split at punctuation, real pauses, segment edges, or every ~14 words) for
     * the AI's ending pass, so it can choose an end INSIDE a long Whisper segment. Each item: {startSec, endSec, text}.
     */
    public static List<Object[]> phrases(List<Transcript.Segment> segs, List<Word> words, double from, double to) {
        List<Tok> tk = tokens(segs, words);
        List<Object[]> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        double ps = -1;
        int cnt = 0;
        for (int i = 0; i < tk.size(); i++) {
            Tok t = tk.get(i);
            if (t.end <= from + 0.05) continue;
            if (t.start >= to) break;
            if (ps < 0) ps = t.start;
            b.append(t.text.trim()).append(' ');
            cnt++;
            double gap = i + 1 < tk.size() ? tk.get(i + 1).start - t.end : 9;
            boolean cut = t.punct || gap >= 0.3 || (t.wEnd && gap >= 0.12) || cnt >= 14 || i == tk.size() - 1;
            if (cut) { out.add(new Object[]{ps, t.end, b.toString().trim()}); b.setLength(0); ps = -1; cnt = 0; }
        }
        if (b.length() > 0) out.add(new Object[]{ps, tk.get(Math.min(tk.size() - 1, Math.max(0, tk.size() - 1))).end, b.toString().trim()});
        return out;
    }

    /** v12 (report/tests): the last sentence of a range, as spoken (max 18 words) */
    public static String closingLine(List<Transcript.Segment> segs, List<Word> words, double start, double end) {
        List<Tok> tk = tokens(segs, words);
        int last = -1;
        for (int i = 0; i < tk.size(); i++) if (tk.get(i).end <= end + 0.05 && tk.get(i).start >= start - 0.05) last = i;
        if (last < 0) return "";
        int first = last;
        while (first > 0 && last - first < 17 && !tk.get(first).sStart && tk.get(first - 1).start >= start - 0.05) first--;
        StringBuilder b = new StringBuilder();
        for (int i = first; i <= last; i++) b.append(tk.get(i).text.trim()).append(' ');
        return b.toString().trim();
    }

    /** public for tests: does the range end on a sentence end (full stop, ?, !, । or a real pause)? */
    public static boolean endsOnThought(List<Transcript.Segment> segs, List<Word> words, double end) {
        List<Tok> tk = tokens(segs, words);
        int best = -1; double bd = 1e9;
        for (int i = 0; i < tk.size(); i++) { double d = Math.abs(tk.get(i).end - end); if (tk.get(i).end <= end + 0.05 && d < bd) { bd = d; best = i; } }
        return best >= 0 && thoughtEnd(tk, best);
    }

    private static boolean isStart(Tok t, boolean strong) { return strong ? t.sStart : (t.sStart || t.wStart); }
    private static boolean isEnd(Tok t, boolean strong) { return strong ? t.sEnd : (t.sEnd || t.wEnd); }

    static int pickStart(List<Tok> tk, int ti, double rawStart, boolean strong) {
        if (isStart(tk.get(ti), strong) && Math.abs(tk.get(ti).start - rawStart) < 2.5) return ti;
        int fwd = -1, back = -1;
        for (int i = ti; i < tk.size() && tk.get(i).start - rawStart <= 6; i++)
            if (tk.get(i).start >= rawStart - 0.3 && isStart(tk.get(i), strong)) { fwd = i; break; }
        for (int i = Math.min(ti, tk.size() - 1); i >= 0 && rawStart - tk.get(i).start <= 12; i--)
            if (tk.get(i).start <= rawStart + 0.3 && isStart(tk.get(i), strong)) { back = i; break; }
        if (fwd >= 0 && tk.get(fwd).start - rawStart <= 2.5) return fwd;
        if (back >= 0) return back;
        return fwd;
    }

    static int pickEnd(List<Tok> tk, int si, int tj, double rawEnd, double st, boolean strong) {
        int n = tk.size();
        int fwd = -1, back = -1;
        // v12: the AI picks where the topic ends (up to 120 s); here we only snap to a sentence end close to it
        for (int i = tj; i < n && tk.get(i).end - st <= MAX_LEN && tk.get(i).end - rawEnd <= 15; i++) if (isEnd(tk.get(i), strong)) { fwd = i; break; }
        for (int i = Math.min(tj, n - 1); i >= si; i--) {
            if (tk.get(i).end - st > MAX_LEN) continue;
            if (isEnd(tk.get(i), strong)) { back = i; break; }
        }
        boolean fwdOk = fwd >= 0 && tk.get(fwd).end - st >= MIN_LEN;
        boolean backOk = back >= 0 && tk.get(back).end - st >= MIN_LEN;
        if (fwdOk && (tk.get(fwd).end - rawEnd <= 6 || !backOk)) return fwd;
        if (backOk) {
            // prefer the earlier end only when it is close; otherwise finish the thought
            if (fwdOk && rawEnd - tk.get(back).end > tk.get(fwd).end - rawEnd) return fwd;
            return back;
        }
        if (fwd >= 0) {
            // too short so far: add whole sentences until it is long enough (still within the limit)
            int k = fwd;
            while (tk.get(k).end - st < MIN_LEN) {
                int nx = -1;
                for (int i = k + 1; i < n && tk.get(i).end - st <= SOFT_LEN; i++) if (isEnd(tk.get(i), strong)) { nx = i; break; }
                if (nx < 0) break;
                k = nx;
            }
            return k;
        }
        // v12: a sentence end that leaves the clip too short is not an answer in the strong pass: let the weak pass
        // (segment edges near the AI's end) decide instead of cutting the topic short
        return strong && !backOk ? -1 : back;
    }

    // ------------------------------------------------------------------ v7 hook rule

    /** openers that only make sense after earlier context (Hinglish/Hindi roman + Devanagari, English) or are noise */
    static final String[] FILLER_PHRASES = {
            "jaise maine kaha", "jaisa maine kaha", "jaise ki maine kaha", "jaisa ki maine bola", "jaise maine bola", "haan to", "haan toh", "han to", "han toh", "to phir", "toh phir",
            "like i said", "as i said", "that's why", "thats why", "and then", "so yeah", "जैसे मैंने कहा", "जैसा मैंने कहा", "हाँ तो", "हां तो", "तो फिर",
    };
    static final String[] FILLER_WORDS = {
            "aur", "or", "toh", "to", "tho", "matlab", "wahi", "vahi", "wohi", "isliye", "isiliye", "lekin", "par", "kyunki", "kyonki", "phir", "fir",
            "and", "so", "but", "because", "cause", "also", "then", "anyway", "plus",
            "um", "umm", "uh", "uhh", "hmm", "ah", "er",
            "और", "तो", "मतलब", "वही", "इसलिए", "इसीलिए", "लेकिन", "पर", "क्योंकि", "फिर",
    };
    static final java.util.Set<String> TO_OK = new java.util.HashSet<>(java.util.Arrays.asList(
            "be", "the", "a", "an", "me", "you", "my", "your", "all", "get", "make", "do", "become", "understand", "succeed", "win", "this", "start", "build", "put"));
    /** fillers that can simply be dropped when the rest of the line stands on its own */
    static final java.util.Set<String> DROPPABLE = new java.util.HashSet<>(java.util.Arrays.asList(
            "and", "so", "um", "umm", "uh", "uhh", "hmm", "ah", "er", "aur", "toh", "to", "tho", "और", "तो"));

    static String firstWords(String text, int n) {
        StringBuilder b = new StringBuilder();
        int c = 0;
        for (String w : text.trim().split("\\s+")) {
            String x = w.toLowerCase(java.util.Locale.ROOT).replaceAll("^[\\p{Punct}\u201C\u201D\u2018\u2019\u0964]+|[\\p{Punct}\u201C\u201D\u2018\u2019\u0964]+$", "");
            if (x.isEmpty()) continue;
            if (c++ > 0) b.append(' ');
            b.append(x);
            if (c >= n) break;
        }
        return b.toString();
    }

    /** true when a line starts with a context-dependent connector/filler ("aur", "toh", "so", "like I said" ...) */
    public static boolean startsWithFiller(String text) {
        if (text == null) return false;
        String head = firstWords(text, 5);
        if (head.isEmpty()) return false;
        for (String p : FILLER_PHRASES) if (head.equals(p) || head.startsWith(p + " ")) return true;
        String[] hw = head.split(" ");
        String w0 = hw[0];
        // English "To be honest..." / "To make it..." is a real opener; Hinglish "to"/"toh" is a connector
        if (w0.equals("to") && hw.length > 1 && TO_OK.contains(hw[1])) return false;
        for (String f : FILLER_WORDS) if (w0.equals(f)) return true;
        return false;
    }

    private static String textFrom(List<Tok> tk, int i, int n) {
        StringBuilder b = new StringBuilder();
        for (int k = i; k < tk.size() && k < i + n; k++) { if (k > i) b.append(' '); b.append(tk.get(k).text == null ? "" : tk.get(k).text); }
        return b.toString();
    }

    /** the clip's opening sentence as spoken (stops at the first sentence end, max 15 words) */
    public static String openingLine(List<Transcript.Segment> segs, List<Word> words, double start, double end) {
        if (segs == null || segs.isEmpty()) return "";
        List<Tok> tk = tokens(segs, words);
        boolean wordLevel = words != null && !words.isEmpty();
        int i = 0;
        while (i < tk.size() && tk.get(i).end <= start + 0.05) i++;
        StringBuilder b = new StringBuilder();
        int c = 0;
        for (int k = i; k < tk.size() && tk.get(k).start < end; k++) {
            String t = tk.get(k).text == null ? "" : tk.get(k).text.trim();
            if (!wordLevel) return clip(t, 15);
            if (t.isEmpty()) continue;
            if (c++ > 0) b.append(' ');
            b.append(t);
            if (c >= 15 || (tk.get(k).sEnd && c >= 3)) break;
        }
        return b.toString().trim();
    }

    private static String clip(String t, int maxWords) {
        String[] w = t.split("\\s+");
        if (w.length <= maxWords) return t;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < maxWords; i++) { if (i > 0) b.append(' '); b.append(w[i]); }
        return b + "…";
    }

    /**
     * v7 hook rule on an aligned range: when the clip opens with a context-dependent filler ("aur", "toh", "so" ...),
     * move its start to the nearest clean sentence start (ahead first, then back), or drop a lone leading filler
     * word when the rest stands on its own. Returns {start, end, how} (how: 0 clean, 1 moved, 2 filler dropped)
     * or null when no clean opening exists (the caller skips the clip).
     */
    public static double[] hookSafe(List<Transcript.Segment> segs, List<Word> words, double[] r, double maxDur) {
        if (segs == null || segs.isEmpty()) return new double[]{r[0], r[1], 0};
        List<Tok> tk = tokens(segs, words);
        boolean wordLevel = words != null && !words.isEmpty();
        int n = tk.size();
        int si = 0;
        while (si < n && tk.get(si).end <= r[0] + 0.05) si++;
        if (si >= n) return null;
        int look = wordLevel ? 6 : 1;
        // v11: a question behind a connector ("But how much…?", "So what…?", "Toh kya…?"): start at the question itself
        if (wordLevel && startsWithFiller(textFrom(tk, si, look)) && !opensMidSentence(tk, si)) {
            for (int k = si + 1; k <= si + 2 && k < n; k++) {
                if (tk.get(k).start - tk.get(k - 1).end > 0.8) break;
                String rest = sentenceAt(tk, k);
                if (!startsWithFiller(rest) && isQuestion(rest) && r[1] - tk.get(k).start >= MIN_LEN - 8)
                    return new double[]{Math.max(tk.get(k - 1).end, tk.get(k).start - 0.08), r[1], 2};
            }
        }
        boolean tail = wordLevel && opensMidSentence(tk, si);
        if (!tail && !startsWithFiller(textFrom(tk, si, look))) return new double[]{r[0], r[1], 0};
        double st = tk.get(si).start;
        // clean sentence starts nearby (v8: a Whisper segment start counts too — Hindi transcripts carry little punctuation)
        int fwd = -1, back = -1;
        for (int i = si + 1; i < n && tk.get(i).start - st <= 12; i++)
            if (cleanStart(tk, i, look)) { fwd = i; break; }
        for (int i = si - 1; i >= 0 && st - tk.get(i).start <= 12; i--)
            if (cleanStart(tk, i, look)) { back = i; break; }
        double fd = fwd >= 0 ? tk.get(fwd).start - st : Double.MAX_VALUE, bd = back >= 0 ? st - tk.get(back).start : Double.MAX_VALUE;
        int pick = fd <= 6 ? fwd : (back >= 0 ? back : fwd);
        if (pick >= 0) {
            double ns = tk.get(pick).start;
            double[] a = align(segs, words, -1, -1, ns, Math.max(r[1], ns + MIN_LEN), maxDur);
            if (Math.abs(a[0] - ns) < 0.5 && a[1] - a[0] >= MIN_LEN - 0.5 && !startsWithFiller(openingLine(segs, words, a[0], a[1])))
                return new double[]{a[0], a[1], 1};
        }
        // a lone leading filler word ("So, what is radiation?"): start at the next word
        if (wordLevel && !tail && si + 1 < n) {
            String w0 = firstWords(tk.get(si).text, 1);
            Tok nx = tk.get(si + 1);
            if (DROPPABLE.contains(w0) && nx.start - tk.get(si).end < 0.8 && !startsWithFiller(textFrom(tk, si + 1, look))
                    && r[1] - nx.start >= MIN_LEN - 2) {
                double s = Math.max(tk.get(si).end, nx.start - 0.08);
                return new double[]{s, r[1], 2};
            }
        }
        return null;
    }

    /**
     * v8: true when the clip would open on the tail of the previous sentence ("doing? Have we become decent?",
     * "response. What are everyone saying?") or in the middle of one: the first word is not a sentence/segment start
     * and either a sentence/segment ends within the first 4 words, or the word before it doesn't end one.
     */
    static boolean opensMidSentence(List<Tok> tk, int si) {
        Tok t0 = tk.get(si);
        if (t0.sStart || t0.wStart) return false;
        if (si > 0) { Tok p = tk.get(si - 1); if (p.sEnd || endsSentence(p.text)) return false; }
        return true;
    }

    /** a token where a clip may begin: a sentence or segment start, not a filler, not followed by a sentence end in 1-2 words */
    static boolean cleanStart(List<Tok> tk, int i, int look) {
        Tok t = tk.get(i);
        boolean prevEnds = i > 0 && (tk.get(i - 1).sEnd || endsSentence(tk.get(i - 1).text));
        if (!(t.sStart || t.wStart || prevEnds)) return false;
        return !startsWithFiller(textFrom(tk, i, look));
    }

    /** public for tests: would this range open on the tail of a previous sentence? */
    public static boolean opensMidSentence(List<Transcript.Segment> segs, List<Word> words, double start) {
        if (words == null || words.isEmpty()) return false;
        List<Tok> tk = tokens(segs, words);
        int si = 0;
        while (si < tk.size() && tk.get(si).end <= start + 0.05) si++;
        return si < tk.size() && opensMidSentence(tk, si);
    }

    static double[] clampOnly(double rawStart, double rawEnd, double maxDur) {
        double s = Math.max(0, rawStart), e = Math.min(rawEnd, maxDur);
        if (e - s > MAX_LEN) e = s + MAX_LEN;
        if (e - s < 3.0) { e = Math.min(maxDur, s + 30); if (e - s < 3.0) s = Math.max(0, e - Math.min(30.0, maxDur)); }
        return new double[]{s, e};
    }

    // ------------------------------------------------------------------ v11 question-led clips

    /** question words: Hinglish (roman), Hindi (Devanagari) — anywhere in a short sentence; English — first word */
    static final java.util.Set<String> Q_HI = new java.util.HashSet<>(java.util.Arrays.asList(
            "kya", "kyaa", "kyun", "kyon", "kyu", "kyo", "kaise", "kaisa", "kaisi", "kaun", "kon", "kaunsa", "kaunsi", "konsa", "konsi", "kab", "kitna", "kitni", "kitne",
            "kahan", "kahaan", "kaha", "kidhar", "kisne", "kiska", "kiski", "kiske", "kisko", "kise",
            "क्या", "क्यों", "क्यूँ", "क्यूं", "कैसे", "कैसा", "कैसी", "कौन", "कौनसा", "कौनसी", "कब", "कितना", "कितनी", "कितने", "कहाँ", "कहां", "किधर",
            "किसने", "किसका", "किसकी", "किसके", "किसको", "किसे"));
    static final java.util.Set<String> Q_EN_WH = new java.util.HashSet<>(java.util.Arrays.asList(
            "what", "what's", "whats", "why", "how", "how's", "who", "who's", "whom", "whose", "when", "where", "which"));
    static final java.util.Set<String> Q_EN_AUX = new java.util.HashSet<>(java.util.Arrays.asList(
            "do", "does", "did", "is", "are", "was", "were", "have", "has", "had", "can", "could", "would", "will", "should", "shall", "may", "might",
            "don't", "doesn't", "didn't", "isn't", "aren't", "wasn't", "haven't", "can't", "won't", "wouldn't", "shouldn't"));
    static final java.util.Set<String> Q_EN_SUBJ = new java.util.HashSet<>(java.util.Arrays.asList(
            "you", "we", "i", "he", "she", "they", "it", "there", "that", "this", "people", "anyone", "someone", "your", "the", "a"));

    /** true when a spoken sentence is a question ("Aapne Arjun Reddy se shaadi ki?", "kya aap…", "क्यों…", "Why did you…") */
    public static boolean isQuestion(String sentence) {
        if (sentence == null) return false;
        String t = sentence.trim();
        if (t.isEmpty()) return false;
        if (t.indexOf('?') >= 0 || t.indexOf('\uFF1F') >= 0) return true;
        String[] w = firstWords(t, 40).split(" ");
        if (w.length == 0 || w[0].isEmpty()) return false;
        if (Q_EN_WH.contains(w[0])) return true;
        if (Q_EN_AUX.contains(w[0]) && w.length > 2 && Q_EN_SUBJ.contains(w[1])) return true;
        // Hindi/Hinglish: the question word opens the sentence ("kya aap…", "क्यों…") or closes it ("…shaadi ki kya", "…kab?").
        // In the middle it is usually an indirect question ("pata nahi ki kya hua"), which is not a hook.
        if (w.length <= 18) {
            for (int i = 0; i < Math.min(3, w.length); i++) if (Q_HI.contains(w[i])) return i == 0 || !(w[i - 1].equals("ki") || w[i - 1].equals("कि"));
            for (int i = Math.max(3, w.length - 2); i < w.length; i++) if (Q_HI.contains(w[i])) return true;
        }
        return false;
    }

    /**
     * v11: where a question-led short should start, or -1. The short must open on the question itself (so anyone gets
     * the topic) and keep the answer. Uses the AI's question line when it is just before / at the start of the clip,
     * else a question sentence that ends right before the clip's first sentence (the clip opened on the answer).
     * Only moves the start back up to 25 s, or forward up to 15 s past a preamble, and keeps at least 12 s of answer.
     */
    public static double questionStart(List<Transcript.Segment> segs, List<Word> words, double start, double end, int qLine, boolean aiSaysQa) {
        if (segs == null || segs.isEmpty()) return -1;
        if (qLine >= 0 && qLine < segs.size()) {
            double qs = segs.get(qLine).start;
            boolean before = qs <= start + 0.3 && start - qs <= 25;
            boolean inside = qs > start + 0.3 && qs - start <= 15 && end - qs >= 12;
            if ((before || inside) && (aiSaysQa || isQuestion(sentenceFrom(segs, words, qs)))) return snapWordStart(words, qs);
        }
        List<Tok> tk = tokens(segs, words);
        int n = tk.size(), si = 0;
        while (si < n && tk.get(si).end <= start + 0.05) si++;
        if (si >= n || si == 0) return -1;
        if (isQuestion(sentenceAt(tk, si))) return -1;          // already opens on a question
        // the sentence right before the clip
        int pe = si - 1;
        if (tk.get(si).start - tk.get(pe).end > 4.0) return -1;
        int ps = pe;
        while (ps > 0 && !tk.get(ps).sStart && !tk.get(ps).wStart && tk.get(pe).end - tk.get(ps - 1).start <= 20) ps--;
        if (start - tk.get(ps).start > 25) return -1;
        StringBuilder b = new StringBuilder();
        for (int k = ps; k <= pe; k++) { if (k > ps) b.append(' '); b.append(tk.get(k).text == null ? "" : tk.get(k).text); }
        return isQuestion(b.toString()) ? tk.get(ps).start : -1;
    }

    /** v18: the sentence that ends at token i (back to its start, max 25 words) */
    static String sentenceEndingAt(List<Tok> tk, int i) {
        int a = i;
        while (a > 0 && i - a < 24 && !tk.get(a).sStart && !tk.get(a - 1).sEnd) a--;
        StringBuilder b = new StringBuilder();
        for (int k = a; k <= i; k++) { if (k > a) b.append(' '); b.append(tk.get(k).text == null ? "" : tk.get(k).text.trim()); }
        return b.toString();
    }

    private static String sentenceAt(List<Tok> tk, int i) {
        StringBuilder b = new StringBuilder();
        for (int k = i; k < tk.size() && k < i + 25; k++) {
            if (k > i && (tk.get(k).sStart || tk.get(k).wStart)) break;
            if (k > i) b.append(' ');
            b.append(tk.get(k).text == null ? "" : tk.get(k).text);
            if (tk.get(k).sEnd) break;
        }
        return b.toString();
    }

    static String sentenceFrom(List<Transcript.Segment> segs, List<Word> words, double at) {
        List<Tok> tk = tokens(segs, words);
        int i = 0;
        while (i < tk.size() && tk.get(i).end <= at + 0.05) i++;
        return i < tk.size() ? sentenceAt(tk, i) : "";
    }

    private static double snapWordStart(List<Word> words, double t) {
        if (words == null) return t;
        for (Word w : words) if (w.end > t + 0.05) return Math.min(t + 1.0, Math.max(t - 0.3, w.start));
        return t;
    }

    /**
     * v11: where the AI's hook line (its exact first sentence, copied from the transcript) really starts, or -1. The AI's
     * own seconds are often a few seconds off its line numbers; the words it copied are not. Searches 40 s around the
     * hint and needs most of the first words to match.
     */
    public static double locateHook(List<Word> words, String hook, double hint) {
        if (words == null || words.isEmpty() || hook == null || hint < 0) return -1;
        String[] h = firstWords(hook, 6).split(" ");
        int k = h.length;
        if (k < 2 || h[0].isEmpty()) return -1;
        int best = -1, bestScore = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            if (w.start < hint - 40) continue;
            if (w.start > hint + 40) break;
            int sc = 0;
            for (int j = 0; j < k && i + j < words.size(); j++) if (same(firstWords(words.get(i + j).text, 1), h[j])) sc++;
            double dist = Math.abs(w.start - hint);
            if (sc > bestScore || (sc == bestScore && sc > 0 && dist < bestDist)) { best = i; bestScore = sc; bestDist = dist; }
        }
        if (best < 0 || bestScore < Math.max(2, (int) Math.ceil(0.6 * k))) return -1;
        return words.get(best).start;
    }

    /**
     * v18: where the AI's closing line (its exact last sentence, copied) really ENDS: the end time of its last word, or -1.
     * Matches the last up-to-6 words, searching from the clip start to 40 s past the hint.
     */
    public static double locateEnd(List<Word> words, String endText, double hint, double clipStart) {
        if (words == null || words.isEmpty() || endText == null || hint < 0) return -1;
        String[] all = endText.trim().split("\\s+");
        int k = Math.min(6, all.length);
        if (k < 2) return -1;
        String[] h = new String[k];
        for (int j = 0; j < k; j++) h[j] = firstWords(all[all.length - k + j], 1);
        int best = -1, bestScore = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = k - 1; i < words.size(); i++) {
            Word w = words.get(i);
            if (w.end < clipStart + 5) continue;
            if (w.end > hint + 40 || w.end > clipStart + MAX_LEN + 30) break;
            int sc = 0;
            for (int j = 0; j < k; j++) if (same(firstWords(words.get(i - k + 1 + j).text, 1), h[j])) sc++;
            double dist = Math.abs(w.end - hint);
            if (sc > bestScore || (sc == bestScore && sc > 0 && dist < bestDist)) { best = i; bestScore = sc; bestDist = dist; }
        }
        if (best < 0 || bestScore < Math.max(2, (int) Math.ceil(0.6 * k))) return -1;
        return words.get(best).end;
    }

    private static boolean same(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        int m = Math.min(a.length(), b.length());
        return m >= 4 && a.regionMatches(0, b, 0, m - 1);
    }

    /**
     * v11: start the clip on the exact word the AI's hook line begins with, when that word starts a sentence or a
     * transcript segment (or follows a pause). Unpunctuated transcripts have few "strong" sentence starts, and the
     * nearest one can be 10 s away in another sentence. Keeps the end (re-fitted when the length leaves 20-59.5 s).
     */
    public static double[] startAt(List<Transcript.Segment> segs, List<Word> words, double hs, double[] r, double maxDur) {
        if (hs < 0 || segs == null || segs.isEmpty()) return r;
        List<Tok> tk = tokens(segs, words);
        int i = -1;
        for (int k = 0; k < tk.size(); k++) if (tk.get(k).start >= hs - 0.05) { i = k; break; }
        if (i < 0) return r;
        Tok t = tk.get(i);
        boolean boundary = t.sStart || t.wStart || i == 0 || tk.get(i - 1).sEnd || endsSentence(tk.get(i - 1).text) || t.start - tk.get(i - 1).end >= 0.3;
        if (!boundary) return r;
        double prevEnd = i > 0 ? tk.get(i - 1).end : 0;
        double s = Math.max(Math.max(0, t.start - 0.12), Math.min(t.start, prevEnd + 0.02));
        if (Math.abs(s - r[0]) < 0.3) return r;
        double e = r[1];
        if (e - s < MIN_LEN - 2 || e - s > MAX_LEN + 0.5) {
            double[] a = align(segs, words, -1, -1, t.start, e - s < MIN_LEN - 2 ? s + MIN_LEN + 8 : s + SOFT_LEN - 2, maxDur);
            if (Math.abs(a[0] - s) > 0.3) {
                // align snapped elsewhere: keep our start, take the last token end that fits
                double end = s;
                for (int k = i; k < tk.size() && tk.get(k).end - s <= MAX_LEN; k++) { end = tk.get(k).end; if (end - s >= MIN_LEN && (tk.get(k).sEnd || tk.get(k).wEnd)) break; }
                return end - s >= 3 ? new double[]{s, Math.min(end + 0.3, maxDur)} : r;
            }
            return a;
        }
        return new double[]{s, e};
    }
}
