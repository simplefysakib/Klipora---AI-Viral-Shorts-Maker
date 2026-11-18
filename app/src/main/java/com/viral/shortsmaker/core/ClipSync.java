package com.viral.shortsmaker.core;

import com.viral.shortsmaker.captions.Word;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v9 caption sync. v8 timed every caption from the FULL-video transcript (8 parts stitched together) and never checked
 * that time against the short that was actually rendered; on Sakib's phone every caption came out ~8.1 s late (a
 * constant shift: the first 8 s of captions were words spoken before the short even starts).
 *
 * v9 times captions from the short itself: its finished audio is transcribed again (word timestamps, language
 * locked), so 0 s = the first frame he sees. Everything here is pure logic (unit-tested on the JVM):
 *  - clip words from a clip transcript, cleaned and clamped to the clip;
 *  - word times inside a segment when a recognizer gave none, snapped to real pauses in the audio (never spread evenly);
 *  - the audio offset between the short and the source (cross-correlation of loudness envelopes), used when the
 *    clip could not be transcribed so the full transcript's words can still be shifted onto the short's timeline;
 *  - the median time difference between two word lists of the same speech (diagnostics + tests).
 */
public final class ClipSync {
    private ClipSync() {}

    public static final double HOP = 0.01;   // 10 ms envelope frames

    /** words of a clip transcript (times already relative to the clip file), cleaned and inside [0, dur] */
    public static List<Word> clipWords(Transcript t, double dur) {
        List<Word> out = new ArrayList<>();
        if (t == null) return out;
        for (Word w0 : t.words) {
            String clean = w0.text == null ? "" : w0.text.replace("\uFFFD", "").trim();
            if (clean.isEmpty() || Double.isNaN(w0.start)) continue;
            double s = Math.max(0, w0.start), e = Double.isNaN(w0.end) ? s : w0.end;
            if (dur > 0) { if (s >= dur - 0.05) continue; e = Math.min(e, dur); }
            if (e - s < 0.05) e = dur > 0 ? Math.min(s + 0.12, dur) : s + 0.12;
            Word w = new Word(clean, s, e);
            w.sentenceEnd = w0.sentenceEnd;
            out.add(w);
        }
        return out;
    }

    /** mono 16-bit PCM -> RMS loudness per 10 ms frame (log scale, so quiet speech still counts) */
    public static float[] envelope(short[] pcm, int rate) {
        int hop = Math.max(1, (int) Math.round(rate * HOP));
        int n = pcm.length / hop;
        float[] env = new float[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int k = i * hop; k < (i + 1) * hop; k++) { double v = pcm[k]; s += v * v; }
            env[i] = (float) Math.log10(1.0 + Math.sqrt(s / hop));
        }
        return env;
    }

    /**
     * Where does the short's loudness envelope fit inside the source window? {@code src} starts {@code lead} seconds
     * before the clip's planned start. Returns {offsetSec, score}: offsetSec > 0 means the short's audio really starts
     * that much later in the source than planned; score is the normalised correlation (0..1, &gt; 0.6 = clear match).
     */
    public static double[] audioOffset(float[] clip, float[] src, double lead) {
        int L = Math.min(clip.length, (int) Math.round(30 / HOP));   // the first 30 s are plenty
        if (L < 100 || src.length < L) return new double[]{0, 0};
        double[] c = zscore(Arrays.copyOf(clip, L));
        double best = -2; int bestLag = 0;
        double[] s = new double[src.length];
        for (int i = 0; i < src.length; i++) s[i] = src[i];
        for (int lag = 0; lag + L <= s.length; lag++) {
            double mean = 0, sq = 0, dot = 0;
            for (int k = 0; k < L; k++) { double v = s[lag + k]; mean += v; sq += v * v; }
            mean /= L;
            double sd = Math.sqrt(Math.max(1e-12, sq / L - mean * mean));
            for (int k = 0; k < L; k++) dot += c[k] * (s[lag + k] - mean);
            double r = dot / (L * sd);
            if (r > best) { best = r; bestLag = lag; }
        }
        return new double[]{bestLag * HOP - lead, best};
    }

    /**
     * v10: lag (seconds) of envelope b relative to a within +-maxLag (b later = positive) and its correlation. Used to
     * prove the single-pass render's audio starts where the transcribed audio started.
     */
    public static double[] lag(float[] a, float[] b, double maxLag) {
        int L = Math.min(Math.min(a.length, b.length), (int) Math.round(30 / HOP));
        int M = (int) Math.round(maxLag / HOP);
        if (L < 100) return new double[]{0, 0};
        double best = -2; int bl = 0;
        for (int g = -M; g <= M; g++) {
            int from = Math.max(0, -g), to = Math.min(L, L - g);
            if (to - from < 50) continue;
            double ma = 0, mb = 0;
            for (int i = from; i < to; i++) { ma += a[i]; mb += b[i + g]; }
            int n = to - from; ma /= n; mb /= n;
            double sab = 0, saa = 0, sbb = 0;
            for (int i = from; i < to; i++) { double x = a[i] - ma, y = b[i + g] - mb; sab += x * y; saa += x * x; sbb += y * y; }
            double r = sab / Math.sqrt(Math.max(1e-12, saa * sbb));
            if (r > best) { best = r; bl = g; }
        }
        return new double[]{bl * HOP, best};
    }

    static double[] zscore(float[] a) {
        double m = 0, q = 0;
        for (float v : a) { m += v; q += v * v; }
        m /= a.length;
        double sd = Math.sqrt(Math.max(1e-12, q / a.length - m * m));
        double[] o = new double[a.length];
        for (int i = 0; i < a.length; i++) o[i] = (a[i] - m) / sd;
        return o;
    }

    /**
     * Word times for a segment whose recognizer gave no word timestamps: the segment's words are laid out by length,
     * then every boundary between two words is moved to the quietest 10 ms frame within ±0.25 s (a real gap between
     * words). Never an even spread, never AI-estimated times.
     */
    public static List<Word> alignSegment(String[] toks, double segStart, double segEnd, float[] env) {
        List<Word> out = new ArrayList<>();
        int n = toks.length;
        if (n == 0 || segEnd <= segStart) return out;
        double total = 0;
        double[] wgt = new double[n];
        for (int i = 0; i < n; i++) { wgt[i] = Math.max(1, syllables(toks[i])); total += wgt[i]; }
        double[] b = new double[n + 1];
        b[0] = segStart; b[n] = segEnd;
        double acc = 0;
        for (int i = 1; i < n; i++) {
            acc += wgt[i - 1];
            double guess = segStart + (segEnd - segStart) * acc / total;
            b[i] = env == null ? guess : quietest(env, guess - 0.25, guess + 0.25, guess);
            if (b[i] <= b[i - 1] + 0.05) b[i] = Math.min(segEnd - 0.05 * (n - i), b[i - 1] + 0.05);
        }
        for (int i = 0; i < n; i++) out.add(new Word(toks[i], b[i], Math.max(b[i] + 0.05, b[i + 1])));
        return out;
    }

    static double quietest(float[] env, double from, double to, double dflt) {
        int a = Math.max(0, (int) Math.floor(from / HOP)), z = Math.min(env.length - 1, (int) Math.ceil(to / HOP));
        if (a >= z) return dflt;
        int bi = a; float bv = Float.MAX_VALUE;
        for (int i = a; i <= z; i++) if (env[i] < bv) { bv = env[i]; bi = i; }
        return bi * HOP;
    }

    /** rough syllable count (Latin vowels groups / Devanagari vowel signs + independent vowels + consonants) */
    static int syllables(String t) {
        int n = 0; boolean prevV = false;
        for (int i = 0; i < t.length(); i++) {
            char c = Character.toLowerCase(t.charAt(i));
            if (c >= 0x0900 && c <= 0x097F) {
                if ((c >= 0x0915 && c <= 0x0939) || (c >= 0x0904 && c <= 0x0914)) n++;
                continue;
            }
            boolean v = "aeiouy".indexOf(c) >= 0;
            if (v && !prevV) n++;
            prevV = v;
        }
        return Math.max(1, n);
    }

    /**
     * v9: Whisper often starts a word too early: a word after a pause gets the pause's start (the caption would light up
     * during silence, up to ~1 s early), and the first word of a segment gets the segment start. When a word's span
     * holds a real silence (&ge; 80 ms below the speech threshold) before its speech, or the word starts in silence, its
     * start moves to where the speech actually begins (30 ms pre-roll). Measured against an independent forced aligner
     * on an English clip: p95 664 -&gt; 166 ms, max 943 -&gt; 410 ms. Never moves a start earlier.
     */
    public static List<Word> snapToSpeech(List<Word> words, float[] env) {
        if (env == null || env.length < 50 || words == null) return words;
        float[] sorted = env.clone();
        Arrays.sort(sorted);
        double floor = sorted[(int) (sorted.length * 0.10)], speech = sorted[(int) (sorted.length * 0.90)];
        double thr = floor + 0.3 * (speech - floor);
        int N = env.length, minRun = 8;
        List<Word> out = new ArrayList<>();
        for (Word w : words) {
            double s = w.start, e = w.end;
            int a = (int) Math.round(s / HOP);
            int z = (int) Math.round(Math.min(e - 0.05, s + Math.max(0.2, Math.min(2.0, (e - s) * 0.85))) / HOP);
            int endF = (int) (e / HOP);
            int best = -1;
            int j = a;
            while (j < z && j < N && best < 0) {
                if (env[j] < thr) {
                    int k = j;
                    while (k < N && env[k] < thr) k++;
                    if (k - j >= minRun && k < endF) best = k;
                    j = k;
                } else j++;
            }
            if (best < 0 && a < N) {
                boolean silentStart = true;
                for (int k = a; k < Math.min(N, a + 3); k++) if (env[k] >= thr) silentStart = false;
                if (silentStart) {
                    int k = a;
                    while (k < N && env[k] < thr) k++;
                    if (k * HOP < e - 0.05) best = k;
                }
            }
            if (best >= 0) s = Math.max(s, best * HOP - 0.03);
            out.add(s == w.start ? w : w.retimed(s, Math.max(e, s + 0.05)));
        }
        return out;
    }


    /**
     * v10 word timing refinement (on top of {@link #snapToSpeech}). Measured on Sakib's clips against an independent
     * forced aligner, Whisper's worst errors were the first word after an unspoken stretch: it starts the word at the
     * beginning of whatever came before (a filler Whisper drops, a breath, a pause), so one word "lasts" 1-3 s and
     * lights up long before it is said. Whisper's word END times were reliable (median 26-48 ms off), so:
     *  1. a word much longer than its syllables allow is anchored to its end: its start moves to the onset of the last
     *     speech run inside it that is long enough to hold the word;
     *  2. a word still longer than 2x the speaker's own pace (seconds per syllable, measured on this clip) is clamped
     *     to 1.5x that pace (slow words stay slow: the pace is the speaker's, not a constant);
     *  3. trailing silence (&ge; 150 ms) inside a word is trimmed so the highlight ends when the sound ends.
     * hi2 clip: worst word 2.11 s -&gt; 0.54 s, p95 725 -&gt; 408 ms. Never moves a start earlier or an end later.
     */
    public static List<Word> refineWords(List<Word> words, float[] env) {
        if (env == null || env.length < 50 || words == null || words.isEmpty()) return words;
        List<Word> snapped = snapToSpeech(words, env);
        double thr = threshold(env);
        int N = env.length, gapF = 12;
        List<Word> stage = new ArrayList<>();
        for (int wi = 0; wi < snapped.size(); wi++) {
            Word w = snapped.get(wi), w0 = words.get(wi);
            double s = w.start, e = w.end;
            double exp = Math.min(1.2, Math.max(0.15, 0.10 * syllables(w.text) + 0.08));
            if (e - s > 1.5 * exp + 0.15) {
                int a = (int) Math.round(w0.start / HOP), z = Math.min(N, (int) Math.round(e / HOP));
                List<int[]> runs = speechRuns(env, thr, a, z, gapF);
                for (int r = runs.size() - 1; r >= 0; r--) {
                    int i = runs.get(r)[0];
                    if (e - i * HOP >= 0.5 * exp) { s = Math.max(s, i * HOP - 0.01); break; }
                }
            }
            stage.add(s == w.start ? w : w.retimed(s, Math.max(e, s + 0.05)));
        }
        // speaker pace: median seconds per syllable of this clip's words
        List<Double> sps = new ArrayList<>();
        for (Word w : stage) if (w.end - w.start > 0.08) sps.add((w.end - w.start) / syllables(w.text));
        double pace = 0.12;
        if (!sps.isEmpty()) { java.util.Collections.sort(sps); pace = Math.max(0.07, Math.min(0.3, sps.get(sps.size() / 2))); }
        List<Word> out = new ArrayList<>();
        for (Word w : stage) {
            double s = w.start, e = w.end;
            int a = (int) Math.round(s / HOP), z = Math.min(N, (int) Math.round(e / HOP)), k = z;
            while (k > a && env[k - 1] < thr) k--;
            if (z - k >= 15 && k > a) e = Math.max(s + 0.08, k * HOP + 0.05);   // trailing silence first
            double ex = Math.max(0.12, pace * syllables(w.text) + 0.05);
            if (e - s > 2.0 * ex) s = e - 1.5 * ex;
            out.add(s == w.start && e == w.end ? w : w.retimed(s, e));
        }
        return out;
    }

    static double threshold(float[] env) {
        float[] sorted = env.clone();
        Arrays.sort(sorted);
        double floor = sorted[(int) (sorted.length * 0.10)], speech = sorted[(int) (sorted.length * 0.90)];
        return floor + 0.3 * (speech - floor);
    }

    /** speech runs [from, to) frames inside [a, z), silences shorter than gapF frames merged */
    static List<int[]> speechRuns(float[] env, double thr, int a, int z, int gapF) {
        List<int[]> rr = new ArrayList<>();
        int i = Math.max(0, a);
        while (i < z) {
            if (env[i] >= thr) {
                int j = i;
                while (j < z) {
                    if (env[j] >= thr) { j++; continue; }
                    int k = j;
                    while (k < z && env[k] < thr) k++;
                    if (k - j < gapF && k < z) j = k; else break;
                }
                rr.add(new int[]{i, j});
                i = j;
            } else i++;
        }
        return rr;
    }

    /** loose comparison key for the same spoken word in two transcripts */
    public static String key(String s) {
        if (s == null) return "";
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s\u0964\u0965]+", "").replace("\u093C", "");
    }

    /**
     * Median start-time difference (test - ref) over the words both lists agree on (longest common subsequence of
     * word keys). Returns {medianSec, maxAbsSec, matched}. Used to check the full transcript against the short.
     */
    public static double[] wordOffset(List<Word> ref, List<Word> test) {
        int n = ref.size(), m = test.size();
        if (n == 0 || m == 0) return new double[]{0, 0, 0};
        int[][] dp = new int[n + 1][m + 1];
        String[] a = new String[n], b = new String[m];
        for (int i = 0; i < n; i++) a[i] = key(ref.get(i).text);
        for (int j = 0; j < m; j++) b[j] = key(test.get(j).text);
        for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
            dp[i][j] = !a[i].isEmpty() && a[i].equals(b[j]) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
        List<Double> d = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (!a[i].isEmpty() && a[i].equals(b[j])) { d.add(test.get(j).start - ref.get(i).start); i++; j++; }
            else if (dp[i + 1][j] >= dp[i][j + 1]) i++; else j++;
        }
        if (d.isEmpty()) return new double[]{0, 0, 0};
        List<Double> s = new ArrayList<>(d);
        java.util.Collections.sort(s);
        double med = s.get(s.size() / 2), mx = 0;
        for (double v : d) mx = Math.max(mx, Math.abs(v));
        return new double[]{med, mx, d.size()};
    }

    /** the full transcript's words for [start, end], moved onto the short's timeline (0 = first frame), plus a measured offset */
    public static List<Word> fromFullTranscript(Transcript tr, double start, double end, double offset) {
        List<Word> rel = tr.wordsForClip(start + offset, end + offset);
        return rel;
    }
}
