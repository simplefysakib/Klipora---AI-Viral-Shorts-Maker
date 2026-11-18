package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** v23 (code 23): output quality — bitrates, intermediate files, encoder correction, sharper scaling. */
public class V23Test {

    private static String j(String[] a) { return String.join(" ", a); }

    @Test public void hardwareBitrates() {
        FfmpegCommands.hwBoost = 1.0;
        assertEquals("18M", FfmpegCommands.hwBitrate(1080));                                          // 1080x1920 (code 27: 12 -> 18)
        assertEquals("18M", FfmpegCommands.hwBitrate(FfmpegCommands.Aspect.LANDSCAPE.spec(1080)));   // 1920x1080
        assertEquals("12M", FfmpegCommands.hwBitrate(FfmpegCommands.Aspect.SQUARE.spec(1080)));
        assertEquals("10M", FfmpegCommands.hwBitrate(720));                                            // 720x1280 tier
        assertEquals("10M", FfmpegCommands.hwBitrate(FfmpegCommands.Aspect.LANDSCAPE.spec(720)));
        assertEquals("6M", FfmpegCommands.hwBitrate(FfmpegCommands.Aspect.SQUARE.spec(720)));
        assertEquals("36M", FfmpegCommands.hwBitrate(1080, true));                                    // intermediate 2x (v26; was 1.5x)
        assertEquals("20M", FfmpegCommands.hwBitrate(720, true));
    }

    @Test public void encoderCorrection() {
        // encoder gives half of what it's asked -> ask 2x (v26 cap 2.5x)
        assertEquals(2.0, FfmpegCommands.nextBoost(1.0, 12000, 6.0), 1e-9);
        assertEquals(2.5, FfmpegCommands.nextBoost(1.0, 12000, 3.0), 1e-9);
        // gives 80% -> ask 1.25x
        assertEquals(1.25, FfmpegCommands.nextBoost(1.0, 12000, 9.6), 1e-9);
        // with 1.25x it now lands on target -> stays 1.25
        assertEquals(1.25, FfmpegCommands.nextBoost(1.25, 12000, 12.0), 1e-9);
        // delivers what it is asked -> 1
        assertEquals(1.0, FfmpegCommands.nextBoost(1.0, 12000, 12.1), 1e-9);
        // no information
        assertEquals(1.3, FfmpegCommands.nextBoost(1.3, 12000, 0), 1e-9);
        try {
            FfmpegCommands.hwBoost = 1.25;
            assertEquals("22500k", FfmpegCommands.hwBitrate(1080));
            FfmpegCommands.hwBoost = 5;   // never above the cap
            assertEquals("40M", FfmpegCommands.hwBitrate(1080));   // 2.5x cap, final shorts at most 40 Mbps (code 27)
        } finally { FfmpegCommands.hwBoost = 1.0; }
    }

    @Test public void intermediateAndFinalTails() {
        String fin = String.join(" ", FfmpegCommands.encodeTail(false, FfmpegCommands.Encoder.HW, 1080, 0, false));
        String mid = String.join(" ", FfmpegCommands.encodeTail(false, FfmpegCommands.Encoder.HW, 1080, 0, true));
        assertTrue(fin.contains("-b:v 18M") && fin.contains("+faststart") && fin.contains("-profile:v high"));
        assertTrue(mid.contains("-b:v 36M") && !mid.contains("faststart"));
        String sw = String.join(" ", FfmpegCommands.encodeTail(false, FfmpegCommands.Encoder.X264, 1080, 0, true));
        assertTrue(sw.contains("-crf 12"));
        String[] raw = FfmpegCommands.renderClip("in.mp4", null, 0, 10, FfmpegCommands.Frame.BLUR, null, null, "raw_0.mp4", 1080,
                FfmpegCommands.Encoder.HW, 0, null, true);
        assertTrue(j(raw).contains("-b:v 36M"));
        String[] out = FfmpegCommands.renderClip("in.mp4", null, 0, 10, FfmpegCommands.Frame.BLUR, null, null, "short_0.mp4", 1080,
                FfmpegCommands.Encoder.HW, 0, null);
        assertTrue(j(out).contains("-b:v 18M"));
        // caption burn = the final file
        String[] burn = FfmpegCommands.burnCaptions("raw_0.mp4", "c.ass", null, "short_0.mp4", FfmpegCommands.Encoder.HW, 1080, 0);
        assertTrue(j(burn).contains("-b:v 18M") && j(burn).contains("+faststart"));
    }

    @Test public void sharpScaling() {
        String fit = FfmpegCommands.frameGraph(FfmpegCommands.Frame.FIT, null, null, 1080);
        String blur = FfmpegCommands.frameGraph(FfmpegCommands.Frame.BLUR, null, null, 1080);
        assertTrue(fit.contains("flags=lanczos") && !fit.contains("bicubic"));
        assertTrue(blur, blur.contains("[b]scale=1080:1920:force_original_aspect_ratio=decrease:flags=lanczos[fg]"));
        // the background is still blurred small (cheap) and only the background
        assertTrue(blur.contains("boxblur") && blur.indexOf("boxblur") < blur.indexOf("[bg]"));
    }

    /** real FFmpeg on the build computer (x264 path): each framing renders a valid 1080x1920 file. -Dv23Src=/path/1080p.mp4 */
    @Test public void realFfmpegRenders() throws Exception {
        String src = System.getProperty("v23Src", "");
        org.junit.Assume.assumeTrue(!src.isEmpty() && new File(src).exists());
        File dir = new File(System.getProperty("java.io.tmpdir"), "v23test");
        dir.mkdirs();
        for (FfmpegCommands.Frame fr : FfmpegCommands.Frame.values()) {
            File o = new File(dir, "o_" + fr + ".mp4");
            String[] a = FfmpegCommands.renderClip(src, null, 1, 4, fr, null, null, o.getAbsolutePath(), 1080, FfmpegCommands.Encoder.X264, 2, null);
            List<String> cmd = new ArrayList<>(); cmd.add("ffmpeg"); cmd.addAll(Arrays.asList(a));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(new File(dir, "log_" + fr + ".txt")).start();
            assertEquals(fr + " ffmpeg exit", 0, p.waitFor());
            assertTrue(o.length() > 100_000);
        }
    }
}
