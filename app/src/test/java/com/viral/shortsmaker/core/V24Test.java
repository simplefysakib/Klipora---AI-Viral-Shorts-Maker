package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.List;

/** v24: sharper source for cropped shorts, WebM clip fetch, hardware decoding, audio fade-in, split panel headroom */
@RunWith(RobolectricTestRunner.class)
public class V24Test {

    static JSONObject fmt(String id, String vc, String ac, int h, String ext, double fps) throws Exception {
        return new JSONObject().put("format_id", id).put("url", "https://x/" + id).put("protocol", "https").put("vcodec", vc).put("acodec", ac)
                .put("height", h).put("width", h * 16 / 9).put("ext", ext).put("fps", fps).put("abr", ac.equals("none") ? 0 : 128);
    }

    static JSONObject info() throws Exception {
        JSONArray a = new JSONArray();
        a.put(fmt("137", "avc1.640028", "none", 1080, "mp4", 30));
        a.put(fmt("248", "vp09.00.40.08", "none", 1080, "webm", 30));
        a.put(fmt("271", "vp09.00.50.08", "none", 1440, "webm", 30));
        a.put(fmt("400", "av01.0.12M.08", "none", 1440, "mp4", 30));
        a.put(fmt("313", "vp09.00.51.08", "none", 2160, "webm", 30));
        a.put(fmt("315", "vp09.00.51.08", "none", 2160, "webm", 60));
        a.put(fmt("134", "avc1.4d401e", "none", 360, "mp4", 30));
        a.put(fmt("140", "none", "mp4a.40.2", 0, "m4a", 0));
        return new JSONObject().put("formats", a);
    }

    @Test public void normalPickUnchanged() throws Exception {
        String[] s = YtEngine.pickStreams(info(), 1080);
        assertTrue(s[3], s[3].contains("1080") && s[3].contains("avc1"));
        // a cap of 1440 without a hardware-decoder answer still never takes a stream above 1080p
        String[] s2 = YtEngine.pickStreams(info(), 1440);
        assertTrue(s2[3], s2[3].contains("x1080 "));
    }

    @Test public void hiPickNeedsHardwareDecoder() throws Exception {
        // phone with a VP9 hardware decoder up to 1440p only
        YtEngine.HiOk vp9to1440 = (f, h, fps) -> "vp9".equals(f) && h <= 1440 && fps <= 31;
        String[] s = YtEngine.pickStreams(info(), 2160, vp9to1440);
        assertTrue(s[3], s[3].startsWith("2560x1440 vp09"));
        assertEquals("https://x/140", s[1]);
        assertEquals("https://x/134", s[2]);   // the small analysis copy stays H.264 360p
        // AV1-only phone
        String[] a = YtEngine.pickStreams(info(), 1440, (f, h, fps) -> "av1".equals(f));
        assertTrue(a[3], a[3].startsWith("2560x1440 av01"));
        // 4K phone: 30 fps 4K, never the 60 fps one
        String[] k = YtEngine.pickStreams(info(), 2160, (f, h, fps) -> "vp9".equals(f) && fps <= 31);
        assertTrue(k[3], k[3].contains("itag 313"));
        // no hardware decoder: 1080p as before
        String[] n = YtEngine.pickStreams(info(), 2160, (f, h, fps) -> false);
        assertTrue(n[3], n[3].contains("x1080 avc1"));
    }

    @Test public void hiCapOnlyForNarrowCrops() {
        Project pr = new Project();
        pr.sourceType = "yt"; pr.url = "https://youtu.be/x"; pr.aspect = "PORTRAIT"; pr.frame = "BLUR"; pr.faceTrack = true;
        assertEquals(1440, Pipeline.hiSourceCap(pr, 0, false, false));
        assertEquals(2160, Pipeline.hiSourceCap(pr, 1, true, true));
        assertEquals(1440, Pipeline.hiSourceCap(pr, 1, true, false));   // standard speed: 1440p
        assertEquals(0, Pipeline.hiSourceCap(pr, 3, true, true));       // 480p data saver
        pr.faceTrack = false;
        assertEquals(0, Pipeline.hiSourceCap(pr, 1, true, true));       // blur / fit show the whole 16:9 frame
        pr.frame = "FILL";
        assertEquals(1440, Pipeline.hiSourceCap(pr, 0, true, true));
        pr.aspect = "SQUARE";
        assertEquals(0, Pipeline.hiSourceCap(pr, 1, true, true));
        pr.aspect = "PORTRAIT"; pr.sourceType = "local";
        assertEquals(0, Pipeline.hiSourceCap(pr, 1, true, true));
    }

    @Test public void renderUsesDecoderAndFadesAudioIn() {
        String[] a = FfmpegCommands.renderClip("/d/seg_0.mp4", null, 0, 30, FfmpegCommands.Frame.FILL, "/d/c.ass", "/d/f", "/d/o.mp4", 1080,
                FfmpegCommands.Encoder.HW, 4, null, false, "vp9_mediacodec", 1080);
        List<String> l = Arrays.asList(a);
        int dec = l.indexOf("vp9_mediacodec"), in = l.indexOf("-i");
        assertTrue(dec > 0 && dec < in && "-c:v".equals(l.get(dec - 1)));
        assertEquals(FfmpegCommands.AUDIO_IN, l.get(l.indexOf("-af") + 1));
        String g = l.get(l.indexOf("-filter_complex") + 1);
        assertTrue(g, g.contains("unsharp=") && g.contains("scale=1080:1920:flags=lanczos"));   // 608x1080 crop enlarged: sharpened
        String[] b = FfmpegCommands.renderClip("/d/seg_0.mp4", null, 0, 30, FfmpegCommands.Frame.FILL, null, "/d/f", "/d/o.mp4", 1080,
                FfmpegCommands.Encoder.HW, 4, null, false, null, 2160);
        String g2 = Arrays.asList(b).get(Arrays.asList(b).indexOf("-filter_complex") + 1);
        assertFalse(g2, g2.contains("unsharp"));   // 4K: the crop is already larger than the output
        assertFalse(Arrays.asList(b).contains("vp9_mediacodec"));
        String[] c = FfmpegCommands.burnCaptions("/d/raw.mp4", "/d/c.ass", "/d/f", "/d/o.mp4", FfmpegCommands.Encoder.HW, 1080, 4, "h264_mediacodec");
        assertEquals("h264_mediacodec", c[c.length > 0 ? Arrays.asList(c).indexOf("-c:v") + 1 : 0]);
        assertTrue(Arrays.asList(c).indexOf("-c:v") < Arrays.asList(c).indexOf("-i"));
    }

    @Test public void splitPanelsZoomLessWithHeadroom() {
        assertTrue(FaceTrack.PH_MIN >= 0.40);
        // face centre at 0.4 of the source height, panel 0.5 tall: the face sits at 42% of the panel (room above the head)
        double y = FaceTrack.lyOf(0.4, 0.5);
        double top = y * (1 - 0.5);
        assertEquals(0.42, (0.4 - top) / 0.5, 1e-9);
        assertEquals(0, FaceTrack.lyOf(0.1, 0.5), 1e-9);
        assertEquals(1, FaceTrack.lyOf(0.95, 0.5), 1e-9);
    }

    /** real FFmpeg (x264): Fill with sharpening + audio fade, also for a source without audio. -Pv23Src=/path/1080p.mp4 */
    @Test public void realFfmpegRendersV24() throws Exception {
        String src = System.getProperty("v23Src", "");
        org.junit.Assume.assumeTrue(!src.isEmpty() && new File(src).exists());
        File dir = new File(System.getProperty("java.io.tmpdir"), "v24test");
        dir.mkdirs();
        File noA = new File(dir, "noaudio.mp4");
        assertEquals(0, new ProcessBuilder("ffmpeg", "-nostdin", "-y", "-loglevel", "error", "-i", src, "-t", "6", "-an", "-c:v", "copy", noA.getAbsolutePath()).redirectErrorStream(true).start().waitFor());
        for (String in : new String[]{src, noA.getAbsolutePath()}) {
            for (FfmpegCommands.Frame fr : FfmpegCommands.Frame.values()) {
                File o = new File(dir, "o_" + fr + "_" + new File(in).getName());
                String[] a = FfmpegCommands.renderClip(in, null, 1, 4, fr, null, null, o.getAbsolutePath(), 1080, FfmpegCommands.Encoder.X264, 2, null, false, null, 1080);
                List<String> cmd = new java.util.ArrayList<>(); cmd.add("ffmpeg"); cmd.add("-nostdin"); cmd.addAll(Arrays.asList(a));
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(new File(dir, "log_" + fr + ".txt")).start();
                assertEquals(fr + " ffmpeg exit " + in, 0, p.waitFor());
                assertTrue(o.length() > 50_000);
            }
        }
    }

    /** box sample: the app's planner + render graph on a real analysis (skipped unless -Pv24Samples=<samples txt>) */
    @Test public void sampleTrackGraph() throws Exception {
        String s = System.getProperty("v24Samples", "");
        org.junit.Assume.assumeTrue(!s.isEmpty() && new File(s).exists());
        String[] meta = System.getProperty("v24Meta", "640,360,12,2160").split(",");
        List<FaceTrack.Sample> sm = FaceTrack.samplesFromText(new String(java.nio.file.Files.readAllBytes(new File(s).toPath())));
        FaceTrack.Plan p = FaceTrack.plan(sm, FaceAnalyzer.FPS, Integer.parseInt(meta[0]), Integer.parseInt(meta[1]), Double.parseDouble(meta[2]), 30, 9 / 16.0);
        assertTrue(p.why, p.usable);
        File cmd = new File(new File(s).getParentFile(), "sample.cmd");
        try (FileOutputStream fo = new FileOutputStream(cmd)) { fo.write(p.sendcmd().getBytes("UTF-8")); }
        String g = FfmpegCommands.trackGraph(new FfmpegCommands.Track(cmd.getAbsolutePath(), p, Integer.parseInt(meta[3])), null, null, 1080, "yuv420p");
        try (FileOutputStream fo = new FileOutputStream(new File(cmd.getParentFile(), "sample.graph"))) { fo.write(g.getBytes("UTF-8")); }
        try (FileOutputStream fo = new FileOutputStream(new File(cmd.getParentFile(), "sample.plan"))) { fo.write((p.summary() + "\n" + p.steadiness() + "\n").getBytes("UTF-8")); }
    }

    /** a real VP9 DASH WebM (box: ffmpeg -f webm -dash 1 -cues_to_front 1) cut by its cues, like the clip fetch does */
    @Test public void webmCuesRangeCut() throws Exception {
        String src = System.getProperty("v24Webm", "");
        org.junit.Assume.assumeTrue("set -Pv24Webm", !src.isEmpty() && new File(src).exists());
        File f = new File(src);
        byte[] head;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) { head = new byte[(int) Math.min(f.length(), 1 << 20)]; r.readFully(head); }
        DashRange.Webm wm = new DashRange.Webm();
        DashRange.Index ix = DashRange.parseWebm(head, head.length, f.length(), wm);
        assertNotNull(ix);
        assertTrue(wm.cuesPos > 0);
        DashRange.Pick pk = DashRange.pick(ix, 12.0, 20.0);
        assertNotNull(pk);
        assertTrue(pk.startSec <= 12.0 && pk.endSec >= 20.0);
        byte[] h = DashRange.patchWebmHeader(head, (int) ix.initEnd, wm);
        File out = new File(System.getProperty("v24Out", "/tmp/v24_cut.webm"));
        try (FileOutputStream fo = new FileOutputStream(out); RandomAccessFile r = new RandomAccessFile(f, "r")) {
            fo.write(h);
            r.seek(pk.from);
            byte[] buf = new byte[(int) (pk.to - pk.from + 1)];
            r.readFully(buf);
            fo.write(buf);
        }
        try (FileOutputStream fo = new FileOutputStream(out.getPath() + ".txt")) { fo.write(String.format(java.util.Locale.US, "%.3f %.3f", pk.startSec, pk.endSec).getBytes()); }
        // the patched header must re-parse as an index-less stream (no cues left)
        DashRange.Webm wm2 = new DashRange.Webm();
        try { assertNull(DashRange.parseWebm(h, h.length, -1, wm2)); } catch (DashRange.NeedMore ok) { /* header only: fine */ }
    }
}
