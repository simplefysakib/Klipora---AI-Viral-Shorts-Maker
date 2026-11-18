package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v26 (code 26): output quality on every phone. Encoder plans for the encoder lists of typical phones (Qualcomm,
 * MediaTek old + new, Exynos / Tensor, Kirin, Unisoc, software-only, landscape-only encoders), the exact FFmpeg
 * arguments each plan produces, the step-down ladder, failure classification and the quality guard thresholds.
 */
public class V26Test {

    @After public void reset() { FfmpegCommands.planSource = null; FfmpegCommands.hwBoost = 1.0; }

    // ---------------------------------------------------------------- simulated phones (MediaCodecList as such phones list it)

    static HwEncode.Enc enc(String name, boolean hw, int maxW, int maxH, boolean anyOrientation) {
        HwEncode.Enc e = new HwEncode.Enc(name, hw);
        e.size = (w, h) -> anyOrientation ? Math.max(w, h) <= Math.max(maxW, maxH) && Math.min(w, h) <= Math.min(maxW, maxH)
                : w <= maxW && h <= maxH;
        return e;
    }

    /** Snapdragon 6xx/7xx/8xx (c2.qti): CBR + VBR, High, NV12 + flexible, QP bounds on Android 12+, up to 100 Mbps */
    static List<HwEncode.Enc> qualcomm() {
        HwEncode.Enc q = enc("c2.qti.avc.encoder", true, 4096, 2176, true);
        q.cbr = q.vbr = q.cq = true; q.high = q.main = true; q.nv12 = q.flexible = true; q.qpBounds = true; q.maxKbps = 100000;
        HwEncode.Enc sw = enc("c2.android.avc.encoder", false, 2048, 2048, true); sw.vbr = sw.cbr = true; sw.flexible = true; sw.main = true;
        return Arrays.asList(q, sw);
    }

    /** MediaTek Dimensity / Helio G (Moto G-series): c2.mtk.avc.encoder, VBR + CBR, High, flexible, no QP bounds, 40 Mbps */
    static List<HwEncode.Enc> mediatekC2() {
        HwEncode.Enc m = enc("c2.mtk.avc.encoder", true, 1920, 1920, true);
        m.cbr = m.vbr = true; m.high = m.main = true; m.flexible = true; m.maxKbps = 40000;
        HwEncode.Enc sec = enc("c2.mtk.avc.encoder.secure", true, 1920, 1920, true); sec.cbr = sec.vbr = true; sec.high = true; sec.flexible = true;
        HwEncode.Enc sw = enc("c2.android.avc.encoder", false, 2048, 2048, true); sw.vbr = true; sw.flexible = true;
        return Arrays.asList(sec, m, sw);
    }

    /** older MediaTek (OMX.MTK, Android 7-10): VBR only, Main profile listed, NV12, 14 Mbps ceiling */
    static List<HwEncode.Enc> mediatekOmx() {
        HwEncode.Enc m = enc("OMX.MTK.VIDEO.ENCODER.AVC", true, 1920, 1920, true);
        m.vbr = true; m.main = true; m.nv12 = true; m.maxKbps = 14000;
        HwEncode.Enc sw = enc("OMX.google.h264.encoder", false, 2048, 2048, true); sw.vbr = true; sw.flexible = true;
        return Arrays.asList(m, sw);
    }

    /** Unisoc (budget Moto E / G0x): VBR only, Baseline only, I420 only */
    static List<HwEncode.Enc> unisoc() {
        HwEncode.Enc u = enc("c2.unisoc.avc.encoder", true, 1920, 1920, true);
        u.vbr = true; u.i420 = true; u.maxKbps = 20000;
        return Arrays.asList(u, enc("c2.android.avc.encoder", false, 2048, 2048, true));
    }

    /** Exynos / Tensor (c2.exynos): CBR + VBR, High, flexible */
    static List<HwEncode.Enc> exynos() {
        HwEncode.Enc x = enc("c2.exynos.h264.encoder", true, 4096, 2304, true);
        x.cbr = x.vbr = true; x.high = x.main = true; x.flexible = true; x.qpBounds = true; x.maxKbps = 120000;
        return Arrays.asList(x);
    }

    /** an encoder that only takes landscape sizes up to 1920x1088 (old chips) */
    static List<HwEncode.Enc> landscapeOnly() {
        HwEncode.Enc o = enc("OMX.qcom.video.encoder.avc", true, 1920, 1088, false);
        o.cbr = o.vbr = true; o.high = true; o.nv12 = true; o.maxKbps = 20000;
        return Arrays.asList(o, enc("OMX.google.h264.encoder", false, 2048, 2048, true));
    }

    /** a phone whose first "video/avc" encoder is Google's software one */
    static List<HwEncode.Enc> softwareFirst() {
        HwEncode.Enc sw = enc("c2.android.avc.encoder", false, 2048, 2048, true); sw.vbr = true; sw.flexible = true;
        HwEncode.Enc hw = enc("c2.vendor.avc.encoder", true, 1920, 1920, true); hw.cbr = hw.vbr = true; hw.high = true; hw.nv12 = true;
        return Arrays.asList(sw, hw);
    }

    /** emulator / no hardware encoder at all */
    static List<HwEncode.Enc> softwareOnly() {
        HwEncode.Enc sw = enc("c2.android.avc.encoder", false, 2048, 2048, true); sw.vbr = true; sw.flexible = true;
        return Arrays.asList(sw, enc("OMX.google.h264.encoder", false, 2048, 2048, true));
    }

    static void use(List<HwEncode.Enc> phone) { FfmpegCommands.planSource = (w, h) -> HwEncode.choose(phone, w, h); }

    static String tail(FfmpegCommands.Encoder e, int spec, boolean inter) { return String.join(" ", FfmpegCommands.encodeTail(false, e, spec, 0, inter)); }

    @Test public void qualcommGetsCbrHighAndQpCeiling() {
        use(qualcomm());
        String t = tail(FfmpegCommands.Encoder.HW, 1080, false);
        assertTrue(t, t.contains("-codec_name c2.qti.avc.encoder") && t.contains("-b:v 18M") && t.contains("-profile:v high")
                && t.contains("-bitrate_mode cbr") && t.contains("-operating_rate 1000") && t.contains("-qp_p_max 32") && t.contains("-pix_fmt nv12"));
    }

    @Test public void mediatekC2SkipsSecureInstance() {
        HwEncode.Plan p = HwEncode.choose(mediatekC2(), 1080, 1920);
        assertTrue(p.hw); assertEquals("c2.mtk.avc.encoder", p.codecName); assertEquals("cbr", p.mode); assertEquals("high", p.profile);
        assertFalse(p.qpCap);
        use(mediatekC2());
        String t = tail(FfmpegCommands.Encoder.HW, 1080, false);
        assertTrue(t, t.contains("-bitrate_mode cbr") && !t.contains("qp_p_max") && t.contains("-b:v 18M"));
    }

    @Test public void oldMediatekVbrOnlyIsAskedMoreAndClampedToItsCeiling() {
        use(mediatekOmx());
        String t = tail(FfmpegCommands.Encoder.HW, 1080, false);
        // VBR only: 18 Mbps x 1.4 = 25.2 Mbps, but the encoder's ceiling is 14 Mbps (above it configure() fails)
        assertTrue(t, t.contains("-bitrate_mode vbr") && t.contains("-profile:v main") && t.contains("-b:v 14M") && !t.contains("cbr"));
        String t720 = tail(FfmpegCommands.Encoder.HW, 720, false);
        assertTrue(t720, t720.contains("-b:v 14M"));   // 10 Mbps x 1.4 = 14 = the ceiling
    }

    @Test public void unisocBaselineI420() {
        use(unisoc());
        HwEncode.Plan p = HwEncode.choose(unisoc(), 1080, 1920);
        assertEquals("yuv420p", p.pixFmt); assertNull(p.profile); assertEquals("vbr", p.mode);
        String t = tail(FfmpegCommands.Encoder.HW, 1080, false);
        assertTrue(t, t.contains("-pix_fmt yuv420p") && !t.contains("-profile:v") && t.contains("-b:v 20M"));   // 25.2 clamped to its 20 Mbps
        // the filter graph hands the encoder the same layout
        String[] c = FfmpegCommands.renderClip("in.mp4", null, 0, 10, FfmpegCommands.Frame.FILL, null, null, "o.mp4", 1080, FfmpegCommands.Encoder.HW, 0, null);
        assertTrue(String.join(" ", c).contains("format=yuv420p[v]"));
    }

    @Test public void exynosAndKirinLikeEncoders() {
        HwEncode.Plan p = HwEncode.choose(exynos(), 1080, 1920);
        assertTrue(p.hw && p.qpCap && "cbr".equals(p.mode) && "high".equals(p.profile) && "nv12".equals(p.pixFmt));
    }

    @Test public void landscapeOnlyEncoderGivesX264ForPortraitButHardwareForLandscape() {
        HwEncode.Plan port = HwEncode.choose(landscapeOnly(), 1080, 1920);
        assertFalse(port.hw);
        assertTrue(port.why, port.why.contains("1080x1920"));
        HwEncode.Plan land = HwEncode.choose(landscapeOnly(), 1920, 1080);   // FFmpeg encodes 1920x1088: fits
        assertTrue(land.hw);
        HwEncode.Plan p720 = HwEncode.choose(landscapeOnly(), 720, 1280);
        assertFalse(p720.hw);
    }

    @Test public void softwareEncoderIsNeverUsed() {
        HwEncode.Plan p = HwEncode.choose(softwareFirst(), 1080, 1920);
        assertTrue(p.hw); assertEquals("c2.vendor.avc.encoder", p.codecName);
        HwEncode.Plan none = HwEncode.choose(softwareOnly(), 1080, 1920);
        assertFalse(none.hw); assertTrue(none.why.contains("no hardware"));
        HwEncode.Plan empty = HwEncode.choose(new ArrayList<>(), 1080, 1920);
        assertFalse(empty.hw);
    }

    @Test public void alignmentIsChecked() {
        // an encoder that takes 1080 wide but not 1088 (FFmpeg pads H.264 to 16) is not used for 1080x1920
        HwEncode.Enc e = enc("c2.odd.avc.encoder", true, 1080, 1920, false);
        e.cbr = true; e.high = true; e.nv12 = true;
        assertFalse(HwEncode.choose(Arrays.asList(e), 1080, 1920).hw);
        assertTrue(HwEncode.choose(Arrays.asList(e), 720, 1280).hw);
    }

    @Test public void ladderSteps() {
        use(qualcomm());
        assertEquals(FfmpegCommands.Encoder.HW_STD, FfmpegCommands.Encoder.HW.next());
        assertEquals(FfmpegCommands.Encoder.HW_BASIC, FfmpegCommands.Encoder.HW_STD.next());
        assertEquals(FfmpegCommands.Encoder.X264, FfmpegCommands.Encoder.HW_BASIC.next());
        String std = tail(FfmpegCommands.Encoder.HW_STD, 1080, false);
        assertTrue(std, std.contains("-codec_name c2.qti.avc.encoder") && std.contains("-bitrate_mode cbr") && std.contains("-profile:v high")
                && !std.contains("operating_rate") && !std.contains("qp_"));
        String basic = tail(FfmpegCommands.Encoder.HW_BASIC, 1080, false);
        // the bare 3.1 settings (encoder default rate control = VBR on most phones: asked 1.4x)
        assertTrue(basic, !basic.contains("codec_name") && !basic.contains("profile") && !basic.contains("bitrate_mode") && basic.contains("-b:v 25200k"));
        assertEquals(0, Pipeline.rank(FfmpegCommands.Encoder.HW));
        assertEquals(3, Pipeline.rank(FfmpegCommands.Encoder.X264));
        for (int r = 0; r < 4; r++) assertEquals(r, Pipeline.rank(Pipeline.ofRank(r)));
    }

    @Test public void legacyPlanKeepsCode25Arguments() {
        FfmpegCommands.planSource = null;
        String t = tail(FfmpegCommands.Encoder.HW, 1080, false);
        assertTrue(t, t.contains("-c:v h264_mediacodec -b:v 18M -g 60 -profile:v high -bitrate_mode cbr -operating_rate 1000 -pix_fmt nv12"));
    }

    @Test public void boostIsClampedToTheEncoderCeiling() {
        use(mediatekC2());
        FfmpegCommands.hwBoost = 2.5;
        assertEquals("40M", FfmpegCommands.hwBitrate(1080, false, FfmpegCommands.Encoder.HW));   // final short: 45 -> its 40 Mbps ceiling
        assertEquals("40M", FfmpegCommands.hwBitrate(1080, true, FfmpegCommands.Encoder.HW));    // intermediate: never above the encoder's 40 Mbps
        use(mediatekOmx());
        assertEquals("14M", FfmpegCommands.hwBitrate(1080, true, FfmpegCommands.Encoder.HW));    // never above the encoder's 14 Mbps
    }

    @Test public void failureClassification() {
        String net = "[https @ 0x7a] Connection reset by peer\n[in#0 @ 0x7b] Error during demuxing: I/O error\nConversion failed!";
        assertEquals(RenderFault.Kind.NETWORK, RenderFault.classify(net, true));
        assertEquals(RenderFault.Kind.OTHER, RenderFault.classify("Connection reset by peer", false));   // a local file has no network
        String enc = "[h264_mediacodec @ 0x1] MediaCodec configure failed, Unknown error\n[vost#0:0/h264_mediacodec @ 0x2] Error while opening encoder - maybe incorrect parameters such as bit_rate, rate, width or height.\nConversion failed!";
        assertEquals(RenderFault.Kind.ENCODER, RenderFault.classify(enc, true));
        String dec = "[vp9_mediacodec @ 0x1] Failed to configure codec\n[vist#0:0/vp9 @ 0x2] Error while decoding stream #0:0: Generic error in an external library";
        assertEquals(RenderFault.Kind.DECODER, RenderFault.classify(dec, false));
        String both = "[vist#0:0/vp9 @ 0x2] Error while decoding stream\n[vost#0:0/h264_mediacodec @ 0x3] Could not open encoder before EOF";
        assertEquals(RenderFault.Kind.DECODER, RenderFault.classify(both, false));   // the first error is the cause
        assertEquals(RenderFault.Kind.ENCODER, RenderFault.classify(new Pipeline.HwException("hardware encoder output did not play back"), true));
        assertEquals(RenderFault.Kind.NETWORK, RenderFault.classify(new MediaRunner.Failed("FFmpeg failed (code 1): x", "Server returned 403 Forbidden (access denied)"), true));
        assertEquals(RenderFault.Kind.OTHER, RenderFault.classify("FFmpeg failed (code 1): something odd", true));
    }

    @Test public void qualityGuardThresholds() {
        // code 27: 1080x1920 target 18 Mbps: below 8.1 Mbps = starved; 720x1280 target 10 Mbps: below 4.5
        assertTrue(FfmpegCommands.starved(1080, 8.0));
        assertFalse(FfmpegCommands.starved(1080, 8.2));
        assertTrue(FfmpegCommands.starved(720, 4.4));
        assertFalse(FfmpegCommands.starved(720, 4.6));
        assertFalse(FfmpegCommands.starved(1080, 0));     // unknown
        assertTrue(FfmpegCommands.undershot(3.0, 6.0));   // x264 needed 2x the bits: the hardware encoder starved it
        assertFalse(FfmpegCommands.undershot(3.0, 3.5));  // easy picture
    }

    /** writes the app's own Fill graphs (1080p / 1440p / 4K source -> 1080x1920) for the box render matrix */
    @Test public void dumpGraphsForBoxMatrix() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int h : new int[]{1080, 1440, 2160})
            sb.append("FILL").append(h).append('\t').append(FfmpegCommands.frameGraph(FfmpegCommands.Frame.FILL, null, null, 1080, "yuv420p", h)).append('\n');
        sb.append("FILL720_1080\t").append(FfmpegCommands.frameGraph(FfmpegCommands.Frame.FILL, null, null, 720, "yuv420p", 1080)).append('\n');
        java.nio.file.Files.write(new java.io.File(System.getProperty("java.io.tmpdir"), "klipora_v26_graphs.txt").toPath(), sb.toString().getBytes("UTF-8"));
        assertTrue(sb.toString().contains("unsharp"));
    }

    @Test public void softwareDecodedSharperSourceRule() {
        assertTrue(Pipeline.swHiOk("vp9", 1440, 30));
        assertFalse(Pipeline.swHiOk("vp9", 1440, 60));    // 60 fps: twice the decoding
        assertFalse(Pipeline.swHiOk("vp9", 2160, 30));    // 4K only with a hardware decoder
        assertFalse(Pipeline.swHiOk("av1", 1440, 30));    // AV1 is too slow in software
        assertFalse(Pipeline.swHiOk("vp9", 1080, 30));
    }
}
