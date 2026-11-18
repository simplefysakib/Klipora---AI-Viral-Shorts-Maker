package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** code 33 (5.1): every engine crash moves the phone to a really different path (his vivo Y16: 14 identical crashes on 5.0) */
public class V33LadderTest {
    private static int key(int[] s) { return s[0] * 10 + s[1]; }

    @Test public void vivoX264CrashGoesToHardwareEncoderThenCpuSafeThenNative() {
        // 5.0 on his vivo: level SW_DECODE ("software decoding + hardware encoder (kept)") but x264 ran (quality mark)
        int[] s = {RenderSafety.SW_DECODE, RenderSafety.R_NORMAL};
        int[] a = RenderSafety.stepAfterCrash(s[0], s[1], RenderSafety.K_SOFTWARE, true, true);
        assertEquals(RenderSafety.R_NO_X264, a[1]);
        assertEquals(RenderSafety.SW_DECODE, a[0]);
        int[] b = RenderSafety.stepAfterCrash(a[0], a[1], RenderSafety.K_HW_ENCODE, false, true);   // the hw encoder crashes too
        assertEquals(RenderSafety.SW_ALL, b[0]);
        assertEquals(RenderSafety.R_NO_ASM, b[1]);
        int[] c = RenderSafety.stepAfterCrash(b[0], b[1], RenderSafety.K_SOFTWARE, true, true);     // CPU-safe x264 crashes
        assertEquals(RenderSafety.R_NATIVE, c[1]);
        int[] d = RenderSafety.stepAfterCrash(c[0], c[1], RenderSafety.K_NATIVE, false, true);      // nothing left: unchanged
        assertEquals(key(c), key(d));
    }

    @Test public void noHardwareEncoderGoesStraightToCpuSafe() {
        int[] a = RenderSafety.stepAfterCrash(RenderSafety.SW_ALL, RenderSafety.R_NORMAL, RenderSafety.K_SOFTWARE, true, false);
        assertEquals(RenderSafety.R_NO_ASM, a[1]);
        int[] b = RenderSafety.stepAfterCrash(a[0], a[1], RenderSafety.K_SOFTWARE, true, false);
        assertEquals(RenderSafety.R_NATIVE, b[1]);
    }

    @Test public void everyCrashChangesThePathUntilTheBackupEngine() {
        int[] kinds = {RenderSafety.K_HW_DECODE, RenderSafety.K_HW_ENCODE, RenderSafety.K_SOFTWARE};
        for (int lvl = RenderSafety.FULL; lvl <= RenderSafety.SW_ALL; lvl++)
            for (int r = RenderSafety.R_NORMAL; r < RenderSafety.R_NATIVE; r++)
                for (int k : kinds) for (boolean x : new boolean[]{true, false}) for (boolean hw : new boolean[]{true, false}) {
                    int[] n = RenderSafety.stepAfterCrash(lvl, r, k, x, hw);
                    assertNotEquals("level " + lvl + " rung " + r + " kind " + k, lvl * 10 + r, key(n));
                    assertTrue(n[0] >= lvl && n[1] >= r);   // never back to a path that crashed
                }
    }

    @Test public void hardwareDecoderCrashStillOnlyDropsTheDecoder() {
        int[] a = RenderSafety.stepAfterCrash(RenderSafety.FULL, RenderSafety.R_NORMAL, RenderSafety.K_HW_DECODE, false, true);
        assertEquals(RenderSafety.SW_DECODE, a[0]);
        assertEquals(RenderSafety.R_NORMAL, a[1]);
    }

    @Test public void nativeCommandIsItsOwnKind() {
        assertEquals(RenderSafety.K_NATIVE, RenderSafety.kindOf(new String[]{RenderSafety.NATIVE_ARG, "{}"}));
        assertTrue(RenderSafety.usesX264(new String[]{"-c:v", "libx264"}));
        assertFalse(RenderSafety.usesX264(new String[]{"-c:v", "h264_mediacodec"}));
    }

    @Test public void cpuSafeSwitchesOffAsmAndThreadsOnEveryCommand() {
        String[] cmd = FfmpegCommands.renderClipSafe("/v.mp4", "/a.webm", 10, 20, null, null, "/o.mp4", 720);
        String[] s = FfmpegCommands.cpuSafe(cmd);
        List<String> l = Arrays.asList(s);
        assertEquals("-cpuflags", s[0]);
        assertEquals("0", s[1]);
        assertTrue(l.contains("-filter_complex_threads"));
        for (int i = 0; i < s.length; i++) if ("-i".equals(s[i])) { assertEquals("1", s[i - 1]); assertEquals("-threads", s[i - 2]); }
        int x = l.indexOf("libx264");
        assertEquals("-x264-params", s[x + 1]);
        assertEquals("asm=0", s[x + 2]);
        assertArrayEquals(s, FfmpegCommands.cpuSafe(s));   // idempotent
        // the edit itself is untouched: same graph, same output
        assertEquals(cmd[Arrays.asList(cmd).indexOf("-filter_complex") + 1], s[l.indexOf("-filter_complex") + 1]);
        assertEquals(cmd[cmd.length - 1], s[s.length - 1]);
    }

    @Test public void cpuSafeLimitsX264Threads() {
        String[] cmd = {"-y", "-i", "in.mp4", "-c:v", "libx264", "-threads", "0", "out.mp4"};
        List<String> l = Arrays.asList(FfmpegCommands.cpuSafe(cmd));
        int t = l.lastIndexOf("-threads");
        assertEquals("2", l.get(t + 1));
    }
}
