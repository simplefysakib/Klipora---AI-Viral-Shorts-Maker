package com.viral.shortsmaker.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * v13 (2.4) AI face tracking: turns per-frame face detections (sampled a few times a second from the clip) into a
 * smooth 9:16 camera path, OpusClip style. Pure Java (no Android), unit tested; the detections come from
 * FaceAnalyzer (MediaPipe face landmarker, on the phone, offline).
 *
 * How the camera is chosen:
 *  1. Shot cuts in the source (thumbnail difference) split the clip into shots; faces are linked into tracks per shot.
 *  2. If every face of a shot fits in the 9:16 window, the window frames all of them (no switching needed).
 *  3. Otherwise the active speaker is the face whose lips move most (inner-lip gap over time), while there is voice in
 *     the audio. A new speaker must lead for 0.8 s before the camera switches (hysteresis, no flicker); the switch is
 *     then placed where that person started talking, as a clean cut (or a fast pan when they are already close).
 *     No usable lip signal: the largest, most central face.
 *  4. Camera motion: dead zone (small head moves do nothing), then a critically damped spring with a speed limit;
 *     clamped to the frame edges. Face lost: hold for 1 s, then ease back to the centre.
 *  5. Two people who cannot both fit and talk back and forth (or nobody is clearly the speaker): split screen,
 *     left person on top, right person at the bottom, each framed on the face (static per split, no jitter).
 */
public final class FaceTrack {
    private FaceTrack() {}

    public static final class Face {
        public final float x0, y0, x1, y1, mouth;   // box as a fraction of the frame; mouth = inner-lip gap / face height
        public Face(float x0, float y0, float x1, float y1, float mouth) { this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; this.mouth = mouth; }
        public double cx() { return (x0 + x1) / 2.0; }
        public double cy() { return (y0 + y1) / 2.0; }
        public double w() { return x1 - x0; }
        public double h() { return y1 - y0; }
    }

    public static final class Sample {
        public final List<Face> faces;
        public final float audio;   // RMS of the audio around this sample (any unit)
        public final float cut;     // mean abs luma difference to the previous sample's thumbnail (0..255)
        public Sample(List<Face> faces, float audio, float cut) { this.faces = faces == null ? new ArrayList<>() : faces; this.audio = audio; this.cut = cut; }
    }

    public static final class Split {
        public double start, end;            // seconds
        public double topCx, topCy, botCx, botCy;   // panel centres, fractions of the frame
        /** v22 (4.1): this split's own panel size (fractions of source width / height), chosen so each half shows only its person */
        public double pw, ph;
        double fh;                                   // face height (fraction of source height) the panel was sized for
    }

    public static final class Plan {
        public boolean usable;
        public String why = "";
        public double cropW;       // output-shaped window width as a fraction of the source width
        public double aspect = 9.0 / 16.0;   // v14: output width / height
        public double outFps;
        public double[] cx;        // window centre per output frame (fraction of width)
        public List<Split> splits = new ArrayList<>();
        public double panelH, panelW;   // split panel size as fractions of source height / width
        public int shots, switches, hardCuts;
        public int moves;          // v16: planned smooth camera moves
        public int skippedSplits;  // v22: split parts left out because the two people could not be separated cleanly
        public double faceCoverage;
        public boolean[] cutAt;    // output frames where the camera makes a clean cut

        /** left edge as a fraction of the free width (iw-ow), what the crop filter's x gets */
        public double lx(int k) { return lxOf(cx[Math.max(0, Math.min(cx.length - 1, k))], cropW); }

        public String summary() {
            return String.format(Locale.US, "faces in %.0f%% of frames, %d shot(s), %d speaker switch(es), %d split-screen part(s)",
                    faceCoverage * 100, shots, switches, splits.size())
                    + (skippedSplits > 0 ? String.format(Locale.US, " (%d left out: the two sat too close for clean halves)", skippedSplits) : "");
        }

        /**
         * v22 (4.1): the timeline in render segments at outFps: {kind 0 = camera, 1 = split part, first frame, end frame
         * (exclusive), split index or camera segment number}. 4.0 rendered the two split panels on EVERY frame (overlay
         * enable only hid them) - two extra lanczos scales + sharpening per frame; now each frame goes through exactly
         * one path. Frame k of a split part is the frame the 4.0 enable expression showed it on: [start, end) rounded.
         */
        public List<int[]> segments() {
            List<int[]> out = new ArrayList<>();
            int n = cx == null ? 0 : cx.length;
            int cur = 0, cam = 0;
            for (int i = 0; i < splits.size(); i++) {
                Split s = splits.get(i);
                int a = Math.max(cur, (int) Math.round(s.start * outFps)), b = Math.min(n, (int) Math.round(s.end * outFps));
                if (b <= a) continue;
                if (a > cur) out.add(new int[]{0, cur, a, cam++});
                out.add(new int[]{1, a, b, i});
                cur = b;
            }
            if (cur < n || out.isEmpty()) out.add(new int[]{0, cur, Math.max(n, cur + 1), cam});
            return out;
        }

        /** v22: true when the render needs the segmented graph (at least one split part survives on the frame grid) */
        public boolean hasSplitSegments() {
            for (int[] g : segments()) if (g[0] == 1) return true;
            return false;
        }

        /** name of the camera crop filter for camera segment number c (one segment = the 4.0 name crop@m) */
        public String camName(int c) { return hasSplitSegments() ? "crop@m" + c : "crop@m"; }

        /**
         * ffmpeg sendcmd script: the camera crop of each camera segment follows the camera (split panels are static
         * per part and baked into the graph since v22). Each camera segment gets its starting position at its first frame.
         */
        public String sendcmd() {
            StringBuilder sb = new StringBuilder();
            boolean seg = hasSplitSegments();
            for (int[] g : segments()) {
                if (g[0] != 0) continue;
                String name = seg ? "crop@m" + g[3] : "crop@m";
                double last = -9;
                for (int k = g[1]; k < Math.min(g[2], cx.length); k++) {
                    double l = lx(k);
                    if (k == g[1] || Math.abs(l - last) > 0.0005) {
                        // a hair before the frame's time: "%.4f" of k/30 can round up past the frame's own timestamp
                        sb.append(String.format(Locale.US, "%.4f %s x (iw-ow)*%.5f;\n", Math.max(0, (k - 0.25) / outFps), name, l));
                        last = l;
                    }
                }
            }
            return sb.toString();
        }

        /** overlay enable expression for the split parts, null when there are none */
        public String splitEnable() {
            if (splits.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (Split s : splits) {
                if (sb.length() > 0) sb.append('+');
                sb.append(String.format(Locale.US, "between(t,%.4f,%.4f)", s.start - 0.5 / outFps, s.end - 0.5 / outFps));
            }
            return sb.toString();
        }

        /**
         * v16 log line: share of frames where the camera is perfectly still, the number of planned smooth moves, and
         * shakes (the camera reversing direction within 0.4 s: 0 by design; 2.6 logged the std of every movement)
         */
        public String steadiness() {
            if (cx == null || cx.length < 3) return "camera still 100%";
            int still = 0, shakes = 0, lastDir = 0, lastDirAt = -999;
            for (int k = 1; k < cx.length; k++) {
                double d = cx[k] - cx[k - 1];
                if (cutAt != null && cutAt[k]) { lastDir = 0; continue; }
                if (Math.abs(d) < 1e-6) { still++; continue; }
                int dir = d > 0 ? 1 : -1;
                if (lastDir != 0 && dir != lastDir && k - lastDirAt < 0.4 * outFps) shakes++;
                lastDir = dir; lastDirAt = k;
            }
            return String.format(Locale.US, "camera still %.0f%% of the time, %d smooth move(s), %d shake(s), %d instant cut(s)",
                    100.0 * still / (cx.length - 1), moves, shakes, hardCuts);
        }

        /** frame-to-frame movement of the window, in output pixels (std dev and max) for a given output width */
        public double[] jitter(int outW) {
            if (cx == null || cx.length < 3) return new double[]{0, 0};
            double scale = outW / cropW;   // source fraction -> output pixels
            double sum = 0, sum2 = 0, mx = 0; int n = 0;
            for (int k = 1; k < cx.length; k++) {
                double d = Math.abs(cx[k] - cx[k - 1]) * scale;
                if (cutAt != null && cutAt[k]) continue;   // a deliberate cut, not jitter
                sum += d; sum2 += d * d; mx = Math.max(mx, d); n++;
            }
            double mean = n == 0 ? 0 : sum / n;
            return new double[]{Math.sqrt(Math.max(0, n == 0 ? 0 : sum2 / n - mean * mean)), mx, mean};
        }
    }

    static double lxOf(double c, double w) {
        if (w >= 0.9999) return 0;
        return clamp((c - w / 2) / (1 - w), 0, 1);
    }

    static double clamp(double v, double a, double b) { return v < a ? a : v > b ? b : v; }

    /**
     * v24: vertical placement of a split panel with headroom: the face centre sits at HEAD_Y of the panel's height
     * (eyes in the upper third, head + shoulders in view) instead of the panel's middle, which cut the shoulders and
     * left empty space above the head.
     */
    static final double HEAD_Y = 0.42;
    public static double lyOf(double c, double h) {
        if (h >= 0.9999) return 0;
        return clamp((c - HEAD_Y * h) / (1 - h), 0, 1);
    }

    // ---------------------------------------------------------------------------------------------- tuning
    static final double MIN_FACE_H = 0.045;     // smaller faces are background people / noise
    static final double REL_FACE_W = 0.33;      // faces narrower than this share of the frame's biggest face are ignored
    static final double CUT_ABS = 24, CUT_REL = 3.5;
    static final double TRACK_GAP_SEC = 0.8;
    static final double SWITCH_SEC = 0.8;       // a new speaker must lead this long
    static final double LEAD = 1.35;            // ... by this factor
    static final double ACT_FLOOR = 0.006;      // lip activity below this = no usable lip signal
    static final double HOLD_SEC = 1.0;         // face lost: hold, then ease to the centre
    // v21 (4.0, Sakib: the speaker must stay in the centre): 0.16 -> 0.10 of the window width, and a drift must last
    // 0.5 s (was 0.3 s) before the camera re-centres. Box (2 real podcasts, 90 s each): mean distance of the speaker
    // from the centre 5.7% -> 3.3% (JRE) and 6.4% -> 4.3% (Norm), no extra shakes; the camera moves a bit more often
    // (smooth eased moves only).
    static final double DEAD_ZONE = 0.12;       // x the window width (v24: 0.10 -> 0.12, fewer small corrections)
    static final double RECENTER_SEC = 0.5;
    static final double SETTLE = 0.03;
    static final double OMEGA = 2.8, OMEGA_FAST = 9.0;   // v24: 3.2 -> 2.8, calmer planned moves
    static final double MAX_SPEED = 1.8;        // window widths per second
    static final double CUT_FAR = 0.35;         // speaker switch further than this x window width = clean cut
    static final double SPLIT_MIN_SEC = 3.0;
    static final boolean DEBUG = System.getProperty("ftdebug") != null;
    static final double FIT_MARGIN = 0.9;
    static final double GROUP_KEEP = 0.94;      // v16: a two-shot stays a two-shot until the pair needs more than this
    static final double LOOK_AHEAD = 0.35;      // v16: a planned move starts this share of its length BEFORE the subject leaves
    static final double EDGE_KEEP = 0.46;       // v16: the framed face never comes closer to the window edge than this

    static final class Track {
        int shot;
        double[] cx, cy, w, h, m;
        int first = Integer.MAX_VALUE, last = -1, seen;
        double[] act;
        double[] sync, score;   // v16: lip-voice timing correlation, and activity weighted by it
        Track(int n, int shot) {
            this.shot = shot;
            cx = nan(n); cy = nan(n); w = nan(n); h = nan(n); m = nan(n);
        }
        boolean at(int i) { return !Double.isNaN(cx[i]); }
        double x0(int i) { return cx[i] - w[i] / 2; }
        double x1(int i) { return cx[i] + w[i] / 2; }
    }

    static double[] nan(int n) { double[] a = new double[n]; Arrays.fill(a, Double.NaN); return a; }

    public static Plan plan(List<Sample> s, double sampleFps, int srcW, int srcH, double dur, double outFps) {
        return plan(s, sampleFps, srcW, srcH, dur, outFps, 9.0 / 16.0);
    }

    /** v14: aspect = output width / height (9/16 Shorts, 1 square); the window and split panels follow it */
    public static Plan plan(List<Sample> s, double sampleFps, int srcW, int srcH, double dur, double outFps, double aspect) {
        Plan p = new Plan();
        p.outFps = outFps;
        p.aspect = aspect;
        p.cropW = srcW <= 0 || srcH <= 0 ? 1 : Math.min(1.0, srcH * aspect / srcW);
        int nOut = Math.max(1, (int) Math.ceil(dur * outFps - 1e-6));
        p.cx = new double[nOut];
        Arrays.fill(p.cx, 0.5);
        if (p.cropW > 0.92) { p.why = aspect < 0.7 ? "the video is already vertical" : "the whole frame already fits this format"; return p; }
        int n = s == null ? 0 : s.size();
        if (n < 3) { p.why = "too few frames"; return p; }
        double cw = p.cropW;

        // 0. faces worth following
        List<List<Face>> F = new ArrayList<>();
        int withFace = 0;
        for (Sample sm : s) {
            double maxW = 0;
            for (Face f : sm.faces) if (f.h() >= MIN_FACE_H) maxW = Math.max(maxW, f.w());
            List<Face> keep = new ArrayList<>();
            for (Face f : sm.faces) if (f.h() >= MIN_FACE_H && f.w() >= REL_FACE_W * maxW) keep.add(f);
            if (!keep.isEmpty()) withFace++;
            F.add(keep);
        }
        p.faceCoverage = withFace / (double) n;
        if (p.faceCoverage < 0.15) { p.why = "no faces found"; return p; }

        // 1. shots
        float[] cuts = new float[n];
        for (int i = 0; i < n; i++) cuts[i] = s.get(i).cut;
        double medCut = median(cuts, 1, n);
        int[] shot = new int[n];
        int shots = 1;
        for (int i = 1; i < n; i++) {
            if (cuts[i] > Math.max(CUT_ABS, CUT_REL * medCut)) shots++;
            shot[i] = shots - 1;
        }
        p.shots = shots;

        // 2. tracks
        List<Track> tracks = new ArrayList<>();
        int gap = (int) Math.round(TRACK_GAP_SEC * sampleFps);
        for (int i = 0; i < n; i++) {
            List<Face> fs = new ArrayList<>(F.get(i));
            List<Track> act = new ArrayList<>();
            for (Track t : tracks) if (t.shot == shot[i] && t.last >= i - gap) act.add(t);
            while (!fs.isEmpty()) {
                double best = 1e9; Track bt = null; Face bf = null;
                for (Face f : fs) for (Track t : act) {
                    int j = t.last;
                    double d = Math.abs(f.cx() - t.cx[j]) + 0.5 * Math.abs(f.cy() - t.cy[j]);
                    double lim = 0.6 * Math.max(f.w(), t.w[j]) + 0.03;
                    if (d < lim && d < best) { best = d; bt = t; bf = f; }
                }
                if (bt == null) {
                    for (Face f : fs) { Track t = new Track(n, shot[i]); put(t, i, f); tracks.add(t); }
                    break;
                }
                put(bt, i, bf);
                fs.remove(bf);
                act.remove(bt);
            }
        }

        // 2b. short gaps (a missed detection, a person seen only every other frame) are filled in
        int fill = Math.max(1, (int) Math.round(0.6 * sampleFps));
        for (Track t : tracks) {
            int prev = -1;
            for (int i = t.first; i <= t.last; i++) {
                if (!t.at(i)) continue;
                if (prev >= 0 && i - prev > 1 && i - prev - 1 <= fill) {
                    for (int k = prev + 1; k < i; k++) {
                        double f = (k - prev) / (double) (i - prev);
                        t.cx[k] = t.cx[prev] + (t.cx[i] - t.cx[prev]) * f; t.cy[k] = t.cy[prev] + (t.cy[i] - t.cy[prev]) * f;
                        t.w[k] = t.w[prev] + (t.w[i] - t.w[prev]) * f; t.h[k] = t.h[prev] + (t.h[i] - t.h[prev]) * f;
                    }
                }
                prev = i;
            }
        }

        // 3. lip activity + voice
        int win = Math.max(1, (int) Math.round(0.6 * sampleFps));
        for (Track t : tracks) {
            double[] d = nan(n);
            for (int i = t.first + 1; i <= t.last; i++) if (t.at(i) && t.at(i - 1) && !Double.isNaN(t.m[i]) && !Double.isNaN(t.m[i - 1])) d[i] = Math.abs(t.m[i] - t.m[i - 1]);
            t.act = new double[n];
            for (int i = 0; i < n; i++) {
                double sum = 0; int c = 0;
                for (int j = Math.max(0, i - win); j <= Math.min(n - 1, i + win); j++) if (!Double.isNaN(d[j])) { sum += d[j]; c++; }
                t.act[i] = c >= 2 ? sum / c : 0;
            }
        }
        float[] au = new float[n];
        for (int i = 0; i < n; i++) au[i] = s.get(i).audio;
        double a90 = percentile(au, 0.9);
        boolean[] voice = new boolean[n];
        for (int i = 0; i < n; i++) voice[i] = a90 <= 0 || au[i] > 0.22 * a90;
        // v16 (2.7): per-face audio timing. The speaker's lips move WHEN the voice gets louder; a listener's nodding or
        // smiling does not follow the audio. Each face's lip motion is correlated with the voice level over +-1.2 s and
        // the activity is weighted by it (lip motion x voice energy x timing), so the camera picks the person whose
        // mouth matches the sound, not just the one whose mouth moves most.
        int cwin = Math.max(3, (int) Math.round(1.2 * sampleFps));
        for (Track t : tracks) {
            double[] d = nan(n);
            for (int i = t.first + 1; i <= t.last; i++) if (t.at(i) && t.at(i - 1) && !Double.isNaN(t.m[i]) && !Double.isNaN(t.m[i - 1])) d[i] = Math.abs(t.m[i] - t.m[i - 1]);
            t.sync = new double[n];
            t.score = new double[n];
            for (int i = 0; i < n; i++) {
                t.sync[i] = a90 > 0 ? corr(d, au, Math.max(0, i - cwin), Math.min(n - 1, i + cwin)) : 0;
                t.score[i] = t.act[i] * (0.7 + 1.3 * Math.max(0, t.sync[i]));
            }
        }

        // 4 + 5. per shot: who is framed, and the target x per sample
        double[] target = nan(n);
        double[] span = new double[n];        // v16: width of what is framed (a face, or a group), for keeping it fully in frame
        boolean[] hard = new boolean[n];      // a clean cut happens AT this sample
        boolean[] fast = new boolean[n];      // a fast pan starts here
        boolean[] splitAt = new boolean[n];
        int switches = 0;
        int s0 = 0;
        while (s0 < n) {
            int s1 = s0;
            while (s1 + 1 < n && shot[s1 + 1] == shot[s0]) s1++;
            if (s0 > 0) hard[s0] = true;
            List<Track> ts = new ArrayList<>();
            for (Track t : tracks) if (t.shot == shot[s0] && t.seen >= Math.max(2, 0.25 * sampleFps)) ts.add(t);
            // the 2 most present people of the shot (split-screen candidates)
            ts.sort((a, b) -> b.seen - a.seen);
            Track cur = null;
            int run = 0; Track runT = null;
            int[] who = new int[n]; // -1 none, -2 group, else index in ts
            boolean wasGroup = false;
            for (int i = s0; i <= s1; i++) {
                List<Track> pres = new ArrayList<>();
                for (Track t : ts) if (t.at(i)) pres.add(t);
                who[i] = -1;
                if (pres.isEmpty()) {
                    // v16: no face (turned away, covered, out of shot): hold the last good framing for the rest of the
                    // shot instead of drifting to the centre after 1 s
                    if (i > s0 && !Double.isNaN(target[i - 1])) { target[i] = target[i - 1]; span[i] = span[i - 1]; }
                    continue;
                }
                double lo = 9, hi = -9;
                for (Track t : pres) { lo = Math.min(lo, t.x0(i)); hi = Math.max(hi, t.x1(i)); }
                // v16: two people sitting close enough are framed together; once together they stay together until
                // they clearly no longer fit (hysteresis: no flicker between a two-shot and a single)
                if (hi - lo <= cw * (wasGroup && pres.size() >= 2 ? GROUP_KEEP : FIT_MARGIN)) {
                    wasGroup = pres.size() >= 2;
                    target[i] = (lo + hi) / 2;
                    span[i] = hi - lo;
                    who[i] = -2;
                    if (cur == null || !cur.at(i)) cur = biggest(pres, i);
                    run = 0;
                    continue;
                }
                wasGroup = false;
                if (cur == null || (!cur.at(i) && i - lastSeen(cur, i) > HOLD_SEC * sampleFps)) {
                    cur = opener(pres, i, s1, sampleFps);
                    run = 0;
                }
                // challenger
                Track lead = null; double la = 0;
                for (Track t : pres) if (t.score[i] > la) { la = t.score[i]; lead = t; }
                double ca = cur.at(i) ? cur.score[i] : 0;
                if (lead != null && lead != cur && voice[i] && lead.act[i] > ACT_FLOOR && la > LEAD * ca) {
                    if (runT != lead) { runT = lead; run = 0; }
                    run++;
                } else if (run > 0) run = Math.max(0, run - 2);
                if (run >= SWITCH_SEC * sampleFps) {
                    int at = Math.max(s0, i - run + 1);
                    // start where the new speaker's lips started moving
                    while (at > s0 + 1 && runT.at(at - 1) && runT.act[at - 1] > ACT_FLOOR && runT.score[at - 1] > LEAD * scoreOf(cur, at - 1)) at--;
                    double from = Double.isNaN(target[Math.max(s0, at - 1)]) ? 0.5 : target[Math.max(s0, at - 1)];
                    cur = runT; run = 0; runT = null;
                    switches++;
                    for (int j = at; j <= i; j++) { target[j] = cur.at(j) ? cur.cx[j] : from; span[j] = cur.at(j) ? cur.w[j] : span[j]; }
                    if (Math.abs(cur.cx[i] - from) > CUT_FAR * cw) hard[at] = true; else fast[at] = true;
                }
                target[i] = cur.at(i) ? cur.cx[i] : (Double.isNaN(target[i - 1 < 0 ? 0 : i - 1]) ? 0.5 : target[Math.max(0, i - 1)]);
                span[i] = cur.at(i) ? cur.w[i] : (i > 0 ? span[i - 1] : 0);
                who[i] = ts.indexOf(cur);
            }
            // split screen: two people who do not fit, talking back and forth / no clear speaker
            if (ts.size() >= 2) splits(p, ts.get(0), ts.get(1), s0, s1, sampleFps, cw, who, ts, voice, splitAt);
            s0 = s1 + 1;
        }
        p.switches = switches;

        // lost faces: held (above). v16: a shot that opens before its first face is framed where that face will be
        // (look-ahead), not at the centre; only a shot with no face at all stays centred
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(target[i])) continue;
            int j = i;
            while (j < n && Double.isNaN(target[j]) && shot[j] == shot[i]) j++;
            double v = j < n && shot[j] == shot[i] ? target[j] : 0.5, sp = j < n && shot[j] == shot[i] ? span[j] : 0;
            for (int k = i; k < j; k++) { target[k] = v; span[k] = sp; }
        }

        // 6. output frames
        double[] tg = new double[nOut];
        boolean[] hardF = new boolean[nOut], fastF = new boolean[nOut];
        for (int k = 0; k < nOut; k++) {
            double ts = k / outFps * sampleFps;
            int j = Math.min(n - 1, (int) Math.floor(ts + 1e-9));
            int j2 = Math.min(n - 1, j + 1);
            double fr = ts - j;
            boolean boundary = hard[j2] && j2 != j;
            tg[k] = boundary ? target[j] : target[j] + (target[j2] - target[j]) * Math.max(0, Math.min(1, fr));
        }
        for (int i = 1; i < n; i++) {
            int k = (int) Math.ceil(i / sampleFps * outFps - 1e-9);
            if (k < nOut) { if (hard[i]) hardF[k] = true; if (fast[i]) fastF[k] = true; }
        }
        // robust smoothing inside each segment (median over 0.6 s), never across a cut
        int mw = (int) Math.round(0.3 * outFps);
        double[] sm = new double[nOut];
        int a = 0;
        while (a < nOut) {
            int b = a;
            while (b + 1 < nOut && !hardF[b + 1]) b++;
            for (int k = a; k <= b; k++) {
                int lo = Math.max(a, k - mw), hi = Math.min(b, k + mw);
                double[] wv = Arrays.copyOfRange(tg, lo, hi + 1);
                Arrays.sort(wv);
                sm[k] = wv[wv.length / 2];
            }
            a = b + 1;
        }
        // span per output frame (same interpolation, no smoothing needed)
        double[] spO = new double[nOut];
        for (int k = 0; k < nOut; k++) spO[k] = span[Math.min(n - 1, (int) Math.floor(k / outFps * sampleFps + 1e-9))];
        // 7. v16 (2.7) camera: planned, look-ahead moves. The whole clip is known before the render, so the camera does
        // not chase the face (2.4-2.6: a spring that always lagged a little). It holds perfectly still while the
        // subject stays inside the dead zone; when the subject really moves (out of the dead zone for 0.3 s, or about
        // to touch the edge) it plans one eased move to where the subject settles, starting slightly BEFORE the
        // subject leaves, so the move and the person arrive together. Camera cuts in the source and real speaker
        // changes far away = an instant cut (no pan across a cut); near speaker changes = a quick eased move.
        double half = cw / 2;
        int hc = 0, lastEnd = -1, lastMoveDir = 0;
        int confirm = Math.max(1, (int) Math.round(RECENTER_SEC * outFps));
        int a2 = 0;
        while (a2 < nOut) {
            int b2 = a2;
            while (b2 + 1 < nOut && !hardF[b2 + 1]) b2++;
            if (hardF[a2]) hc++;
            // shot start: frame where the subject is over the first half second (instant, no pan in from elsewhere)
            double P = clamp(med(sm, a2, Math.min(b2, a2 + (int) Math.round(0.5 * outFps))), half, 1 - half);
            lastEnd = a2 - 1; lastMoveDir = 0;
            for (int k = a2; k <= b2; k++) p.cx[k] = P;
            int k = a2;
            while (k <= b2) {
                double want = clamp(sm[k], half, 1 - half);
                double dev = Math.abs(want - P);
                double reach = Math.abs(sm[k] - P) + spO[k] / 2;   // how far the framed face/group reaches from the centre
                boolean cutOff = reach > 0.52 * cw && Math.abs(want - P) > SETTLE * cw;          // part of it is outside
                boolean edge = cutOff || (spO[k] < 0.6 * cw && reach > EDGE_KEEP * cw && Math.abs(want - P) > SETTLE * cw);
                boolean quick = fastF[k];
                boolean need = quick || edge;
                if (!need && dev > DEAD_ZONE * cw) {
                    // sustained? (a quick lean or a gesture that comes back does not move the camera)
                    int hold = 0;
                    for (int q = k; q <= Math.min(b2, k + confirm); q++) if (Math.abs(clamp(sm[q], half, 1 - half) - P) > 0.8 * DEAD_ZONE * cw) hold++;
                    need = hold >= Math.min(confirm, b2 - k + 1);
                }
                if (!need) { p.cx[k] = P; k++; continue; }
                // where the subject settles: the median of the next 0.8 s
                // where to go: if the subject's path over the next 1.5 s fits in the dead zone, the position that keeps
                // the whole path framed (the camera knows where the speaker goes next); else where it settles (0.8 s)
                double Q;
                {
                    int e15 = Math.min(b2, k + (int) Math.round(1.5 * outFps));
                    double lo = 9, hi = -9;
                    for (int q = k; q <= e15; q++) { lo = Math.min(lo, sm[q]); hi = Math.max(hi, sm[q]); }
                    Q = hi - lo <= 1.6 * DEAD_ZONE * cw ? (lo + hi) / 2 : med(sm, k, Math.min(b2, k + (int) Math.round(0.8 * outFps)));
                    Q = clamp(Q, half, 1 - half);
                }
                if (Math.abs(Q - P) < SETTLE * cw) { p.cx[k] = P; k++; continue; }
                // no back-and-forth: turning around right after a move waits 0.7 s (unless the face would leave)
                int dirNow = Q > P ? 1 : -1;
                if (!quick && !cutOff && dirNow == -lastMoveDir && k - lastEnd < 0.7 * outFps) { p.cx[k] = P; k++; continue; }
                lastMoveDir = dirNow;
                if (DEBUG) System.out.println(String.format(Locale.US, "move k=%d P=%.3f Q=%.3f quick=%b edge=%b cut=%b reach=%.3f span=%.3f cw=%.3f", k, P, Q, quick, edge, cutOff, reach / cw, spO[k] / cw, cw));
                double T = quick ? 0.3 : clamp(0.6 + 1.4 * Math.abs(Q - P) / cw, 0.7, 1.8);
                int len = Math.max(2, (int) Math.round(T * outFps));
                int st = Math.max(Math.max(a2, lastEnd + 1), k - (int) Math.round((quick ? 0.1 : LOOK_AHEAD * T) * outFps));
                int en = Math.min(b2, st + len);
                double from = st > a2 ? p.cx[st - 1] : P;
                for (int q = st; q <= en; q++) {
                    double u = en == st ? 1 : (q - st) / (double) (en - st);
                    double e = u * u * u * (u * (u * 6 - 15) + 10);   // smootherstep: zero speed and acceleration at both ends
                    p.cx[q] = from + (Q - from) * e;
                }
                P = Q; lastEnd = en; k = en + 1;
                if (!quick) p.moves++;
                for (int q = k; q <= b2; q++) p.cx[q] = P;
            }
            a2 = b2 + 1;
        }
        p.hardCuts = hc;
        p.cutAt = hardF;
        mergeSplits(p);
        p.usable = true;
        return p;
    }

    static double actOf(Track t, int i) { return t != null && t.at(i) ? t.act[i] : 0; }
    static double scoreOf(Track t, int i) { return t != null && t.at(i) && t.score != null ? t.score[i] : actOf(t, i); }

    /** Pearson correlation of a (NaN = missing) and b over [lo, hi]; 0 when too few points or no variation */
    static double corr(double[] a, float[] b, int lo, int hi) {
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0; int c = 0;
        for (int i = lo; i <= hi; i++) {
            if (Double.isNaN(a[i])) continue;
            double x = a[i], y = b[i];
            sa += x; sb += y; saa += x * x; sbb += y * y; sab += x * y; c++;
        }
        if (c < 4) return 0;
        double va = saa - sa * sa / c, vb = sbb - sb * sb / c;
        if (va <= 1e-12 || vb <= 1e-12) return 0;
        return (sab - sa * sb / c) / Math.sqrt(va * vb);
    }

    static int lastSeen(Track t, int i) { for (int j = Math.min(i, t.last); j >= 0; j--) if (t.at(j)) return j; return -999999; }

    static void put(Track t, int i, Face f) {
        t.cx[i] = f.cx(); t.cy[i] = f.cy(); t.w[i] = f.w(); t.h[i] = f.h(); t.m[i] = f.mouth < 0 ? Double.NaN : f.mouth;
        t.first = Math.min(t.first, i); t.last = Math.max(t.last, i); t.seen++;
    }

    static Track biggest(List<Track> pres, int i) {
        Track b = null; double bs = -1;
        for (Track t : pres) {
            double sc = t.w[i] * t.h[i] * (1.0 - 0.8 * Math.abs(t.cx[i] - 0.5));
            if (sc > bs) { bs = sc; b = t; }
        }
        return b;
    }

    /** who the shot opens on: the one talking in its first 2 s, else the largest, most central face */
    static Track opener(List<Track> pres, int i, int s1, double fps) {
        Track b = null; double bs = 0;
        int e = Math.min(s1, i + (int) Math.round(2 * fps));
        for (Track t : pres) {
            double sum = 0;
            for (int j = i; j <= e; j++) sum += scoreOf(t, j);
            sum /= (e - i + 1);
            if (sum > bs) { bs = sum; b = t; }
        }
        if (b != null && bs > ACT_FLOOR) {
            double second = 0;
            for (Track t : pres) if (t != b) { double sum = 0; for (int j = i; j <= e; j++) sum += scoreOf(t, j); second = Math.max(second, sum / (e - i + 1)); }
            if (bs > LEAD * second) return b;
        }
        return biggest(pres, i);
    }

    static void splits(Plan p, Track A, Track B, int s0, int s1, double fps, double cw, int[] who, List<Track> ts,
                       boolean[] voice, boolean[] splitAt) {
        if (A.cx[A.first] > B.cx[B.first]) { Track t = A; A = B; B = t; }   // left person on top
        int gap = (int) Math.round(0.6 * fps);
        int i = s0;
        while (i <= s1) {
            if (!both(A, B, i, cw)) { i++; continue; }
            int j = i, miss = 0, lastOk = i;
            while (j + 1 <= s1) {
                if (both(A, B, j + 1, cw)) { lastOk = j + 1; miss = 0; } else if (++miss > gap) break;
                j++;
            }
            int a = i, b = lastOk;
            double len = (b - a + 1) / fps;
            if (len >= SPLIT_MIN_SEC) {
                int sw = 0, amb = 0, voiced = 0, prev = Integer.MIN_VALUE;
                for (int k = a; k <= b; k++) {
                    if (who[k] >= 0 && prev != Integer.MIN_VALUE && who[k] != prev) sw++;
                    if (who[k] >= 0) prev = who[k];
                    if (voice[k]) {
                        voiced++;
                        double x = actOf(A, k), y = actOf(B, k);
                        if (Math.max(x, y) < ACT_FLOOR || Math.max(x, y) < LEAD * Math.min(x, y)) amb++;
                    }
                }
                boolean backAndForth = sw >= 2 && len / sw < 6.0;
                boolean unclear = voiced > 0 && amb >= 0.6 * voiced;
                if (backAndForth || unclear) {
                    addSplit(p, A, B, a, b, fps, splitAt, ts);
                } else {
                    // v19 (3.0, Sakib: "split screen time aane pe automatically"): inside a long two-person shot, the
                    // moments that need both faces - a quick back-and-forth, both talking at once / reacting - get a
                    // split screen of their own, and the single speaker comes back when one person holds the floor
                    for (int[] r : localSplits(A, B, a, b, fps, voice)) addSplit(p, A, B, r[0], r[1], fps, splitAt, ts);
                }
            }
            i = Math.max(j, i) + 1;
        }
    }

    static void addSplit(Plan p, Track A, Track B, int a, int b, double fps, boolean[] splitAt) {
        addSplit(p, A, B, a, b, fps, splitAt, null);
    }

    /** v22: how many skipped split parts (the two people sit too close to give each a clean half) */
    static final String SPLIT_SKIP = "split skipped";

    /**
     * v22 (4.1, Sakib's screenshot: each half showed a sliver of the other man - shoulder / tattooed arm): a split part is
     * only added when each half can be framed on its own person WITHOUT any part of the neighbour. The neighbour's body
     * reaches ~BODY_HALF face widths to each side of his face centre (shoulders, jacket, arms); each panel must stay
     * inside the free space between the neighbours' bodies, keep its own face (plus a margin) fully inside, and keep the
     * face near the panel centre. The widest panel that meets all of this is used (zooming in only as much as needed);
     * when even the tightest allowed panel cannot do it, there is no split here and the single-speaker camera stays.
     */
    static boolean addSplit(Plan p, Track A, Track B, int a, int b, double fps, boolean[] splitAt, List<Track> ts) {
        double srcAspect = p.cropW > 0 ? p.aspect / p.cropW : 16.0 / 9.0;   // W/H
        double fh = Math.max(med(A.h, a, b), med(B.h, a, b));
        double[] g = splitGeometry(A, B, a, b, ts, srcAspect, p.aspect, fh);
        if (g == null) { p.skippedSplits++; return false; }
        Split sp = new Split();
        sp.start = a / fps; sp.end = (b + 1) / fps;
        sp.topCx = g[0]; sp.botCx = g[1]; sp.pw = g[2]; sp.ph = g[3]; sp.fh = fh;
        sp.topCy = med(A.cy, a, b); sp.botCy = med(B.cy, a, b);
        p.panelH = Math.max(p.panelH, fh);   // face height for now; mergeSplits keeps the largest panel for the log
        p.splits.add(sp);
        for (int k = a; k <= b; k++) splitAt[k] = true;
        return true;
    }

    static final double BODY_HALF = 2.0;      // v22: neighbour's body half-width in face widths (shoulders + jacket)
    static final double FACE_MARGIN = 0.15;   // v22: own face + this many face widths each side must be inside the panel
    static final double CENTRE_OFF = 0.22;    // v22: face centre at most this share of the panel width off the panel centre
    // v24 (Sakib: "less zoom"): smallest panel 0.28 -> 0.40 of the source height (a 0.28 panel of a 1080p video was
    // 302 px enlarged 3.2x into a 960 px half). A pair that cannot be split cleanly at this size stays one speaker camera.
    static final double PH_MIN = 0.40;

    /**
     * v22: {topCx, botCx, pw, ph} for a clean split of A (left, top panel) and B (right, bottom panel) over [a, b], or null
     * when no clean panel exists. ts = every person of the shot (a third person counts as a neighbour too).
     */
    static double[] splitGeometry(Track A, Track B, int a, int b, List<Track> ts, double srcAspect, double aspect, double fh) {
        double pa = 2 * aspect, pmax = aspect < 0.7 ? 0.6 : 0.95;
        // v24: start from a wider panel (head + shoulders + room: 4.2 face heights, was 3.4) and never go below
        // 2.6 face heights (was 1.6: the face filled the half = the "zoomed in" look)
        double ph0 = clamp(4.2 * fh, 0.45, 1.0);
        double pw0 = Math.min(pmax, ph0 * pa / srcAspect);
        double phMin = Math.max(PH_MIN, 2.6 * fh);
        double pwMin = phMin * pa / srcAspect;
        List<double[]> people = new ArrayList<>();   // {cx, w, isA, isB}
        people.add(new double[]{med(A.cx, a, b), med(A.w, a, b), 1, 0});
        people.add(new double[]{med(B.cx, a, b), med(B.w, a, b), 0, 1});
        if (ts != null) for (Track t : ts) {
            if (t == A || t == B) continue;
            int seen = 0; for (int k = a; k <= b; k++) if (t.at(k)) seen++;
            if (seen >= Math.max(2, 0.2 * (b - a + 1))) people.add(new double[]{med(t.cx, a, b), med(t.w, a, b), 0, 0});
        }
        double[] fa = people.get(0), fb = people.get(1);
        double[] ia = freeSpan(fa, people), ib = freeSpan(fb, people);
        if (ia == null || ib == null) return null;
        for (int step = 0; step <= 40; step++) {
            double pw = pw0 - (pw0 - pwMin) * step / 40.0;
            if (pw < pwMin - 1e-9) break;
            double ca = place(fa, ia, pw), cb = place(fb, ib, pw);
            if (!Double.isNaN(ca) && !Double.isNaN(cb)) return new double[]{ca, cb, pw, pw * srcAspect / pa};
        }
        return null;
    }

    /** v22: the horizontal span free of every other person's body around person f, or null when f's own face is not clear */
    static double[] freeSpan(double[] f, List<double[]> people) {
        double lo = 0, hi = 1;
        for (double[] o : people) {
            if (o == f) continue;
            double body = BODY_HALF * Math.max(o[1], 0.02);
            if (o[0] < f[0]) lo = Math.max(lo, o[0] + body); else hi = Math.min(hi, o[0] - body);
        }
        double m = FACE_MARGIN * f[1];
        if (f[0] - f[1] / 2 - m < lo - 1e-9 || f[0] + f[1] / 2 + m > hi + 1e-9) return null;
        return new double[]{lo, hi};
    }

    /** v22: panel centre for person f inside the free span with panel width pw, NaN when it does not fit cleanly */
    static double place(double[] f, double[] span, double pw) {
        double lo = Math.max(span[0], 0), hi = Math.min(span[1], 1);
        if (hi - lo < pw - 1e-9) return Double.NaN;
        double c = clamp(f[0], lo + pw / 2, hi - pw / 2);
        double m = FACE_MARGIN * f[1];
        if (f[0] - f[1] / 2 - m < c - pw / 2 - 1e-9 || f[0] + f[1] / 2 + m > c + pw / 2 + 1e-9) return Double.NaN;
        if (Math.abs(f[0] - c) > CENTRE_OFF * pw + 1e-9) return Double.NaN;
        return c;
    }

    static final double TURN_MIN_SEC = 0.8;    // v19: a turn of speech shorter than this is a "haan", not a turn
    static final double SPLIT_WIN_SEC = 3.0;   // v19: +- window in which turns / overlap are counted
    static final double SPLIT_ENTER_SEC = 1.0, SPLIT_EXIT_SEC = 2.0, SPLIT_LOCAL_MIN_SEC = 2.5;

    /**
     * v19: who has the floor at each sample of a two-person run: 0 = A, 1 = B, 2 = both at once (overlap / reaction),
     * -1 = nobody / no usable lip signal. Lip activity weighted by voice timing (score), as the speaker switch uses.
     */
    static int[] floorOf(Track A, Track B, int a, int b, boolean[] voice) {
        int[] f = new int[b - a + 1];
        for (int k = a; k <= b; k++) {
            double x = scoreOf(A, k), y = scoreOf(B, k), ax = actOf(A, k), ay = actOf(B, k);
            int v = -1;
            if (voice[k] && Math.max(ax, ay) >= ACT_FLOOR) {
                if (x > LEAD * y) v = 0;
                else if (y > LEAD * x) v = 1;
                else if (Math.min(ax, ay) >= ACT_FLOOR) v = 2;
            }
            f[k - a] = v;
        }
        return f;
    }

    /**
     * v19: the split-screen parts inside one two-person run [a, b]. A sample "wants" a split when, within +-3 s, the floor
     * changed hands at least twice (turns of >= 0.8 s each) or both people were active together for >= 35 % of the voiced
     * samples. Hysteresis: the split starts after 1 s of wanting it, ends after 2 s of not wanting it (one person holds the
     * floor), and a part shorter than 2.5 s is dropped - so the layout never flickers.
     */
    static List<int[]> localSplits(Track A, Track B, int a, int b, double fps, boolean[] voice) {
        List<int[]> out = new ArrayList<>();
        int n = b - a + 1;
        if (n < SPLIT_LOCAL_MIN_SEC * fps) return out;
        int[] fl = floorOf(A, B, a, b, voice);
        // debounced turns: runs of the same holder (0/1) lasting >= TURN_MIN_SEC; record the sample where each turn starts
        int minRun = Math.max(1, (int) Math.round(TURN_MIN_SEC * fps));
        List<Integer> turnAt = new ArrayList<>();
        int holder = -1, runStart = 0, runLen = 0, cand = -1;
        for (int k = 0; k < n; k++) {
            int v = fl[k];
            if (v == 0 || v == 1) {
                if (v == cand) runLen++; else { cand = v; runStart = k; runLen = 1; }
                if (runLen >= minRun && cand != holder) {
                    if (holder != -1) turnAt.add(runStart);
                    holder = cand;
                }
            }
        }
        int w = (int) Math.round(SPLIT_WIN_SEC * fps);
        boolean[] want = new boolean[n];
        for (int k = 0; k < n; k++) {
            int lo = Math.max(0, k - w), hi = Math.min(n - 1, k + w);
            int turns = 0;
            for (int t : turnAt) if (t >= lo && t <= hi) turns++;
            int voiced = 0, bothN = 0;
            for (int j = lo; j <= hi; j++) { if (fl[j] >= 0) voiced++; if (fl[j] == 2) bothN++; }
            want[k] = turns >= 2 || (voiced >= fps && bothN >= 0.35 * voiced);
        }
        int enter = (int) Math.round(SPLIT_ENTER_SEC * fps), exit = (int) Math.round(SPLIT_EXIT_SEC * fps);
        boolean on = false;
        int st = -1, yes = 0, no = 0, lastWant = -1;
        for (int k = 0; k < n; k++) {
            if (want[k]) { yes++; no = 0; lastWant = k; } else { no++; yes = 0; }
            if (!on && yes >= enter) { on = true; st = k - yes + 1; }
            else if (on && no >= exit) {
                on = false;
                int en = lastWant;
                if (en - st + 1 >= SPLIT_LOCAL_MIN_SEC * fps) out.add(new int[]{a + st, a + en});
            }
        }
        if (on) { int en = n - 1; if (en - st + 1 >= SPLIT_LOCAL_MIN_SEC * fps) out.add(new int[]{a + st, a + en}); }
        return out;
    }

    static boolean both(Track A, Track B, int i, double cw) {
        if (!A.at(i) || !B.at(i)) return false;
        double lo = Math.min(A.x0(i), B.x0(i)), hi = Math.max(A.x1(i), B.x1(i));
        return hi - lo > cw * FIT_MARGIN;
    }

    static void mergeSplits(Plan p) {
        if (p.splits.isEmpty()) return;
        p.splits.sort((x, y) -> Double.compare(x.start, y.start));
        List<Split> out = new ArrayList<>();
        for (Split s : p.splits) {
            Split last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && s.start - last.end < 1.5 && Math.abs(s.topCx - last.topCx) < 0.08 && Math.abs(s.botCx - last.botCx) < 0.08) {
                last.end = s.end;
                // v22: the merged part keeps the tighter (cleaner) panel of the two, centred like the tighter one
                if (s.pw < last.pw) { last.pw = s.pw; last.ph = s.ph; last.topCx = s.topCx; last.botCx = s.botCx; }
                last.fh = Math.max(last.fh, s.fh);
            } else out.add(s);
        }
        p.splits = out;
        double maxW = 0, maxH = 0;
        for (Split s : p.splits) {
            double pw = s.pw, ph = s.ph, fh = s.fh;
            // v16: eyes on the upper third of the panel (eyes sit ~0.1 face heights above the face box centre)
            s.topCy = s.topCy - 0.1 * fh + ph / 6;
            s.botCy = s.botCy - 0.1 * fh + ph / 6;
            s.topCx = clamp(s.topCx, pw / 2, 1 - pw / 2); s.botCx = clamp(s.botCx, pw / 2, 1 - pw / 2);
            s.topCy = clamp(s.topCy, ph / 2, 1 - ph / 2); s.botCy = clamp(s.botCy, ph / 2, 1 - ph / 2);
            maxW = Math.max(maxW, pw); maxH = Math.max(maxH, ph);
        }
        p.panelH = maxH; p.panelW = maxW;
    }

    static double med(double[] v, int a, int b) {
        double[] t = new double[b - a + 1]; int c = 0;
        for (int i = a; i <= b; i++) if (!Double.isNaN(v[i])) t[c++] = v[i];
        if (c == 0) return 0.5;
        t = Arrays.copyOf(t, c); Arrays.sort(t); return t[c / 2];
    }

    static double median(float[] v, int a, int b) {
        if (b <= a) return 0;
        float[] t = Arrays.copyOfRange(v, a, b); Arrays.sort(t); return t[t.length / 2];
    }

    static double percentile(float[] v, double q) {
        if (v.length == 0) return 0;
        float[] t = v.clone(); Arrays.sort(t); return t[(int) Math.min(t.length - 1, Math.floor(q * t.length))];
    }

    // ---------------------------------------------------------------------------------------------- (de)serialise
    /** compact text form of the samples (cache + test harness): one line per sample "audio cut|x0 y0 x1 y1 m|..." */
    public static String samplesToText(List<Sample> s) {
        StringBuilder sb = new StringBuilder();
        for (Sample sm : s) {
            sb.append(String.format(Locale.US, "%.5f %.2f", sm.audio, sm.cut));
            for (Face f : sm.faces) sb.append(String.format(Locale.US, "|%.4f %.4f %.4f %.4f %.4f", f.x0, f.y0, f.x1, f.y1, f.mouth));
            sb.append('\n');
        }
        return sb.toString();
    }

    public static List<Sample> samplesFromText(String txt) {
        List<Sample> out = new ArrayList<>();
        if (txt == null) return out;
        for (String line : txt.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\|");
            String[] h = parts[0].trim().split("\\s+");
            List<Face> fs = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String[] v = parts[i].trim().split("\\s+");
                if (v.length < 5) continue;
                fs.add(new Face(Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2]), Float.parseFloat(v[3]), Float.parseFloat(v[4])));
            }
            out.add(new Sample(fs, Float.parseFloat(h[0]), h.length > 1 ? Float.parseFloat(h[1]) : 0));
        }
        return out;
    }
}
