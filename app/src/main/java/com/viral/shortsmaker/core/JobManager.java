package com.viral.shortsmaker.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of truth for job progress, shared by ProcessingService (writer) and the UI (observer).
 * v3: real per-step progress. Each step reports its own fraction (download bytes, upload bytes, transcribed parts,
 * FFmpeg time= stats) or -1 when the step has no measurable progress (waiting on the AI). The overall bar and the
 * ETA are computed from those real fractions plus timings learned from the previous runs on this phone.
 */
public final class JobManager {
    public enum State { IDLE, RUNNING, DONE, FAILED, CANCELLED }
    public enum Kind { ANALYZE, CAPTION }

    public static final String[] STAGES = {"Get audio", "Transcribe speech", "Find viral moments", "Render shorts", "Captions"};
    public static final int N = 5;

    public interface Listener { void onJobChanged(JobManager jm); }

    private static final JobManager I = new JobManager();
    public static JobManager get() { return I; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public volatile State state = State.IDLE;
    public volatile Kind kind = Kind.ANALYZE;
    /** code 32: a CAPTION job that removes the captions (the step label; 4.9 read the last ANALYZE request) */
    public volatile boolean captionRemove;
    public volatile int stage = 0;
    /** overall 0..100 */
    public volatile int percent = 0;
    /** headline of the current step, e.g. "Downloading audio" */
    public volatile String status = "";
    /** live numbers of the current step, e.g. "3.1 of 5.4 MB • 1.2 MB/s" */
    public volatile String detail = "";
    public volatile String error = "";
    /** non-fatal problems of the last job (shown on the results screen) */
    public volatile String warning;
    public volatile Project project;
    /** title of the video being processed */
    public volatile String title = "";

    /** per step: fraction 0..1, -1 = running without measurable progress */
    public final double[] frac = new double[N];
    /** per step: start / end time (ms), 0 = not reached */
    public final long[] t0 = new long[N], t1 = new long[N];
    /** per step: estimated duration in seconds (from the plan) */
    public final double[] est = new double[N];
    /** steps not part of this job */
    public final boolean[] skip = new boolean[N];
    public volatile long startedAt;
    /** seconds, -1 unknown */
    public volatile int etaSec = -1;

    private boolean loaded;
    private long lastPost;

    private JobManager() {}

    public synchronized void ensureLoaded(Context c) {
        if (loaded) return;
        loaded = true;
        if (project == null) {
            Project p = Project.load(c);
            // v7: on a fresh launch the last project stays reachable (History, Caption Studio) but the Create screen
            // opens on an empty new-project form: results show only right after a run (or when opened from History)
            if (p != null && !p.clips.isEmpty()) { project = p; state = State.IDLE; }
        }
    }

    public boolean isRunning() { return state == State.RUNNING; }

    public synchronized void start(Kind k) {
        kind = k; state = State.RUNNING; stage = k == Kind.CAPTION ? 4 : 0; percent = 0; error = ""; warning = null; status = "Starting…"; detail = "";
        startedAt = System.currentTimeMillis();
        for (int i = 0; i < N; i++) { frac[i] = 0; t0[i] = 0; t1[i] = 0; est[i] = 0; skip[i] = false; }
        if (k == Kind.CAPTION) { for (int i = 0; i < 4; i++) skip[i] = true; }
        else skip[4] = true;
        t0[stage] = startedAt;
        etaSec = -1; etaD = -1; mapFrom = 0; mapTo = 0; remStep = -1;
        notifyNow();
    }

    /** estimated seconds per step for this job (learned from earlier runs) */
    public synchronized void plan(double[] seconds) {
        for (int i = 0; i < N && i < seconds.length; i++) est[i] = Math.max(0, seconds[i]);
        mapFrom = 0; mapTo = 0;
    }

    /**
     * v22 (4.1): a better estimate of one step arrived mid-run (the render step, once the clips and their lengths are
     * known). The bar keeps showing what it showed and the rest of the way is spread over the new weights - no jump,
     * no stall at the old number.
     */
    public synchronized void replan(int step, double seconds) {
        if (step < 0 || step >= N || seconds <= 0) return;
        long now = System.currentTimeMillis();
        double shownNow = percent / 100.0;
        est[step] = seconds;
        double raw = rawFraction(now);
        mapFrom = Math.min(0.999, raw);
        mapTo = Math.min(0.99, Math.max(shownNow, 0));
        recompute(now);
    }

    /** v22: the step itself knows how long it still needs (render: a simulation of the remaining pipeline) */
    public synchronized void stageRemaining(int step, double seconds) { stageRemainingAt(step, seconds, System.currentTimeMillis()); }
    public synchronized void stageRemainingAt(int step, double seconds, long now) { remStep = step; remSec = seconds; remAt = now; }
    /** v22 tests: start time of the current job (fake clock) */
    public synchronized void startedAtForTest(long t) { startedAt = t; for (int i = 0; i < N; i++) if (t0[i] != 0) t0[i] = t; }

    private double mapFrom, mapTo;
    private int remStep = -1; private double remSec; private long remAt;
    private double etaD = -1; private long etaAt;

    /** report progress of a step. frac -1 = indeterminate. status/detail null = unchanged */
    public synchronized void step(int s, double f, String status, String detail) { stepAt(s, f, status, detail, System.currentTimeMillis()); }

    /** v22: step() at a given clock (tests replay a run in fake time) */
    public synchronized void stepAt(int s, double f, String status, String detail, long now) {
        if (s != stage) {
            for (int i = 0; i < s; i++) { if (t1[i] == 0 && t0[i] != 0) t1[i] = now; if (!skip[i]) frac[i] = 1; }
            stage = s;
            if (t0[s] == 0) t0[s] = now;
            this.detail = "";
        }
        // v22: once a step reports a real fraction it never falls back to "no measurable progress" (4.1: the render step's
        // sub-tasks used to flip it between the two, and the ring / time left flipped with it)
        frac[s] = f < 0 ? (frac[s] > 0 ? frac[s] : -1) : Math.max(frac[s] < 0 ? 0 : frac[s], Math.min(1, f));
        if (status != null) this.status = status;
        if (detail != null) this.detail = detail;
        recompute(now);
        if (now - lastPost > 150) { lastPost = now; notifyNow(); }
    }

    /** v2-compatible call */
    public void progress(int stage, int percent, String status) { step(stage, -1, status, null); }

    private double weight(int i) {
        if (skip[i]) return 0;
        double total = 0;
        for (int k = 0; k < N; k++) if (!skip[k]) total += Math.max(1, est[k]);
        return Math.max(1, est[i]) / Math.max(1, total);
    }

    private double fracOf(int i, long now) {
        if (skip[i]) return 0;
        if (i < stage) return 1;
        if (i > stage) return 0;
        if (frac[i] >= 0) return frac[i];
        // indeterminate step (AI call): use elapsed vs learned estimate, never past 90 %
        double el = t0[i] == 0 ? 0 : (now - t0[i]) / 1000.0;
        return est[i] > 0 ? Math.min(0.9, el / est[i]) : 0;
    }

    double rawFraction(long now) {
        double p = 0;
        for (int i = 0; i < N; i++) p += weight(i) * fracOf(i, now);
        return p;
    }

    /** v22: seconds left in the current step: the step's own number when it gave one, else live rate blended with the plan */
    double stepRemaining(long now) {
        double el = t0[stage] == 0 ? 0 : (now - t0[stage]) / 1000.0;
        if (remStep == stage && now - remAt < 4000) return Math.max(0, remSec - (now - remAt) / 1000.0);
        double f = frac[stage];
        double plan = f < 0 ? Math.max(est[stage] * 0.1, est[stage] - el) : Math.max(est[stage] * (1 - f), est[stage] - el);
        if (f <= 0.02 || el < 2) return Math.max(0, plan);
        double live = el * (1 - f) / f;
        double c = Math.min(1, f / 0.3);   // trust the live rate more as the step goes on: no switch between two formulas
        return Math.max(0, c * live + (1 - c) * plan);
    }

    private void recompute(long now) {
        double p = rawFraction(now);
        double mapped = mapTo + (1 - mapTo) * (p - mapFrom) / Math.max(1e-6, 1 - mapFrom);
        percent = Math.max(percent, (int) Math.min(99, Math.floor(Math.max(0, mapped) * 100)));
        boolean known = est[stage] > 0 || frac[stage] > 0.02 || remStep == stage;
        if (!known) { etaSec = -1; etaD = -1; return; }
        double rem = stepRemaining(now);
        for (int i = stage + 1; i < N; i++) if (!skip[i]) rem += est[i];
        // v22 (4.1): his 4.0 screen jumped 1 -> 23 -> 4 min within seconds (every short's update recomputed and
        // half-blended it). Now the shown number counts down with the clock and moves toward the new estimate
        // gently (10 s time constant), updated at most twice a second.
        if (etaD < 0) { etaD = rem; etaAt = now; }
        else {
            double dt = (now - etaAt) / 1000.0;
            if (dt >= 0.5) {
                double down = Math.max(0, etaD - dt);
                double a = 1 - Math.exp(-dt / 10.0);
                etaD = down + (rem - down) * a;
                etaAt = now;
            }
        }
        etaSec = (int) Math.max(1, Math.round(etaD));
    }

    /** seconds the step took (finished) or has been running, -1 if not reached */
    public double stepSeconds(int i) {
        if (t0[i] == 0) return -1;
        long end = t1[i] != 0 ? t1[i] : System.currentTimeMillis();
        return (end - t0[i]) / 1000.0;
    }

    public synchronized void tick() {
        if (state == State.RUNNING) { recompute(System.currentTimeMillis()); }
    }

    public synchronized void finish(State s, String err) {
        long now = System.currentTimeMillis();
        if (t0[stage] != 0 && t1[stage] == 0) t1[stage] = now;
        state = s; error = err == null ? "" : err;
        if (s == State.DONE) { percent = 100; for (int i = 0; i < N; i++) if (!skip[i]) frac[i] = 1; }
        notifyNow();
    }

    public void reset() { state = State.IDLE; percent = 0; stage = 0; status = ""; detail = ""; error = ""; notifyNow(); }

    public void notifyNow() { main.post(() -> { for (Listener l : listeners) l.onJobChanged(this); }); }

    public void addListener(Listener l) { listeners.add(l); l.onJobChanged(this); }
    public void removeListener(Listener l) { listeners.remove(l); }
    /** tests: activities of earlier tests never stop, drop their listeners */
    public void removeAllListeners() { listeners.clear(); }
}
