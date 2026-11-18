package com.viral.shortsmaker;

import static org.junit.Assert.*;

import com.viral.shortsmaker.core.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** v16 (2.7): quality (1080p source, partial DASH fetch, lanczos), speed, and the advanced camera. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class V16Test {
    static final double SF = 5;

    // ------------------------------------------------------------------ DASH index (sidx) -> clip byte range
    static void box(ByteArrayOutputStream o, String type, byte[] body) {
        int n = 8 + body.length;
        o.write(n >>> 24); o.write(n >>> 16); o.write(n >>> 8); o.write(n);
        o.write(type.charAt(0)); o.write(type.charAt(1)); o.write(type.charAt(2)); o.write(type.charAt(3));
        o.write(body, 0, body.length);
    }
    static void u32(ByteArrayOutputStream o, long v) { o.write((int) (v >>> 24)); o.write((int) (v >>> 16)); o.write((int) (v >>> 8)); o.write((int) v); }

    /** ftyp + moov + sidx (10 fragments of 5 s, 1000 bytes each, timescale 1000, ept 0) */
    static byte[] dashHead() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        box(o, "ftyp", new byte[]{'d', 'a', 's', 'h', 0, 0, 0, 0});
        box(o, "moov", new byte[100]);
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        u32(s, 0); u32(s, 1); u32(s, 1000); u32(s, 0); u32(s, 0);   // version/flags, ref id, timescale, ept, first offset
        s.write(0); s.write(0); s.write(0); s.write(10);             // reserved, count
        for (int i = 0; i < 10; i++) { u32(s, 1000); u32(s, 5000); u32(s, 0x90000000L); }
        box(o, "sidx", s.toByteArray());
        return o.toByteArray();
    }

    @Test public void sidxGivesTheClipsByteRange() throws Exception {
        byte[] h = dashHead();
        DashRange.Index ix = DashRange.parse(h, h.length);
        assertNotNull(ix);
        assertEquals(16 + 108, ix.initEnd);                 // ftyp(16) + moov(108)
        assertEquals(h.length, ix.firstByte);               // fragments right after the index
        DashRange.Pick p = DashRange.pick(ix, 12.0, 21.0);  // fragments 2 (10-15 s) .. 4 (20-25 s)
        assertEquals(h.length + 2000, p.from);
        assertEquals(h.length + 5000 - 1, p.to);
        assertEquals(10.0, p.startSec, 1e-9);
        assertEquals(25.0, p.endSec, 1e-9);
        assertEquals(3, p.fragments);
    }

    @Test public void truncatedIndexAsksForMoreAndNonDashIsRefused() throws Exception {
        byte[] h = dashHead();
        try { DashRange.parse(h, h.length - 20); fail("should need more bytes"); } catch (DashRange.NeedMore m) { assertTrue(m.bytes >= h.length); }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        box(o, "ftyp", new byte[8]); box(o, "moov", new byte[50]); box(o, "mdat", new byte[50]);   // plain MP4, no index
        byte[] plain = o.toByteArray();
        assertNull(DashRange.parse(plain, plain.length));
        byte[] webm = new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0, 0, 0, 0, 0};
        assertNull(DashRange.parse(webm, webm.length));
    }

    // ------------------------------------------------------------------ source quality
    static JSONObject fmt(String id, int w, int h, String vc, String ac, String ext, double abr) throws Exception {
        JSONObject f = new JSONObject();
        f.put("format_id", id); f.put("url", "https://x/" + id); f.put("protocol", "https"); f.put("width", w); f.put("height", h);
        f.put("vcodec", vc); f.put("acodec", ac); f.put("ext", ext); f.put("fps", 30); if (abr > 0) f.put("abr", abr);
        return f;
    }

    @Test public void longVideosNowUse1080pAndASmallAnalysisCopy() throws Exception {
        assertEquals(1080, YtEngine.maxHeight(0, 3600));   // 2.6: 480 for videos over 25 min = blurry tracked crops
        JSONObject info = new JSONObject();
        JSONArray a = new JSONArray();
        a.put(fmt("18", 640, 360, "avc1.42001E", "mp4a.40.2", "mp4", 0));
        a.put(fmt("134", 640, 360, "avc1.4d401e", "none", "mp4", 0));
        a.put(fmt("135", 854, 480, "avc1.4d401f", "none", "mp4", 0));
        a.put(fmt("137", 1920, 1080, "avc1.640028", "none", "mp4", 0));
        a.put(fmt("248", 1920, 1080, "vp9", "none", "webm", 0));
        a.put(fmt("140", 0, 0, "none", "mp4a.40.2", "m4a", 129));
        a.put(fmt("139", 0, 0, "none", "mp4a.40.5", "m4a", 49));
        info.put("formats", a);
        String[] s = YtEngine.pickStreams(info, YtEngine.maxHeight(0, 3600));
        assertEquals("https://x/137", s[0]);      // 1080p H.264 for the render
        assertEquals("https://x/140", s[1]);      // 128 kbps audio
        assertEquals("https://x/134", s[2]);      // 360p for face analysis
        assertTrue(s[3].startsWith("1920x1080 avc1"));
    }

    @Test public void trackedCropUsesLanczosAndSharpensOnlyWhenEnlarging() throws Exception {
        List<FaceTrack.Sample> s = V13FaceTrackTest.gen(6, (i, t, r) -> V13FaceTrackTest.list(V13FaceTrackTest.face(0.3, 0.4, 0.3, true, i, r)), 0.1f);
        FaceTrack.Plan p = FaceTrack.plan(s, SF, 1920, 1080, 6, 30);
        String up = FfmpegCommands.trackGraph(new FfmpegCommands.Track("/t/c.cmd", p, 1080), null, null, 1080, "yuv420p");
        assertTrue(up.contains("flags=lanczos"));
        assertTrue(up.contains("unsharp"));          // 1080 tall crop -> 1920 tall output: enlarged, light sharpening
        String down = FfmpegCommands.trackGraph(new FfmpegCommands.Track("/t/c.cmd", p, 1440), null, null, 720, "yuv420p");
        assertTrue(down.contains("flags=lanczos"));
        assertFalse(down.contains("unsharp"));       // 1440 -> 1280: no sharpening
        String fill = String.join(" ", FfmpegCommands.renderClip("in.mp4", null, 0, 5, FfmpegCommands.Frame.FILL, null, null, "o.mp4", 720, FfmpegCommands.Encoder.X264, 0));
        assertTrue(fill.contains("flags=lanczos"));   // tracking off (crop) is as sharp as tracking on
        assertFalse(up.contains("bilinear"));
    }

    // ------------------------------------------------------------------ camera
    static int shakes(FaceTrack.Plan p) {
        int sh = 0, last = 0, at = -99;
        for (int k = 1; k < p.cx.length; k++) {
            double d = p.cx[k] - p.cx[k - 1];
            if (p.cutAt != null && p.cutAt[k]) { last = 0; continue; }
            if (Math.abs(d) < 1e-7) continue;
            int dir = d > 0 ? 1 : -1;
            if (last != 0 && dir != last && k - at < 12) sh++;
            last = dir; at = k;
        }
        return sh;
    }

    @Test public void lookAheadStartsTheMoveBeforeThePersonMoves() {
        // one person walks from 0.3 to 0.62 between 5.0 and 5.6 s
        List<FaceTrack.Sample> s = V13FaceTrackTest.gen(10, (i, t, r) -> V13FaceTrackTest.list(V13FaceTrackTest.face(t < 5 ? 0.3 : t < 5.6 ? 0.3 + 0.32 * (t - 5) / 0.6 : 0.62, 0.4, 0.3, true, i, r)), 0.1f);
        FaceTrack.Plan p = FaceTrack.plan(s, SF, 1920, 1080, 10, 30);
        assertEquals(0.3, p.cx[(int) (3 * 30)], 0.02);
        assertTrue("camera already moving at 5.0 s: " + p.cx[150], p.cx[150] > p.cx[90] + 0.005);   // look-ahead
        assertEquals(0.62, p.cx[(int) (7.5 * 30)], 0.03);
        for (double c : p.cx) assertTrue(c <= 0.62 + 0.03);    // no overshoot
        assertEquals(0, shakes(p));
        assertTrue(p.steadiness().contains("0 shake"));
    }

    @Test public void smallSwayKeepsTheCameraPerfectlyStill() {
        List<FaceTrack.Sample> s = V13FaceTrackTest.gen(12, (i, t, r) -> V13FaceTrackTest.list(V13FaceTrackTest.face(0.4 + 0.02 * Math.sin(t * 2), 0.4, 0.3, true, i, r)), 0.1f);
        FaceTrack.Plan p = FaceTrack.plan(s, SF, 1920, 1080, 12, 30);
        for (int k = 1; k < p.cx.length; k++) assertEquals(p.cx[0], p.cx[k], 1e-9);
        assertTrue(p.steadiness().startsWith("camera still 100%"));
    }

    @Test public void voiceTimingPicksTheRealSpeaker() {
        // two far-apart people, both move their lips the same amount; only B's lips follow the voice level
        Random r = new Random(5);
        List<FaceTrack.Sample> s = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            double loud = (i % 4 < 2) ? 1.0 : 0.15;                       // voice rhythm
            double mB = loud > 0.5 ? 0.10 : 0.02;                        // B: open when loud
            double mA = ((i + 1) % 4 < 2) ? 0.10 : 0.02;                 // A: same amount, out of step
            List<FaceTrack.Face> f = new ArrayList<>();
            f.add(new FaceTrack.Face(0.10f, 0.25f, 0.24f, 0.55f, (float) (mA + 0.002 * r.nextDouble())));
            f.add(new FaceTrack.Face(0.76f, 0.25f, 0.90f, 0.55f, (float) (mB + 0.002 * r.nextDouble())));
            s.add(new FaceTrack.Sample(f, (float) loud, 2f));
        }
        FaceTrack.Plan p = FaceTrack.plan(s, SF, 1920, 1080, 12, 30);
        assertTrue(p.usable);
        if (p.splits.isEmpty()) assertEquals(0.83, p.cx[p.cx.length / 2], 0.05);   // on B
        else for (FaceTrack.Split sp : p.splits) assertTrue(sp.end > sp.start);
    }

    @Test public void twoPeopleCloseTogetherAreFramedTogetherWithoutFlicker() {
        // two people whose outer edges span 0.27-0.29 of the width (window 0.316): one two-shot, never switching
        List<FaceTrack.Sample> s = V13FaceTrackTest.gen(12, (i, t, r) -> {
            double gap = 0.135 + 0.01 * Math.sin(t * 3);
            return V13FaceTrackTest.list(V13FaceTrackTest.face(0.5 - gap / 2 - 0.0, 0.4, 0.12, i % 2 == 0, i, r), V13FaceTrackTest.face(0.5 + gap / 2, 0.4, 0.12, i % 2 == 1, i, r));
        }, 0.1f);
        FaceTrack.Plan p = FaceTrack.plan(s, SF, 1920, 1080, 12, 30);
        assertEquals(0, p.switches);
        assertTrue(p.splits.isEmpty());
        for (double c : p.cx) assertEquals(0.5, c, 0.03);
    }

    @Test public void shotThatOpensWithoutAFaceIsFramedWhereTheFaceWillBe() {
        // the source cuts at 4 s to a shot whose person (at 0.75) is first detected at 5 s
        List<FaceTrack.Sample> s = new ArrayList<>();
        Random r = new Random(2);
        for (int i = 0; i < 50; i++) {
            double t = i / SF;
            List<FaceTrack.Face> f = t < 4 ? V13FaceTrackTest.list(V13FaceTrackTest.face(0.3, 0.4, 0.35, true, i, r)) : t < 5 ? new ArrayList<>() : V13FaceTrackTest.list(V13FaceTrackTest.face(0.75, 0.4, 0.35, true, i, r));
            s.add(new FaceTrack.Sample(f, 0.1f, i == 20 ? 60f : 2f));
        }
        FaceTrack.Plan p = FaceTrack.plan(s, SF, 1920, 1080, 10, 30);
        assertEquals(0.75, p.cx[4 * 30 + 3], 0.03);   // right after the cut: already on the person (not the centre)
        assertEquals(0.3, p.cx[4 * 30 - 3], 0.03);
    }

    @Test public void splitPanelsPutTheEyesOnTheUpperThird() {
        // two far-apart people talking back and forth -> split screen; eyes ~1/3 from the panel top
        List<FaceTrack.Sample> s = new ArrayList<>();
        Random r = new Random(4);
        for (int i = 0; i < 80; i++) {
            boolean aTalks = (i / 10) % 2 == 0;
            s.add(new FaceTrack.Sample(V13FaceTrackTest.list(V13FaceTrackTest.face(0.18, 0.42, 0.22, aTalks, i, r), V13FaceTrackTest.face(0.82, 0.42, 0.22, !aTalks, i, r)), 0.3f, 2f));
        }
        FaceTrack.Plan p = FaceTrack.plan(s, SF, 1920, 1080, 16, 30);
        for (FaceTrack.Split sp : p.splits) {
            double top = sp.topCy - p.panelH / 2, eyes = 0.42 - 0.1 * 0.22;
            double rel = (eyes - top) / p.panelH;
            if (sp.topCy - p.panelH / 2 > 0.001 && sp.topCy + p.panelH / 2 < 0.999) assertEquals(1 / 3.0, rel, 0.06);
        }
    }
}
