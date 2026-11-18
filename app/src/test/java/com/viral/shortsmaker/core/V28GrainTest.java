package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Locale;

/** code 28 (4.6): grain fix - light denoise before a gentler sharpen, only when the crop is enlarged */
public class V28GrainTest {
    @Test public void enlargeCases() {
        // 1080p source -> 1080x1920 fill (1.78x)
        assertEquals("hqdn3d=1.5:1.2:3:2.5,unsharp=5:5:0.35:5:5:0.0,scale=1080:1920:flags=lanczos", FfmpegCommands.scaleHq(1080, 1920, 1080, 1920));
        // 1440p -> 1080x1920 (1.33x), 1080p -> 720x1280 (1.19x), split panel (1.11x)
        assertEquals("hqdn3d=1.5:1.2:3:2.5,unsharp=5:5:0.20:5:5:0.0,scale=1080:1920:flags=lanczos", FfmpegCommands.scaleHq(1080, 1920, 1440, 1920));
        assertEquals("hqdn3d=1.5:1.2:3:2.5,unsharp=5:5:0.20:5:5:0.0,scale=720:1280:flags=lanczos", FfmpegCommands.scaleHq(720, 1280, 1080, 1280));
        assertEquals("hqdn3d=1.5:1.2:3:2.5,unsharp=5:5:0.20:5:5:0.0,scale=1080:960:flags=lanczos", FfmpegCommands.scaleHq(1080, 960, 864, 960));
        // no enlarge: unchanged (4K, 1:1, downscale), unknown crop: unchanged
        assertEquals("scale=1080:1920:flags=lanczos", FfmpegCommands.scaleHq(1080, 1920, 2160, 1920));
        assertEquals("scale=1080:1920:flags=lanczos", FfmpegCommands.scaleHq(1080, 1920, 1920, 1920));
        assertEquals("scale=720:1280:flags=lanczos", FfmpegCommands.scaleHq(720, 1280, 1440, 1280));
        assertEquals("scale=1080:1920:flags=lanczos", FfmpegCommands.scaleHq(1080, 1920, 0, 1920));
    }

    @Test public void noDecimalCommaInAnyLocale() {
        Locale old = Locale.getDefault();
        try {
            for (Locale l : new Locale[]{new Locale("hi", "IN"), Locale.GERMANY, new Locale("ar"), new Locale("fr")}) {
                Locale.setDefault(l);
                String s = FfmpegCommands.scaleHq(1080, 1920, 1080, 1920);
                assertFalse(l + ": " + s, s.contains(","+"35") && s.contains("0,35"));
                assertTrue(s.contains("unsharp=5:5:0.35:5:5:0.0"));
            }
        } finally { Locale.setDefault(old); }
    }

    @Test public void oldStrongSharpenGone() {
        for (int h : new int[]{540, 720, 864, 1080, 1440}) {
            String s = FfmpegCommands.scaleHq(1080, 1920, h, 1920);
            assertFalse(s, s.contains("unsharp=3:3:1.0"));
            assertFalse(s, s.contains("unsharp=3:3:0.6"));
        }
    }
}
