package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * code 27: high bitrate on every phone (Sakib, Nov 2: "shorts bilkul high quality aur HIGH BITRATE mein"). The exact
 * bitrate each simulated phone class is asked for (final short + two-pass caption intermediate, CBR vs VBR, clamped to
 * the encoder's own bitrate range), the software (x264) path, the safe-mode render and the AAC audio settings.
 */
public class V27Test {

    @After public void reset() { FfmpegCommands.planSource = null; FfmpegCommands.hwBoost = 1.0; }

    private static String tail(FfmpegCommands.Encoder e, int spec, boolean inter) { return V26Test.tail(e, spec, inter); }
    private static long ask(int spec, boolean inter) { return FfmpegCommands.hwAskKbps(spec, inter, FfmpegCommands.Encoder.HW); }

    @Test public void baseTargetsByPixelCount() {
        assertEquals(18000, FfmpegCommands.hwBaseKbps(1080, false));                                           // 1080x1920
        assertEquals(18000, FfmpegCommands.hwBaseKbps(FfmpegCommands.Aspect.LANDSCAPE.spec(1080), false));     // 1920x1080
        assertEquals(12000, FfmpegCommands.hwBaseKbps(FfmpegCommands.Aspect.SQUARE.spec(1080), false));        // 1080x1080
        assertEquals(10000, FfmpegCommands.hwBaseKbps(720, false));                                            // 720x1280
        assertEquals(10000, FfmpegCommands.hwBaseKbps(FfmpegCommands.Aspect.LANDSCAPE.spec(720), false));      // 1280x720
        assertEquals(6000, FfmpegCommands.hwBaseKbps(FfmpegCommands.Aspect.SQUARE.spec(720), false));          // 720x720
        assertEquals(4500, FfmpegCommands.hwBaseKbps(480, false));                                             // 480x854
        assertEquals(3000, FfmpegCommands.hwBaseKbps(FfmpegCommands.Aspect.SQUARE.spec(480), false));          // 480x480
        // 60 fps = 4/3 (1080p60 = 24 Mbps); every short is rendered at 30 fps today
        assertEquals(24000, FfmpegCommands.hwBaseKbps(1080, false, 60));
        assertEquals(13333, FfmpegCommands.hwBaseKbps(720, false, 60));
        assertEquals(30, FfmpegCommands.OUTPUT_FPS);
        // two-pass caption intermediate: 2x
        assertEquals(36000, FfmpegCommands.hwBaseKbps(1080, true));
        assertEquals(20000, FfmpegCommands.hwBaseKbps(720, true));
        // more pixels never get fewer bits
        int[] specs = {480, FfmpegCommands.Aspect.SQUARE.spec(720), 720, FfmpegCommands.Aspect.SQUARE.spec(1080), 1080};
        for (int i = 1; i < specs.length; i++) assertTrue(FfmpegCommands.hwBaseKbps(specs[i], false) > FfmpegCommands.hwBaseKbps(specs[i - 1], false));
    }

    /** {phone, spec, intermediate, expected kbps} for every simulated device class */
    @Test public void deviceClassMatrix() {
        Object[][] m = {
                // Snapdragon (CBR, up to 100 Mbps): exactly the targets
                {V26Test.qualcomm(), 1080, false, 18000L}, {V26Test.qualcomm(), 1080, true, 36000L},
                {V26Test.qualcomm(), 720, false, 10000L}, {V26Test.qualcomm(), 720, true, 20000L},
                // MediaTek Dimensity / Helio c2 (Moto G, CBR, 40 Mbps)
                {V26Test.mediatekC2(), 1080, false, 18000L}, {V26Test.mediatekC2(), 1080, true, 36000L},
                {V26Test.mediatekC2(), 720, false, 10000L},
                // old MediaTek OMX (VBR only, 14 Mbps ceiling): 1.4x asked, clamped to 14 Mbps
                {V26Test.mediatekOmx(), 1080, false, 14000L}, {V26Test.mediatekOmx(), 1080, true, 14000L}, {V26Test.mediatekOmx(), 720, false, 14000L},
                // Unisoc (VBR only, 20 Mbps): 25.2 -> 20; 720p 14
                {V26Test.unisoc(), 1080, false, 20000L}, {V26Test.unisoc(), 720, false, 14000L}, {V26Test.unisoc(), 720, true, 20000L},
                // Exynos / Tensor (CBR, 120 Mbps)
                {V26Test.exynos(), 1080, false, 18000L}, {V26Test.exynos(), 1080, true, 36000L},
                // old landscape-only Qualcomm OMX (CBR, 20 Mbps) for 1920x1080
                {V26Test.landscapeOnly(), FfmpegCommands.Aspect.LANDSCAPE.spec(1080), false, 18000L},
                {V26Test.landscapeOnly(), FfmpegCommands.Aspect.LANDSCAPE.spec(1080), true, 20000L},
                // vendor encoder listed after Google's software one (CBR, ceiling unknown)
                {V26Test.softwareFirst(), 1080, false, 18000L}, {V26Test.softwareFirst(), 1080, true, 36000L},
        };
        for (Object[] r : m) {
            @SuppressWarnings("unchecked") List<HwEncode.Enc> phone = (List<HwEncode.Enc>) r[0];
            V26Test.use(phone);
            int spec = (Integer) r[1]; boolean inter = (Boolean) r[2];
            assertEquals(phone.get(0).name + " " + spec + (inter ? " intermediate" : ""), ((Long) r[3]).longValue(), ask(spec, inter));
            String t = tail(FfmpegCommands.Encoder.HW, spec, inter);
            assertTrue(t, t.contains("-b:v " + FfmpegCommands.fmtKbps((Long) r[3])));
        }
    }

    @Test public void highProfileWhereListed() {
        V26Test.use(V26Test.qualcomm());
        assertTrue(tail(FfmpegCommands.Encoder.HW, 1080, false).contains("-profile:v high"));
        V26Test.use(V26Test.mediatekC2());
        assertTrue(tail(FfmpegCommands.Encoder.HW, 1080, true).contains("-profile:v high"));
        V26Test.use(V26Test.mediatekOmx());   // lists Main only
        assertTrue(tail(FfmpegCommands.Encoder.HW, 1080, false).contains("-profile:v main"));
        // software encoder: High profile too
        assertTrue(tail(FfmpegCommands.Encoder.X264, 1080, false).contains("-profile:v high"));
    }

    @Test public void clampedToTheEncodersReportedRange() {
        // floor: an encoder whose bitrate range starts at 12 Mbps is never asked less (720p asked 10 -> 12)
        HwEncode.Enc e = V26Test.enc("c2.floor.avc.encoder", true, 1920, 1920, true);
        e.cbr = true; e.high = true; e.nv12 = true; e.minKbps = 12000; e.maxKbps = 30000;
        V26Test.use(Arrays.asList(e));
        assertEquals(12000, ask(720, false));
        assertEquals(18000, ask(1080, false));
        assertEquals(30000, ask(1080, true));   // 36 -> its 30 Mbps ceiling
        HwEncode.Plan p = HwEncode.choose(Arrays.asList(e), 1080, 1920);
        assertEquals(12000, p.minKbps); assertEquals(30000, p.maxKbps);
        // no range reported: own ceilings (final 40, intermediate 50 Mbps)
        HwEncode.Enc n = V26Test.enc("c2.norange.avc.encoder", true, 1920, 1920, true);
        n.cbr = true; n.high = true; n.nv12 = true;
        V26Test.use(Arrays.asList(n));
        FfmpegCommands.hwBoost = 2.5;
        assertEquals(40000, ask(1080, false));
        assertEquals(50000, ask(1080, true));
        assertEquals(25000, ask(720, false));
    }

    @Test public void vbrFallbackAsksMoreButStaysClamped() {
        HwEncode.Enc v = V26Test.enc("c2.vbr.avc.encoder", true, 1920, 1920, true);
        v.vbr = true; v.high = true; v.nv12 = true; v.maxKbps = 100000;
        V26Test.use(Arrays.asList(v));
        assertEquals(25200, ask(1080, false));    // 18 x 1.4
        assertEquals(50000, ask(1080, true));     // 36 x 1.4 = 50.4 -> 50
        assertEquals(14000, ask(720, false));     // 10 x 1.4
        assertTrue(tail(FfmpegCommands.Encoder.HW, 1080, false).contains("-bitrate_mode vbr"));
        // HW_BASIC (encoder default rate control) is asked like VBR
        V26Test.use(V26Test.qualcomm());
        assertTrue(tail(FfmpegCommands.Encoder.HW_BASIC, 1080, false).contains("-b:v 25200k"));
    }

    @Test public void qualityGuardAt45PercentOfTheNewTarget() {
        assertEquals(0.45, FfmpegCommands.GUARD_FRACTION, 1e-9);
        assertTrue(FfmpegCommands.starved(1080, 8.0));            // < 8.1 of 18
        assertFalse(FfmpegCommands.starved(1080, 8.2));
        assertTrue(FfmpegCommands.starved(720, 4.4));             // < 4.5 of 10
        assertFalse(FfmpegCommands.starved(720, 4.6));
        assertTrue(FfmpegCommands.starved(FfmpegCommands.Aspect.SQUARE.spec(1080), 5.3));   // < 5.4 of 12
    }

    @Test public void softwarePathIsHighQualityToo() {
        String fin = tail(FfmpegCommands.Encoder.X264, 1080, false);
        assertTrue(fin, fin.contains("-c:v libx264") && fin.contains("-crf 14") && fin.contains("-profile:v high") && fin.contains("-preset superfast"));
        String mid = tail(FfmpegCommands.Encoder.X264, 1080, true);
        assertTrue(mid, mid.contains("-crf 12"));
        assertTrue(FfmpegCommands.X264_CRF <= 14 && FfmpegCommands.X264_CRF_INTERMEDIATE < FfmpegCommands.X264_CRF && FfmpegCommands.X264_CRF_SAFE <= 16);
        String safe = String.join(" ", FfmpegCommands.renderClipSafe("in.mp4", null, 0, 10, null, null, "o.mp4", 1080));
        assertTrue(safe, safe.contains("-crf 16") && safe.contains("-b:a 192k") && safe.contains("-ar 48000") && safe.contains("-ac 2"));
    }

    @Test public void audioIsAac192kStereo48k() {
        for (FfmpegCommands.Encoder e : FfmpegCommands.Encoder.values()) {
            String t = tail(e, 1080, false);
            assertTrue(e + ": " + t, t.contains("-c:a aac -b:a 192k -ac 2 -ar 48000") && !t.contains("128k") && !t.contains("44100"));
        }
        assertEquals(1024.0 / 48000.0, Pipeline.AAC_PRIMING_SEC, 1e-12);
        assertEquals(0.2, FfmpegCommands.AUDIO_MBPS, 1e-9);
    }

    @Test public void everyFinalRenderUsesTheRaisedBitrate() {
        V26Test.use(V26Test.mediatekC2());
        String[] c = FfmpegCommands.renderClip("in.mp4", null, 0, 10, FfmpegCommands.Frame.FILL, null, null, "o.mp4", 1080, FfmpegCommands.Encoder.HW, 0, null);
        String j = String.join(" ", c);
        assertTrue(j, j.contains("-b:v 18M") && j.contains("-r 30") && j.contains("-b:a 192k"));
    }
}
