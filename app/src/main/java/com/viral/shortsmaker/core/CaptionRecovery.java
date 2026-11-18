package com.viral.shortsmaker.core;

import com.viral.shortsmaker.captions.Word;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 5.4: a short's own speech is transcribed by Whisper in one request. When Whisper loops or goes quiet on a stretch
 * (music under a voice, a dubbed cartoon, overlapping speakers) that stretch comes back as one repeated phrase, which
 * {@link Transcript#parseVerboseJson} drops, or with no words at all, so the captions of that stretch were garbled and
 * then simply missing while the person kept talking (his clip: 12-33 s). The full-video transcript already re-checked
 * such stretches; the per-short transcript never did. This class finds those stretches and has them transcribed again
 * in short windows (Whisper does not loop on 6 s of audio), then puts the words into the gap, timed on the short's own
 * timeline. Pure logic (unit-tested on the JVM); the audio cut and the request are the {@link Window} the caller supplies.
 */
public final class CaptionRecovery {
    private CaptionRecovery() {}

    /** a pause between two words (or before the first / after the last word) longer than this is suspect */
    public static final double GAP_SEC = 2.5, EDGE_GAP_SEC = 4.0;
    /** each re-check covers this much of the gap, plus PAD seconds of context each side (words there are not used) */
    public static final double WIN_SEC = 6.0, PAD = 0.5;
    /** at most this many extra requests per short */
    public static final int MAX_WINDOWS = 10;

    /** transcribes the short's audio from..to (seconds on the short's timeline); the returned times are relative to {@code from}; null = no speech */
    public interface Window { Transcript transcribe(double from, double to) throws Exception; }

    /** a word counts as speaking for at most this long when measuring pauses (Whisper stretches words over silence) */
    static double speakEnd(Word w) { return Math.min(w.end, w.start + 1.2); }

    /** time ranges {from, to} of a short whose speech is missing or unusable, merged, inside [0, dur] */
    public static List<double[]> suspectSpans(Transcript t, double dur) {
        List<double[]> raw = new ArrayList<>();
        if (t == null || dur <= 0 || t.words.isEmpty()) return raw;     // no words at all: the segment aligner handles it
        for (double[] l : t.loops) add(raw, l[0] - 0.2, l[1] + 0.2, dur, 1.0);
        List<Word> ws = new ArrayList<>(t.words);
        ws.sort((a, b) -> Double.compare(a.start, b.start));
        double prev = 0;
        boolean first = true;
        for (Word w : ws) {
            if (w.start >= dur - 0.05) break;
            double lim = first ? EDGE_GAP_SEC : GAP_SEC;
            if (w.start - prev >= lim) add(raw, prev, w.start, dur, 1.0);
            prev = Math.max(prev, speakEnd(w));
            first = false;
        }
        if (dur - prev >= EDGE_GAP_SEC) add(raw, prev, dur, dur, 1.0);
        // a spoken line whose words were mostly not timed
        for (Transcript.Segment s : t.segments) {
            if (s.text == null) continue;
            String tx = s.text.trim();
            int tokens = tx.isEmpty() ? 0 : tx.split("\\s+").length;
            if (tokens < 3 || s.end <= s.start) continue;
            int have = 0;
            for (Word w : ws) if (w.start >= s.start - 0.3 && w.start <= s.end + 0.3) have++;
            if (have < tokens * 0.4) add(raw, s.start, s.end, dur, 1.0);
        }
        raw.sort((a, b) -> Double.compare(a[0], b[0]));
        List<double[]> out = new ArrayList<>();
        for (double[] r : raw) {
            if (!out.isEmpty() && r[0] - out.get(out.size() - 1)[1] < 0.5) out.get(out.size() - 1)[1] = Math.max(out.get(out.size() - 1)[1], r[1]);
            else out.add(new double[]{r[0], r[1]});
        }
        return out;
    }

    private static void add(List<double[]> l, double a, double b, double dur, double minLen) {
        a = Math.max(0, a); b = Math.min(dur, b);
        if (b - a >= minLen) l.add(new double[]{a, b});
    }

    /** the equal pieces ("cores", each at most WIN_SEC) a span is re-checked in: {from, to} */
    public static List<double[]> cores(double from, double to) {
        List<double[]> out = new ArrayList<>();
        if (to - from < 0.2) return out;
        int n = (int) Math.max(1, Math.ceil((to - from) / WIN_SEC - 1e-9));
        double len = (to - from) / n;
        for (int i = 0; i < n; i++) out.add(new double[]{from + i * len, from + (i + 1) * len});
        return out;
    }

    private static final String[] PHRASES = {"thank you", "thanks for watching", "subscribe", "like and subscribe", "subtitles by", "amara.org",
            "\u0927\u0928\u094d\u092f\u0935\u093e\u0926", "\u0925\u0948\u0902\u0915 \u092f\u0942", "\u0935\u0940\u0921\u093f\u092f\u094b \u0915\u094b \u0932\u093e\u0907\u0915"};

    /** is a re-check answer real speech? (not silence turned into a stock phrase, not another loop) */
    static boolean plausible(Transcript sub) {
        if (sub == null || sub.words.size() < 2 || sub.words.size() < sub.segments.size()) return false;
        Set<String> distinct = new HashSet<>();
        StringBuilder all = new StringBuilder();
        for (Word w : sub.words) { distinct.add(w.text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]+", "")); all.append(w.text).append(' '); }
        if (sub.words.size() >= 6 && distinct.size() < sub.words.size() * 0.35) return false;
        String s = all.toString().toLowerCase(Locale.ROOT).trim();
        if (sub.words.size() <= 5) for (String p : PHRASES) if (s.contains(p)) return false;
        return true;
    }

    /**
     * Puts re-checked words into every suspect stretch of {@code t}. Returns the number of requests made (0 = nothing
     * suspect). Any failure of one request (limit, network) stops the recovery and keeps what is already in {@code t};
     * only a cancel is rethrown.
     */
    public static int recover(Transcript t, double dur, Window win, Consumer<String> log) throws Exception {
        List<double[]> spans = suspectSpans(t, dur);
        if (spans.isEmpty()) return 0;
        double lost = 0;
        for (double[] sp : spans) lost += sp[1] - sp[0];
        if (log != null) log.accept(String.format(Locale.US, "%d stretch(es), %.0f s of this short's speech came back missing or repeated, checking again in short pieces", spans.size(), lost));
        int used = 0, gained = 0;
        outer:
        for (double[] sp : spans) {
            for (double[] c : cores(sp[0], sp[1])) {
                if (used >= MAX_WINDOWS) break outer;
                double wa = Math.max(0, c[0] - PAD), wb = Math.min(dur, c[1] + PAD);
                used++;
                Transcript sub;
                try {
                    sub = win.transcribe(wa, wb);
                } catch (CancelToken.Cancelled x) {
                    throw x;
                } catch (Exception e) {
                    if (log != null) log.accept("re-check stopped (" + AiClient.describe(e) + "), kept what was heard");
                    break outer;
                }
                if (!plausible(sub)) continue;
                int before = t.words.size();
                t.spliceSpan(sub, wa, c[0], c[1]);
                gained += Math.max(0, t.words.size() - before);
            }
        }
        if (log != null) log.accept(String.format(Locale.US, "re-check: %d request(s), %d word(s) recovered", used, gained));
        return used;
    }
}
