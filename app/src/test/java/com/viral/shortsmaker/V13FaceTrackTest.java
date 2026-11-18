package com.viral.shortsmaker;

import static org.junit.Assert.*;

import com.viral.shortsmaker.core.FaceTrack;
import com.viral.shortsmaker.core.FfmpegCommands;
import com.viral.shortsmaker.core.Project;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** v13 (2.4): AI face tracking camera planner + its ffmpeg graph. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class V13FaceTrackTest {
    static final double SF = 5;   // samples per second

    /** a face centred at cx (frame fractions), height h, lips moving when talking */
    static FaceTrack.Face face(double cx, double cy, double h, boolean talking, int i, Random r) {
        double w = h * 9 / 16.0 * 1.0;   // in width units for a 16:9 frame: h(height units) * (H/W)
        double m = talking ? 0.05 + 0.05 * Math.abs(Math.sin(i * 1.7)) + 0.02 * r.nextDouble() : 0.02 + 0.002 * r.nextDouble();
        double jx = (r.nextDouble() - 0.5) * 0.006, jy = (r.nextDouble() - 0.5) * 0.006;   // detector noise
        return new FaceTrack.Face((float) (cx - w / 2 + jx), (float) (cy - h / 2 + jy), (float) (cx + w / 2 + jx), (float) (cy + h / 2 + jy), (float) m);
    }

    interface Gen { List<FaceTrack.Face> at(int i, double t, Random r); }

    static List<FaceTrack.Sample> gen(double sec, Gen g, float audio) {
        Random r = new Random(7);
        List<FaceTrack.Sample> s = new ArrayList<>();
        int n = (int) Math.round(sec * SF);
        for (int i = 0; i < n; i++) s.add(new FaceTrack.Sample(g.at(i, i / SF, r), audio, 2f + r.nextFloat()));
        return s;
    }

    static FaceTrack.Plan plan(List<FaceTrack.Sample> s, double sec) { return FaceTrack.plan(s, SF, 1920, 1080, sec, 30); }

    @Test public void verticalVideoIsSkipped() {
        List<FaceTrack.Sample> s = gen(10, (i, t, r) -> list(face(0.5, 0.4, 0.2, true, i, r)), 0.1f);
        FaceTrack.Plan p = FaceTrack.plan(s, SF, 1080, 1920, 10, 30);
        assertFalse(p.usable);
        assertTrue(p.why.contains("vertical"));
    }

    @Test public void noFacesMeansNormalFraming() {
        List<FaceTrack.Sample> s = gen(10, (i, t, r) -> new ArrayList<>(), 0.1f);
        assertFalse(plan(s, 10).usable);
    }

    @Test public void singleSpeakerIsFollowedSteadily() {
        // talking head off-centre at 0.30 with detector noise: window centres on him, no jitter
        List<FaceTrack.Sample> s = gen(20, (i, t, r) -> list(face(0.30, 0.4, 0.3, true, i, r)), 0.1f);
        FaceTrack.Plan p = plan(s, 20);
        assertTrue(p.usable);
        assertEquals(600, p.cx.length);
        for (int k = 0; k < p.cx.length; k++) assertEquals(0.30, p.cx[k], 0.03);
        double[] j = p.jitter(720);
        assertTrue("std " + j[0], j[0] < 0.5);
        assertTrue("max " + j[1], j[1] < 2.0);
    }

    @Test public void smallHeadMovesDoNotMoveTheCamera() {
        // +-1.5 % of the frame width sway: inside the dead zone, the window stays put
        List<FaceTrack.Sample> s = gen(20, (i, t, r) -> list(face(0.5 + 0.015 * Math.sin(t * 2), 0.4, 0.3, true, i, r)), 0.1f);
        FaceTrack.Plan p = plan(s, 20);
        double lo = 9, hi = -9;
        for (double c : p.cx) { lo = Math.min(lo, c); hi = Math.max(hi, c); }
        assertTrue("moved " + (hi - lo), hi - lo < 0.01);
    }

    @Test public void walkingPersonIsFollowedSmoothly() {
        // walks from 0.25 to 0.75 over 10 s: the window follows, with no frame-to-frame jump
        List<FaceTrack.Sample> s = gen(14, (i, t, r) -> list(face(0.25 + 0.5 * Math.min(1, Math.max(0, (t - 2) / 10)), 0.4, 0.25, true, i, r)), 0.1f);
        FaceTrack.Plan p = plan(s, 14);
        assertEquals(0.25, p.cx[10], 0.04);
        assertEquals(0.75, p.cx[p.cx.length - 1], 0.06);
        double[] j = p.jitter(720);
        assertTrue("max step " + j[1], j[1] < 25);   // < 25 px per frame at 720 wide
        assertEquals(0, p.hardCuts);
    }

    @Test public void speakerChangeIsACleanCutAtTheRightMoment() {
        // A (left) talks 0-8 s, B (right) talks 8-16 s; they cannot both fit
        List<FaceTrack.Sample> s = gen(16, (i, t, r) -> list(face(0.2, 0.4, 0.25, t < 8, i, r), face(0.8, 0.4, 0.25, t >= 8, i, r)), 0.1f);
        FaceTrack.Plan p = plan(s, 16);
        assertTrue(p.usable);
        assertEquals(1, p.switches);
        assertTrue(p.splits.isEmpty());
        assertEquals(0.2, p.cx[5 * 30], 0.03);
        assertEquals(0.8, p.cx[12 * 30], 0.03);
        int cut = -1;
        for (int k = 1; k < p.cx.length; k++) if (Math.abs(p.cx[k] - p.cx[k - 1]) > 0.2) { cut = k; break; }
        assertTrue("cut at " + cut / 30.0, cut >= 0 && Math.abs(cut / 30.0 - 8.0) <= 0.6);
        // a clean cut: one frame, no slow drift between the two people
        int mid = 0;
        for (double c : p.cx) if (c > 0.3 && c < 0.7) mid++;
        assertTrue("frames in between " + mid, mid <= 1);
    }

    @Test public void briefOverlapDoesNotFlicker() {
        // B says a 0.4 s "haan" while A talks: no switch (hysteresis)
        List<FaceTrack.Sample> s = gen(12, (i, t, r) -> list(face(0.2, 0.4, 0.25, !(t > 5 && t < 5.4), i, r), face(0.8, 0.4, 0.25, t > 5 && t < 5.4, i, r)), 0.1f);
        FaceTrack.Plan p = plan(s, 12);
        assertEquals(0, p.switches);
        for (double c : p.cx) assertEquals(0.2, c, 0.03);
    }

    @Test public void silenceDoesNotSwitch() {
        // B's lips move (chewing / smiling) but there is no voice: the camera stays on A
        List<FaceTrack.Sample> s = new ArrayList<>();
        Random r = new Random(3);
        for (int i = 0; i < 60; i++) {
            double t = i / SF;
            boolean bMoves = t > 4;
            float audio = t > 4 ? 0.001f : 0.1f;
            s.add(new FaceTrack.Sample(list(face(0.2, 0.4, 0.25, t <= 4, i, r), face(0.8, 0.4, 0.25, bMoves, i, r)), audio, 2));
        }
        FaceTrack.Plan p = plan(s, 12);
        assertEquals(0, p.switches);
    }

    @Test public void fastBackAndForthBecomesSplitScreen() {
        // switching every 2 s for 14 s: a split screen, left person on top
        List<FaceTrack.Sample> s = gen(14, (i, t, r) -> {
            boolean a = ((int) (t / 2)) % 2 == 0;
            return list(face(0.18, 0.4, 0.22, a, i, r), face(0.82, 0.42, 0.22, !a, i, r));
        }, 0.1f);
        FaceTrack.Plan p = plan(s, 14);
        assertEquals(1, p.splits.size());
        FaceTrack.Split sp = p.splits.get(0);
        assertTrue(sp.end - sp.start > 10);
        // each face sits inside its panel (the panel itself is clamped to the frame edge)
        assertTrue(Math.abs(sp.topCx - 0.18) < p.panelW / 2 - 0.05);
        assertTrue(Math.abs(sp.botCx - 0.82) < p.panelW / 2 - 0.05);
        assertTrue(sp.topCx < sp.botCx);
        assertTrue(p.panelW > 0 && p.panelW <= 0.6 && p.panelH > 0 && p.panelH <= 1.0);
        assertNotNull(p.splitEnable());
        assertTrue(p.hasSplitSegments());   // v22: split panels are baked into the segmented graph, not sent by sendcmd
    }

    @Test public void peopleWhoFitTogetherAreFramedTogether() {
        // two faces 0.12 apart fit inside the 9:16 window (0.316 of the width): framed together, no switching
        List<FaceTrack.Sample> s = gen(12, (i, t, r) -> list(face(0.44, 0.4, 0.15, t < 6, i, r), face(0.56, 0.4, 0.15, t >= 6, i, r)), 0.1f);
        FaceTrack.Plan p = plan(s, 12);
        for (double c : p.cx) assertEquals(0.5, c, 0.02);
        assertEquals(0, p.switches);
    }

    @Test public void lostFaceHoldsThenEasesToCentre() {
        // face at 0.25 until 6 s, then gone. v16 (2.7): the last good framing is held (2.4-2.6 drifted to the centre)
        List<FaceTrack.Sample> s = gen(14, (i, t, r) -> t < 6 ? list(face(0.25, 0.4, 0.25, true, i, r)) : new ArrayList<>(), 0.1f);
        FaceTrack.Plan p = plan(s, 14);
        assertEquals(0.25, p.cx[(int) (6.5 * 30)], 0.02);      // held right after
        assertEquals(0.25, p.cx[p.cx.length - 1], 0.02);       // and still held (no jump to the centre)
        double[] j = p.jitter(720);
        assertTrue(j[1] < 40);                                   // eased, not snapped
    }

    @Test public void shotCutSnapsImmediately() {
        // the source cuts from a close-up at 0.3 to one at 0.7 at 5 s: the window snaps with the cut
        List<FaceTrack.Sample> s = new ArrayList<>();
        Random r = new Random(1);
        for (int i = 0; i < 50; i++) {
            double t = i / SF;
            s.add(new FaceTrack.Sample(list(face(t < 5 ? 0.3 : 0.7, 0.4, 0.35, true, i, r)), 0.1f, i == 25 ? 60f : 2f));
        }
        FaceTrack.Plan p = plan(s, 10);
        assertEquals(0.3, p.cx[149], 0.03);
        assertEquals(0.7, p.cx[150], 0.03);
        assertEquals(1, p.hardCuts);
    }

    @Test public void windowStaysInsideTheFrame() {
        List<FaceTrack.Sample> s = gen(10, (i, t, r) -> list(face(0.03, 0.4, 0.2, true, i, r)), 0.1f);
        FaceTrack.Plan p = plan(s, 10);
        for (double c : p.cx) assertTrue(c >= p.cropW / 2 - 1e-9 && c <= 1 - p.cropW / 2 + 1e-9);
        assertEquals(0, p.lx(0), 1e-9);
    }

    @Test public void samplesRoundTrip() {
        List<FaceTrack.Sample> s = gen(3, (i, t, r) -> list(face(0.3, 0.4, 0.2, true, i, r), new FaceTrack.Face(0.6f, 0.2f, 0.7f, 0.4f, -1f)), 0.05f);
        List<FaceTrack.Sample> b = FaceTrack.samplesFromText(FaceTrack.samplesToText(s));
        assertEquals(s.size(), b.size());
        assertEquals(2, b.get(4).faces.size());
        assertEquals(s.get(4).faces.get(0).x0, b.get(4).faces.get(0).x0, 1e-4);
        assertEquals(-1f, b.get(4).faces.get(1).mouth, 1e-6);
    }

    @Test public void trackGraphKeepsTimingAndCaptions() {
        List<FaceTrack.Sample> s = gen(10, (i, t, r) -> list(face(0.3, 0.4, 0.3, true, i, r)), 0.1f);
        FaceTrack.Plan p = plan(s, 10);
        FfmpegCommands.Track tr = new FfmpegCommands.Track("/data/x/ft_0.cmd", p);
        String g = FfmpegCommands.trackGraph(tr, "/data/x/c.ass", "/data/fonts", 720, "yuv420p");
        assertTrue(g, g.startsWith("[0:v]fps=30,sendcmd=f=/data/x/ft_0.cmd,crop@m="));
        assertTrue(g.contains("scale=720:1280"));
        assertTrue(g.contains("ass=filename=/data/x/c.ass"));
        assertTrue(g.endsWith(",format=yuv420p[v]"));
        assertFalse(g.contains("setpts"));   // timing untouched
        String[] withT = FfmpegCommands.renderClip("in.mp4", null, 12.5, 10, FfmpegCommands.Frame.BLUR, null, null, "o.mp4", 720, FfmpegCommands.Encoder.X264, 0, tr);
        String[] noT = FfmpegCommands.renderClip("in.mp4", null, 12.5, 10, FfmpegCommands.Frame.BLUR, null, null, "o.mp4", 720, FfmpegCommands.Encoder.X264, 0);
        assertEquals(withT.length, noT.length);
        for (int i = 0; i < withT.length; i++) if (!noT[i].contains("[0:v]")) assertEquals(noT[i], withT[i]);   // same seek, length, encoder, audio
        String off = String.join(" ", noT);
        assertTrue(off.contains("boxblur"));       // OFF = the 2.3 blur framing exactly
        assertFalse(off.contains("sendcmd"));
        // sendcmd: first command at 0, one line per change, all within the clip
        String cmd = p.sendcmd();
        assertTrue(cmd.startsWith("0.0000 crop@m x (iw-ow)*"));
        for (String line : cmd.split("\n")) assertTrue(Double.parseDouble(line.split(" ")[0]) < 10);
    }

    @Test public void projectRemembersFaceTrackingAndOldProjectsStayOff() throws Exception {
        Project pr = new Project();
        pr.faceTrack = true;
        assertTrue(Project.fromJson(pr.toJson()).faceTrack);
        org.json.JSONObject o = pr.toJson();
        o.remove("faceTrack");
        assertFalse(Project.fromJson(o).faceTrack);   // a 2.3 project re-styled in 2.4 keeps its 2.3 framing
    }

    static List<FaceTrack.Face> list(FaceTrack.Face... f) { List<FaceTrack.Face> l = new ArrayList<>(); for (FaceTrack.Face x : f) l.add(x); return l; }
}
