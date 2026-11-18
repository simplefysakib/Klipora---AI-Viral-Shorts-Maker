package com.viral.shortsmaker.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v22 (4.1): one honest progress for the "render shorts" step. Sakib's 4.0 run: two shorts at once each wrote the step's
 * status line and fraction, face tracking reported "no measurable progress" (the bar then ran on the last run's time and
 * showed 93% with most of the work left), and the time left was computed two different ways in turn (1 -> 23 -> 4 min
 * within seconds).
 *
 * Now every short has three phases with weights in predicted seconds of work - getting its clip + captions (PREP), face
 * analysis (FACES) and the encode (ENCODE) - learned from this phone's earlier runs. The step's fraction is the share of
 * that work done (never goes back), the status line is written from all shorts at once (one writer, no flicker), and the
 * time left is a small simulation of the remaining pipeline (face analyses one after another, encodes beside them) with
 * the rates corrected by what this run has actually measured so far.
 */
public final class RenderProgress {
    public static final int WAIT = 0, PREP = 1, FACES = 2, ENCODE = 3, DONE = 4;

    /** seconds of wall time per short / per clip second, as learned on this phone (defaults = his OPPO after 4.1 changes) */
    public static final class Rates {
        public double prep = 8;        // seconds per short to fetch its clip (+ its caption transcript, alongside)
        public double faces = 0.8;     // face-analysis seconds per clip second (0 when no tracking)
        public double encode = 1.2;    // encode seconds per clip second (while another short is analysed beside it)
        public Rates() {}
        public Rates(double prep, double faces, double encode) { this.prep = prep; this.faces = faces; this.encode = encode; }
    }

    private final int n;
    private final int[] clipIndex;
    private final double[] sec;
    private final boolean tracking;
    private final Rates r;
    private final int par;
    private final int[] phase;
    private final double[] f;         // fraction of the current phase
    private final long[][] t;         // per short: start time (ms) of each phase, 0 = not reached
    private final long[] prepEnd;     // when PREP reached 100% (the wait for the face engines after it is not prep work)
    private double shown;             // last fraction handed out (monotonic)
    private Runnable onChange;
    private final long t0;

    public RenderProgress(int[] clipIndex, double[] clipSec, boolean tracking, Rates rates, int par, long now) {
        this.n = clipSec.length;
        this.clipIndex = clipIndex;
        this.sec = clipSec;
        this.tracking = tracking;
        this.r = rates == null ? new Rates() : rates;
        this.par = Math.max(1, par);
        phase = new int[n]; f = new double[n]; t = new long[n][5]; prepEnd = new long[n];
        t0 = now;
    }

    public void setOnChange(Runnable r) { onChange = r; }
    public int size() { return n; }

    /** position of a project clip in this batch, -1 when not in it */
    public int slot(int clipIdx) { for (int k = 0; k < n; k++) if (clipIndex[k] == clipIdx) return k; return -1; }

    double w(int k, int ph) {
        switch (ph) {
            case PREP: return r.prep;
            case FACES: return tracking ? r.faces * sec[k] : 0;
            case ENCODE: return r.encode * sec[k];
            default: return 0;
        }
    }

    double total(int k) { return w(k, PREP) + w(k, FACES) + w(k, ENCODE); }

    /** phase ph of short k is at fraction x (0..1). Earlier phases count as done; an older phase reported late is ignored. */
    public void set(int k, int ph, double x, long now) {
        synchronized (this) {
            if (k < 0 || k >= n || ph < phase[k] || phase[k] == DONE) return;
            if (ph > phase[k]) {
                for (int p = phase[k] + 1; p <= ph; p++) if (t[k][p] == 0) t[k][p] = now;
                if (t[k][PREP] == 0) t[k][PREP] = now;
                phase[k] = ph; f[k] = 0;
            }
            if (ph != DONE) f[k] = Math.max(f[k], Math.min(1, Math.max(0, x)));
            if (ph == PREP && f[k] >= 1 && prepEnd[k] == 0) prepEnd[k] = now;
        }
        Runnable cb = onChange;
        if (cb != null) cb.run();
    }

    public void set(int k, int ph, double x) { set(k, ph, x, System.currentTimeMillis()); }
    public void done(int k) { set(k, DONE, 1); }

    /** work done of short k in seconds */
    double doneWork(int k) {
        int ph = phase[k];
        if (ph == DONE) return total(k);
        double d = 0;
        for (int p = PREP; p < ph; p++) d += w(k, p);
        if (ph >= PREP) d += w(k, ph) * f[k];
        return d;
    }

    /** the step's fraction 0..1: share of the predicted work done; never goes backwards */
    public synchronized double fraction() {
        double all = 0, done = 0;
        for (int k = 0; k < n; k++) { all += total(k); done += doneWork(k); }
        double x = all <= 0 ? 0 : done / all;
        boolean allDone = true;
        for (int k = 0; k < n; k++) if (phase[k] != DONE) allDone = false;
        if (!allDone) x = Math.min(x, 0.995);
        shown = Math.max(shown, x);
        return shown;
    }

    private double shownT;

    /**
     * the step's fraction by TIME: elapsed / (elapsed + simulated time left), never backwards. Work shares overlap (an
     * encode runs while the next short is analysed), so the share of work done runs ahead of the clock; this one is
     * what the bar and the time left agree on. Box replay of his 4.0 run at the moment of his screenshot (93% shown):
     * 72% for this step (the true share of time was 67%).
     */
    public synchronized double timeFraction(long now) {
        boolean allDone = true;
        for (int k = 0; k < n; k++) if (phase[k] != DONE) allDone = false;
        if (allDone) { shownT = 1; return 1; }
        double el = Math.max(0, (now - t0) / 1000.0);
        double rem = remainingSeconds(now);
        double x = el + rem <= 0 ? 0 : el / (el + rem);
        shownT = Math.max(shownT, Math.min(0.995, x));
        return shownT;
    }

    public synchronized int doneCount() { int c = 0; for (int k = 0; k < n; k++) if (phase[k] == DONE) c++; return c; }
    public synchronized int phaseOf(int k) { return phase[k]; }

    /** one status line for every short at once: "1 of 3 done · #2 faces 40% · #3 rendering 12%" */
    public synchronized String detail() {
        StringBuilder sb = new StringBuilder();
        int dn = 0;
        for (int k = 0; k < n; k++) if (phase[k] == DONE) dn++;
        sb.append(n == 1 ? (dn == 1 ? "Done" : "Short 1") : dn + " of " + n + " done");
        for (int k = 0; k < n; k++) {
            int ph = phase[k];
            if (ph == WAIT || ph == DONE) continue;
            String what = ph == PREP ? (f[k] >= 1 ? "waiting" : "getting clip") : ph == FACES ? "tracking faces" : "rendering";
            sb.append(" · #").append(clipIndex[k] + 1).append(' ').append(what);
            if (ph != PREP) sb.append(' ').append(Math.round(f[k] * 100)).append('%');
        }
        return sb.toString();
    }

    /** when phase ph of short k ended (0 = not yet) */
    long endOf(int k, int ph) {
        if (ph == PREP && prepEnd[k] != 0) return prepEnd[k];
        for (int p = ph + 1; p <= DONE; p++) if (t[k][p] != 0) return t[k][p];
        return 0;
    }

    /** this run's measured speed vs the learned rate for a phase (1 = as predicted), from finished and running phases */
    synchronized double correction(int ph, long now) {
        double pred = 0, real = 0;
        for (int k = 0; k < n; k++) {
            if (t[k][ph] == 0) continue;
            double wk = w(k, ph);
            if (wk <= 0) continue;
            long end = endOf(k, ph);
            if (end != 0) {
                pred += wk; real += (end - t[k][ph]) / 1000.0;
            } else if (phase[k] == ph && f[k] >= 0.15) {
                pred += wk * f[k]; real += (now - t[k][ph]) / 1000.0;
            }
        }
        if (pred < 3) return 1;
        return Math.max(0.4, Math.min(3.0, real / pred));
    }

    /**
     * seconds left for the whole step: the remaining work of every short played through the same pipeline the renderer
     * runs (par shorts at a time; face analyses one at a time - one engine set; prep and encode side by side), with each
     * phase's rate scaled by this run's own measurement so far.
     */
    public synchronized double remainingSeconds(long now) {
        double cp = correction(PREP, now), cf = correction(FACES, now), ce = correction(ENCODE, now);
        double[][] rem = new double[n][4];
        int[] ph = new int[n];
        for (int k = 0; k < n; k++) {
            ph[k] = phase[k];
            for (int p = PREP; p <= ENCODE; p++) {
                double c = p == PREP ? cp : p == FACES ? cf : ce;
                double left = p < phase[k] || phase[k] == DONE ? 0 : p == phase[k] ? w(k, p) * (1 - f[k]) : w(k, p);
                rem[k][p] = left * c;
            }
        }
        return simulate(rem, ph, par);
    }

    /** discrete simulation (0.25 s steps) of the remaining work; rem[k][PREP..ENCODE] seconds, ph = current phase */
    static double simulate(double[][] rem, int[] ph, int par) {
        int n = rem.length;
        List<Integer> active = new ArrayList<>();
        int next = 0;
        boolean[] fin = new boolean[n];
        for (int k = 0; k < n; k++) {
            boolean empty = ph[k] == DONE || rem[k][PREP] + rem[k][FACES] + rem[k][ENCODE] <= 1e-9;
            if (empty) fin[k] = true;
        }
        // shorts already running hold their slots first
        for (int k = 0; k < n; k++) if (!fin[k] && ph[k] != WAIT && active.size() < par) active.add(k);
        double time = 0, dt = 0.25;
        int lockHolder = -1;
        for (int guard = 0; guard < 400000; guard++) {
            while (active.size() < par) {
                while (next < n && (fin[next] || active.contains(next))) next++;
                if (next >= n) break;
                active.add(next++);
            }
            if (active.isEmpty()) break;
            for (int k : new ArrayList<>(active)) {
                double[] r = rem[k];
                if (r[PREP] > 1e-9) { r[PREP] -= dt; continue; }
                if (r[FACES] > 1e-9) {
                    if (lockHolder == -1 || lockHolder == k) { lockHolder = k; r[FACES] -= dt; if (r[FACES] <= 1e-9) lockHolder = -1; }
                    continue;
                }
                if (r[ENCODE] > 1e-9) { r[ENCODE] -= dt; if (r[ENCODE] > 1e-9) continue; }
                fin[k] = true; active.remove((Integer) k);
            }
            time += dt;
        }
        return time;
    }

    /** predicted wall seconds of the whole step before it starts */
    public static double predictWall(double[] clipSec, boolean tracking, Rates r, int par) {
        int n = clipSec.length;
        double[][] rem = new double[n][4];
        int[] ph = new int[n];
        for (int k = 0; k < n; k++) {
            rem[k][PREP] = r.prep; rem[k][FACES] = tracking ? r.faces * clipSec[k] : 0; rem[k][ENCODE] = r.encode * clipSec[k];
        }
        return simulate(rem, ph, Math.max(1, par));
    }

    /** measured wall seconds of one phase across finished shorts / clip seconds (for learning); NaN when none */
    public synchronized double measuredRate(int ph, boolean perClipSecond) {
        double real = 0, base = 0;
        for (int k = 0; k < n; k++) {
            if (t[k][ph] == 0) continue;
            long end = endOf(k, ph);
            if (end == 0) continue;
            real += (end - t[k][ph]) / 1000.0;
            base += perClipSecond ? sec[k] : 1;
        }
        return base <= 0 ? Double.NaN : real / base;
    }

    public String debug() {
        return String.format(Locale.US, "%.3f %s", fraction(), detail());
    }
}
