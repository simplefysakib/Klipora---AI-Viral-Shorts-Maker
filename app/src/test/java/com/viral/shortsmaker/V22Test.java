package com.viral.shortsmaker;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.viral.shortsmaker.core.*;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * v22 (4.1): Sakib's real 4.0 run. Clean split-screen halves (no sliver of the neighbour), one path per frame in the
 * segmented render graph, honest monotonic render progress + a time left that does not jump.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class V22Test {
    static final double SF = 8;   // samples per second (FaceAnalyzer.FPS)

    static FaceTrack.Face face(double cx, double cy, double h, boolean talking, int i, Random r) {
        double w = h * 9 / 16.0;
        double m = talking ? 0.05 + 0.05 * Math.abs(Math.sin(i * 1.7)) + 0.02 * r.nextDouble() : 0.02 + 0.002 * r.nextDouble();
        double jx = (r.nextDouble() - 0.5) * 0.004, jy = (r.nextDouble() - 0.5) * 0.004;
        return new FaceTrack.Face((float) (cx - w / 2 + jx), (float) (cy - h / 2 + jy), (float) (cx + w / 2 + jx), (float) (cy + h / 2 + jy), (float) m);
    }

    /** two people at ax / bx (face height h) talking back and forth every 2 s for sec seconds; optional third at cx */
    static List<FaceTrack.Sample> backAndForth(double sec, double ax, double bx, double h, double cx3) {
        Random r = new Random(11);
        List<FaceTrack.Sample> s = new ArrayList<>();
        int n = (int) Math.round(sec * SF);
        for (int i = 0; i < n; i++) {
            double t = i / SF;
            boolean a = ((int) (t / 2)) % 2 == 0;
            List<FaceTrack.Face> fs = new ArrayList<>(Arrays.asList(face(ax, 0.40, h, a, i, r), face(bx, 0.41, h, !a, i, r)));
            if (cx3 > 0) fs.add(face(cx3, 0.42, h * 0.8, false, i, r));
            s.add(new FaceTrack.Sample(fs, 0.1f, 2f + r.nextFloat()));
        }
        return s;
    }

    static FaceTrack.Plan plan(List<FaceTrack.Sample> s, double sec) { return FaceTrack.plan(s, SF, 1920, 1080, sec, 30, 9.0 / 16.0); }

    /** 4.0's panel width for face height fh (fractions), 9:16 output from 16:9: the crop that showed the sliver */
    static double oldPw(double fh) { double ph = Math.max(0.3, Math.min(1.0, 3.4 * fh)); return Math.min(0.6, ph * (9.0 / 8.0) / (16.0 / 9.0)); }

    // ------------------------------------------------------------------------------------------------ split screen
    @Test public void closeSeatedPairGetsCleanHalves() {
        // his screenshot: two men side by side, faces ~0.26 of the width apart. 4.0's 0.34-wide panel around the left
        // man reached past the right man's shoulder line (and vice versa).
        double ax = 0.37, bx = 0.63, h = 0.16, w = h * 9 / 16.0;
        FaceTrack.Plan p = plan(backAndForth(16, ax, bx, h, 0), 16);
        assertTrue(p.usable);
        assertFalse("expected a split part", p.splits.isEmpty());
        double bodyLeftOfB = bx - 2.0 * w, bodyRightOfA = ax + 2.0 * w;
        // 4.0 geometry: the sliver
        double opw = oldPw(h);
        assertTrue("4.0 top panel reached the neighbour", ax + opw / 2 > bodyLeftOfB);
        for (FaceTrack.Split sp : p.splits) {
            double topR = sp.topCx + sp.pw / 2, topL = sp.topCx - sp.pw / 2;
            double botL = sp.botCx - sp.pw / 2, botR = sp.botCx + sp.pw / 2;
            assertTrue("top half must end before the right man's body: " + topR + " vs " + bodyLeftOfB, topR <= bodyLeftOfB + 0.006);
            assertTrue("bottom half must start after the left man's body: " + botL + " vs " + bodyRightOfA, botL >= bodyRightOfA - 0.006);
            // own face fully inside its half
            assertTrue(topL <= ax - w / 2 && topR >= ax + w / 2);
            assertTrue(botL <= bx - w / 2 && botR >= bx + w / 2);
            assertTrue(sp.pw < opw);                                   // zoomed in only because it had to
            assertTrue(sp.ph >= 0.28 - 1e-9 && sp.ph <= 1.0);
            assertEquals(sp.ph, sp.pw * (16.0 / 9.0) / (9.0 / 8.0), 1e-6);   // panel keeps the 9:8 shape
        }
    }

    @Test public void pairTooCloseForCleanHalvesStaysOnTheSpeaker() {
        // faces almost touching: no clean half exists -> no split, the single-speaker camera keeps going
        FaceTrack.Plan p = plan(backAndForth(16, 0.44, 0.56, 0.16, 0), 16);
        assertTrue(p.usable);
        assertTrue(p.splits.isEmpty());
    }

    @Test public void farApartPairKeepsTheFullPanel() {
        // nothing to avoid: v24 uses a wider panel than 4.0 did (less zoom), never a narrower one
        FaceTrack.Plan p = plan(backAndForth(14, 0.18, 0.82, 0.22, 0), 14);
        assertEquals(1, p.splits.size());
        assertTrue(p.splits.get(0).pw >= oldPw(0.22) - 0.002);
    }

    @Test public void thirdPersonBetweenThemIsKeptOut() {
        double ax = 0.20, bx = 0.80, cx = 0.50, h = 0.18;
        FaceTrack.Plan p = plan(backAndForth(16, ax, bx, h, cx), 16);
        for (FaceTrack.Split sp : p.splits) {
            double wc = h * 0.8 * 9 / 16.0;
            assertTrue(sp.topCx + sp.pw / 2 <= cx - 2.0 * wc + 0.006);
            assertTrue(sp.botCx - sp.pw / 2 >= cx + 2.0 * wc - 0.006);
        }
    }

    @Test public void splitGeometryUnit() {
        // direct check of the free-span rule
        List<double[]> people = new ArrayList<>();
        double[] a = {0.37, 0.09, 1, 0}, b = {0.63, 0.09, 0, 1};
        people.add(a); people.add(b);
        double[] span = FaceTrackAccess.freeSpan(a, people);
        assertNotNull(span);
        assertEquals(0.63 - 0.18, span[1], 1e-9);
        double c = FaceTrackAccess.place(a, span, 0.2);
        assertFalse(Double.isNaN(c));
        assertTrue(c + 0.1 <= span[1] + 1e-9);
        assertTrue(Double.isNaN(FaceTrackAccess.place(a, span, 0.5)));   // too wide: would reach the neighbour
    }

    // ------------------------------------------------------------------------------------------------ render graph
    @Test public void segmentsCoverTheClipAndMatchTheOldSplitFrames() {
        FaceTrack.Plan p = plan(backAndForth(16, 0.37, 0.63, 0.16, 0), 16);
        List<int[]> segs = p.segments();
        int pos = 0;
        for (int[] g : segs) { assertEquals(pos, g[1]); assertTrue(g[2] > g[1]); pos = g[2]; }
        assertEquals(p.cx.length, pos);
        // every frame goes the same way 4.0's enable expression sent it (+-1 frame at a part's edge)
        String en = p.splitEnable();
        assertNotNull(en);
        int mismatch = 0;
        for (int k = 0; k < p.cx.length; k++) {
            double t = k / 30.0;
            boolean old = false;
            for (FaceTrack.Split sp : p.splits) if (t >= sp.start - 0.5 / 30 && t <= sp.end - 0.5 / 30) old = true;
            boolean now = false;
            for (int[] g : segs) if (g[0] == 1 && k >= g[1] && k < g[2]) now = true;
            if (old != now) mismatch++;
        }
        assertTrue("mismatching frames: " + mismatch, mismatch <= p.splits.size());
    }

    @Test public void trackGraphRunsOnePathPerFrame() {
        FaceTrack.Plan p = plan(backAndForth(16, 0.37, 0.63, 0.16, 0), 16);
        String g = FfmpegCommands.trackGraph(new FfmpegCommands.Track("/d/ft_0.cmd", p, 1080), "/d/c.ass", "/d/fonts", 1080, "yuv420p");
        assertTrue(g, g.contains("concat=n=" + p.segments().size() + ":v=1:a=0"));
        assertFalse(g.contains("overlay"));
        assertTrue(g.contains("trim=start_frame="));
        assertTrue(g.contains("ass=filename=/d/c.ass"));
        assertTrue(g.endsWith(",format=yuv420p[v]"));
        assertTrue(g, g.contains("hqdn3d=1.5:1.2:3:2.5,unsharp=5:5:0.35:5:5:0.0,scale=1080:"));   // code 28: denoise, gentle sharpen, then enlarge
        String cmd = p.sendcmd();
        if (g.contains("sendcmd")) assertTrue(cmd, cmd.contains("crop@m0 x"));
        assertFalse(cmd.contains("crop@t"));
        // without split parts: the 4.0 graph shape (one camera crop named crop@m)
        FaceTrack.Plan one = plan(backAndForth(16, 0.30, 0.34, 0.3, 0), 16);
        String g1 = FfmpegCommands.trackGraph(new FfmpegCommands.Track("/d/ft_0.cmd", one, 1080), null, null, 1080, "yuv420p");
        assertTrue(g1, g1.startsWith("[0:v]fps=30,sendcmd=f=/d/ft_0.cmd,crop@m="));
        assertFalse(g1.contains("concat"));
    }

    static boolean ffmpeg() { return new File("/usr/bin/ffmpeg").canExecute(); }

    static String run(String... cmd) throws Exception {
        Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = pr.getInputStream().readAllBytes();
        assertTrue(pr.waitFor(300, java.util.concurrent.TimeUnit.SECONDS));
        return new String(out);
    }

    @Test public void segmentedGraphGivesTheSameFramesAsTheOldOverlay() throws Exception {
        assumeTrue(ffmpeg());
        double ax = 0.18, bx = 0.82, h = 0.22;   // full-size panel, so the 4.0 graph can be rebuilt with the same crop
        FaceTrack.Plan p = plan(backAndForth(14, ax, bx, h, 0), 14);
        assertEquals(1, p.splits.size());
        File dir = Files.createTempDirectory("v22g").toFile();
        File cmd = new File(dir, "ft.cmd");
        Files.write(cmd.toPath(), p.sendcmd().getBytes());
        String src = new File(dir, "src.mp4").getAbsolutePath();
        run("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=30:duration=14", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "12", src);
        String gNew = FfmpegCommands.trackGraph(new FfmpegCommands.Track(cmd.getAbsolutePath(), p, 0), null, null, 720, "yuv420p");
        // 4.0's graph (overlay with enable), built with this split's panel and position
        FaceTrack.Split sp = p.splits.get(0);
        String old4 = String.format(Locale.US, "%.4f crop@t x (iw-ow)*%.5f, crop@t y (ih-oh)*%.5f, crop@b x (iw-ow)*%.5f, crop@b y (ih-oh)*%.5f;%n",
                Math.max(0, sp.start - 0.5 / 30), FaceTrackAccess.lxOf(sp.topCx, sp.pw), com.viral.shortsmaker.core.FaceTrack.lyOf(sp.topCy, sp.ph), FaceTrackAccess.lxOf(sp.botCx, sp.pw), com.viral.shortsmaker.core.FaceTrack.lyOf(sp.botCy, sp.ph));
        StringBuilder oc = new StringBuilder();
        double last = -9;
        for (int k = 0; k < p.cx.length; k++) { double l = p.lx(k); if (k == 0 || Math.abs(l - last) > 0.0005) { oc.append(String.format(Locale.US, "%.4f crop@m x (iw-ow)*%.5f;%n", Math.max(0, (k - 0.25) / 30), l)); last = l; } }
        File cmdOld = new File(dir, "old.cmd");
        Files.write(cmdOld.toPath(), (oc + old4).getBytes());
        String pw = String.format(Locale.US, "%.5f", sp.pw), ph = String.format(Locale.US, "%.5f", sp.ph);
        String panel = "w='trunc(iw*" + pw + "/2)*2':h='trunc(ih*" + ph + "/2)*2':x=0:y=0,scale=720:640:flags=lanczos,setsar=1";
        String gOld = "[0:v]fps=30,sendcmd=f=" + cmdOld.getAbsolutePath() + ",split=3[tm][tt][tb];[tm]crop@m=w='min(iw,trunc(ih*9/16/2)*2)':h='trunc(ih/2)*2':x='(iw-ow)*"
                + String.format(Locale.US, "%.5f", p.lx(0)) + "':y=0,scale=720:1280:flags=lanczos,setsar=1[mv];[tt]crop@t=" + panel + "[tv];[tb]crop@b=" + panel + "[bv];"
                + "[tv][bv]vstack=inputs=2[sv];[mv][sv]overlay=0:0:enable='" + p.splitEnable() + "',setsar=1,format=yuv420p[v]";
        String a = run("ffmpeg", "-v", "error", "-i", src, "-filter_complex", gNew, "-map", "[v]", "-f", "framemd5", "-");
        String b = run("ffmpeg", "-v", "error", "-i", src, "-filter_complex", gOld, "-map", "[v]", "-f", "framemd5", "-");
        List<String> la = new ArrayList<>(), lb = new ArrayList<>();
        for (String l : a.split("\n")) if (!l.startsWith("#") && !l.trim().isEmpty()) la.add(l);
        for (String l : b.split("\n")) if (!l.startsWith("#") && !l.trim().isEmpty()) lb.add(l);
        assertEquals(a, lb.size(), la.size());
        int same = 0, samePts = 0;
        for (int i = 0; i < la.size(); i++) {
            String[] x = la.get(i).split(","), y = lb.get(i).split(",");
            if (x[x.length - 1].trim().equals(y[y.length - 1].trim())) same++;
            if (x[1].trim().equals(y[1].trim())) samePts++;
        }
        assertEquals(la.size(), samePts);                 // no frame moved in time (captions stay in sync)
        assertTrue(same + " of " + la.size(), same >= la.size() - 2);   // identical pictures (+-1 frame at each edge of the part)
    }

    // ------------------------------------------------------------------------------------------------ progress
    @Test public void renderProgressIsMonotonicAndHasOneWriter() {
        // his run's shape: 3 shorts, 2 at a time, face analyses one after another
        RenderProgress rp = new RenderProgress(new int[]{0, 1, 2}, new double[]{50, 62, 52}, true, new RenderProgress.Rates(5, 1.5, 1.8), 2, 0);
        double prev = -1, prevT = -1;
        long t = 0;
        Random r = new Random(5);
        int[][] script = {
                {0, 1}, {1, 1}, {0, 2}, {1, 2}, {0, 3}, {1, 2}, {0, 3}, {1, 3}, {2, 1}, {0, 4}, {2, 2}, {1, 4}, {2, 3}, {2, 4}};
        for (int[] ev : script) {
            for (int s = 0; s < 10; s++) {
                t += 1000 + r.nextInt(3000);
                double f = ev[1] == 4 ? 1 : (s + 1) / 10.0 + (r.nextDouble() - 0.5) * 0.15;   // noisy, sometimes backwards
                if (ev[1] == 4) rp.set(ev[0], RenderProgress.DONE, 1, t); else rp.set(ev[0], ev[1], f, t);
                double x = rp.fraction(), y = rp.timeFraction(t);
                assertTrue(x + " < " + prev, x >= prev - 1e-12);
                assertTrue(y + " < " + prevT, y >= prevT - 1e-12);
                prev = x; prevT = y;
                String d = rp.detail();
                assertFalse(d.contains("null"));
            }
        }
        assertEquals(1.0, rp.fraction(), 1e-9);
        // an old phase reported late is ignored (4.0: a fallback download set the step back to "no progress")
        RenderProgress q = new RenderProgress(new int[]{0}, new double[]{50}, true, new RenderProgress.Rates(), 1, 0);
        q.set(0, RenderProgress.ENCODE, 0.5, 1000);
        double before = q.fraction();
        q.set(0, RenderProgress.PREP, 0.1, 2000);
        assertEquals(before, q.fraction(), 1e-12);
    }

    @Test public void detailNamesEveryActiveShortOnce() {
        RenderProgress rp = new RenderProgress(new int[]{0, 1, 2}, new double[]{50, 62, 52}, true, new RenderProgress.Rates(), 2, 0);
        rp.done(0);
        rp.set(1, RenderProgress.ENCODE, 0.40, 1000);
        rp.set(2, RenderProgress.FACES, 0.73, 1000);
        assertEquals("1 of 3 done · #2 rendering 40% · #3 tracking faces 73%", rp.detail());
    }

    @Test public void hisScreenshotMomentIsNoLonger93Percent() {
        // replay of his 4.0 log in render-step time (s): #1 faces 0-78, encode 78-244; #2 prep 0-24, waits, faces 78-170,
        // encode 170-284; #3 prep 244-264, faces 264-304 (screenshot at faces 73% = ~293 s), encode 304-435.
        // Rates as this phone would have learned them from that run.
        RenderProgress rp = new RenderProgress(new int[]{0, 1, 2}, new double[]{50, 62, 52}, true, new RenderProgress.Rates(15, 1.27, 2.5), 2, 0);
        rp.set(0, RenderProgress.PREP, 1, 0); rp.set(0, RenderProgress.FACES, 0, 3000); rp.set(0, RenderProgress.FACES, 1, 78000);
        rp.set(0, RenderProgress.ENCODE, 0, 78000);
        rp.set(1, RenderProgress.PREP, 1, 24000); rp.set(1, RenderProgress.FACES, 0, 78000); rp.set(1, RenderProgress.FACES, 1, 170000);
        rp.set(1, RenderProgress.ENCODE, 0, 170000);
        rp.set(0, RenderProgress.DONE, 1, 244000);
        rp.set(2, RenderProgress.PREP, 0, 244000); rp.set(2, RenderProgress.PREP, 1, 264000); rp.set(2, RenderProgress.FACES, 0, 264000);
        rp.set(1, RenderProgress.DONE, 1, 284000);
        long now = 293000;
        rp.set(2, RenderProgress.FACES, 0.73, now);
        double stepFrac = rp.timeFraction(now);
        double rem = rp.remainingSeconds(now);
        // truth: 435 - 293 = 142 s left of this step
        assertEquals(142, rem, 142 * 0.25);
        // the whole job: 51 s before this step (audio, transcript, AI) + this step's share
        double overall = (51 + stepFrac * 435) / 486.0;
        assertTrue("overall " + overall, overall < 0.80 && overall > 0.62);   // 4.0 showed 93%; the truth was 70%
    }

    @Test public void predictedWallFollowsThePipeline() {
        // one face engine set: analyses in a row; encodes beside them; the last encode alone at the end
        // rates measured from his log the way learnRates measures them: prep (3 + 24 + 20 s) / 3, faces 207.5 s / 164 clip-s,
        // encode 410 s / 164 clip-s
        double w = RenderProgress.predictWall(new double[]{50, 62, 52}, true, new RenderProgress.Rates(15, 1.27, 2.5), 2);
        // his real render step: 435 s
        assertEquals(435, w, 435 * 0.10);
        double noTrack = RenderProgress.predictWall(new double[]{50, 62, 52}, false, new RenderProgress.Rates(5, 1.5, 1.8), 2);
        assertTrue(noTrack < w);
    }

    @Test public void etaDoesNotJumpWhenTheInputFlips() {
        JobManager jm = JobManager.get();
        jm.removeAllListeners();
        jm.start(JobManager.Kind.ANALYZE);
        long t = 1_000_000;
        jm.startedAtForTest(t);
        jm.plan(new double[]{6, 27, 18, 435, 0});
        jm.stepAt(3, 0.1, "Rendering", "", t);
        int lastEta = -1, maxStep = 0;
        // his 4.0 screen: 1 -> 23 -> 4 -> 18 -> 6 min within seconds. Feed the same flips every 0.2 s for 60 s.
        for (int i = 0; i < 300; i++) {
            t += 200;
            jm.stageRemainingAt(3, (i % 2 == 0) ? 60 : 1380, t);
            jm.stepAt(3, 0.1 + i * 0.001, "Rendering", "", t);
            if (i % 5 == 4) {   // the screen redraws ~once a second
                if (lastEta > 0) maxStep = Math.max(maxStep, Math.abs(jm.etaSec - lastEta));
                lastEta = jm.etaSec;
            }
        }
        assertTrue("ETA moved " + maxStep + " s in one second", maxStep <= 90);
        // steady input: it counts down with the clock
        int e0 = jm.etaSec;
        for (int i = 0; i < 50; i++) { t += 200; jm.stageRemainingAt(3, e0 - (i + 1) * 0.2, t); jm.stepAt(3, 0.5, "Rendering", "", t); }
        assertEquals(e0 - 10, jm.etaSec, 3);
        // percent never goes back, even when a bad step reports "no measurable progress"
        int p0 = jm.percent;
        jm.stepAt(3, -1, "Downloading the video", "", t + 100);
        assertTrue(jm.percent >= p0);
        assertTrue(jm.frac[3] >= 0);
        jm.reset();
    }

    @Test public void replanKeepsTheBarWhereItWas() {
        JobManager jm = JobManager.get();
        jm.removeAllListeners();
        jm.start(JobManager.Kind.ANALYZE);
        long t = 2_000_000;
        jm.startedAtForTest(t);
        jm.plan(new double[]{6, 27, 18, 100, 0});
        jm.stepAt(1, 1, "x", "", t + 30_000);
        jm.stepAt(2, 0.5, "x", "", t + 40_000);
        int before = jm.percent;
        jm.replan(3, 435);   // the real clips are longer: the render step is 4x bigger than planned
        assertEquals(before, jm.percent);
        jm.stepAt(3, 0.5, "x", "", t + 50_000);
        assertTrue(jm.percent > before && jm.percent < 99);
        jm.reset();
    }

    // ------------------------------------------------------------------------------------------------ face engines
    @Test public void faceEnginesPerPhone() {
        assertEquals(3, FaceAnalyzer.workerCountFor(8, false, false));   // his OPPO: 8 cores, CPU
        assertEquals(2, FaceAnalyzer.workerCountFor(6, false, false));
        assertEquals(1, FaceAnalyzer.workerCountFor(4, false, false));
        assertEquals(1, FaceAnalyzer.workerCountFor(8, true, false));    // low-RAM phone
        assertEquals(1, FaceAnalyzer.workerCountFor(8, false, true));    // GPU: one queue
    }

    @Test public void bodyModelGateSkipsRepeatsInTheSameShot() {
        // his short #2: 456 body checks on 493 frames of a shot with nobody's face visible
        FaceAnalyzer.PoseGate g = new FaceAnalyzer.PoseGate();
        int runs = 0, reuse = 0;
        for (int i = 0; i < 400; i++) {
            g.frame(i == 200);   // one cut in the middle
            int d = g.decide(i);
            if (d == FaceAnalyzer.PoseGate.RUN) { runs++; g.ran(i, new ArrayList<>()); }   // nobody found
            if (d == FaceAnalyzer.PoseGate.REUSE) reuse++;
        }
        assertTrue("runs " + runs, runs <= 400 / 5 + 2);
        assertEquals(0, reuse);
        // people found: the next frame reuses them, the one after looks again
        FaceAnalyzer.PoseGate h = new FaceAnalyzer.PoseGate();
        List<FaceTrack.Face> heads = new ArrayList<>(Collections.singletonList(new FaceTrack.Face(0.4f, 0.3f, 0.45f, 0.38f, -1f)));
        assertEquals(FaceAnalyzer.PoseGate.RUN, h.decide(0)); h.ran(0, heads);
        h.frame(false); assertEquals(FaceAnalyzer.PoseGate.REUSE, h.decide(1));
        h.frame(false); assertEquals(FaceAnalyzer.PoseGate.RUN, h.decide(2));
        // a cut always looks again
        h.ran(2, new ArrayList<>()); h.frame(true); assertEquals(FaceAnalyzer.PoseGate.RUN, h.decide(3));
    }

    // ------------------------------------------------------------------------------------------------ clip start
    static String res(String name) throws Exception {
        try (java.io.InputStream in = V22Test.class.getClassLoader().getResourceAsStream(name)) { return new String(in.readAllBytes(), "UTF-8"); }
    }

    @Test public void clipsStartPointTwoSecondsBeforeTheFirstWord() throws Exception {
        // real Hindi transcript + the AI's real answer (v7 test files): after the 4.1 pass every short opens 0.2 s before
        // its first word (or right after the previous word when that ended closer than 0.2 s - its tail never opens it)
        Transcript tr = Transcript.fromJson(res("v7_hindi_transcript.json"));
        List<AiClient.Candidate> cands = AiClient.parseForTest(res("v7_hindi_answer.json"), 600, tr);
        List<Project.Clip> out = Pipeline.pickClips(cands, tr, 600, 5);
        assertFalse(out.isEmpty());
        StringBuilder log = new StringBuilder();
        for (Project.Clip c : out) {
            int i = Pipeline.firstWordIndex(tr.words, c.start, c.end);
            assertTrue(i >= 0);
            double prevEnd = i > 0 ? tr.words.get(i - 1).end : 0;
            double before = tr.words.get(i).start - c.start;
            double s = Pipeline.startBefore(tr.words.get(i).start, prevEnd);
            int j = Pipeline.firstWordIndex(tr.words, s, c.end);
            assertEquals("the first word stays the first word", i, j);
            double lead = tr.words.get(i).start - s;
            log.append(String.format(Locale.US, "4.0 lead %.2f s -> %.2f s; ", before, lead));
            if (tr.words.get(i).start - prevEnd >= 0.25) assertEquals(0.2, lead, 0.05);
            else { assertTrue(lead > 0.02 && lead <= 0.2 + 1e-9); assertTrue(s >= Math.min(prevEnd, tr.words.get(i).start - 0.03) - 1e-9); }
        }
        System.out.println("clip starts: " + log);
    }

    /** envelope with silence (level 0.2) and voice (level 2.0) between the given times, 10 ms frames from 'from' */
    static float[] env(double from, double len, double[][] voiced) {
        float[] e = new float[(int) Math.round(len / 0.01)];
        Random r = new Random(1);
        for (int k = 0; k < e.length; k++) {
            double t = from + k * 0.01;
            boolean v = false;
            for (double[] iv : voiced) if (t >= iv[0] && t < iv[1]) v = true;
            e[k] = (float) ((v ? 2.0 : 0.2) + r.nextDouble() * 0.1);
        }
        return e;
    }

    @Test public void startFollowsTheVoiceNotWhispersStretchedWord() {
        // previous sentence ends 10.1 s; 2.5 s of silence; the question really starts at 12.6 s. Whisper stretched the
        // first word back to 10.3 s (the 2-3 s lead he saw). The voice onset puts the start at 12.4 s.
        float[] e = env(9.0, 6.0, new double[][]{{9.0, 10.1}, {12.6, 15.0}});
        double on = Pipeline.voiceOnset(e, 9.0, 10.3, 12.95, 10.1);
        assertEquals(12.6, on, 0.03);
        double s = Pipeline.startBefore(on, 10.1);
        assertEquals(12.4, s, 0.05);
        // no stretch: whisper's time is right -> same 0.2 s lead
        float[] e2 = env(9.0, 6.0, new double[][]{{9.0, 10.1}, {11.0, 15.0}});
        assertEquals(11.0 - 0.2, Pipeline.startBefore(Pipeline.voiceOnset(e2, 9.0, 11.0, 11.4, 10.1), 10.1), 0.05);
        // flat audio (music bed, no contrast): -1, the word time is used
        float[] flat = new float[600]; Arrays.fill(flat, 1f);
        assertEquals(-1, Pipeline.voiceOnset(flat, 9.0, 10.3, 12.9, 10.1), 1e-9);
        // previous word rings into the window: its tail is skipped, the onset is the new word's
        float[] e3 = env(9.0, 6.0, new double[][]{{9.0, 11.25}, {11.6, 15.0}});
        assertEquals(11.6, Pipeline.voiceOnset(e3, 9.0, 11.2, 12.0, 11.2), 0.03);
    }
}
