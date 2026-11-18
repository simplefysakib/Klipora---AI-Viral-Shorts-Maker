package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * code 34 (5.2): his vivo Y16 (Helio P35 MT6765) crashed in FFmpeg's NEON scaler (libswscale +0x239a4) on every render.
 * The first answer to such a crash is the same command with NEON off; chips of that family get it from the first render;
 * every other phone runs exactly the 5.1 command.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V34NoNeonTest {
    private Context c;

    @Before public void setUp() {
        c = ApplicationProvider.getApplicationContext();
        Prefs.sp(c).edit().clear().commit();
        RenderSafety.resetForTest();
        RenderSafety.setRiskySocForTest(false);
        MediaRunner.appCtx = null;
    }

    @After public void tearDown() { RenderSafety.resetForTest(); Prefs.sp(c).edit().clear().commit(); }

    static final String VIVO_WHERE = "CRASH_NATIVE, signal 11 in libavutil.so, libutils.so, libandroid_runtime.so, libperfetto_hprof.so, libbinder.so, libswscale.so, libavfilter.so, libffmpegkit.so";

    // ------------------------------------------------------------------ pure ladder

    @Test public void softwareCrashFirstStepIsTheSameCommandWithoutNeon() {
        // the vivo on a fresh build: level FULL, x264 (its hardware encoder carries the poor-quality mark)
        int[] a = RenderSafety.stepAfterCrash(RenderSafety.FULL, RenderSafety.R_NORMAL, RenderSafety.K_SOFTWARE, true, true, false, VIVO_WHERE);
        assertArrayEquals(new int[]{RenderSafety.FULL, RenderSafety.R_NORMAL, 1}, a);   // level and rung unchanged, NEON off
        // it crashes again without NEON: the 5.1 ladder goes on (hardware encoder), NEON stays off
        int[] b = RenderSafety.stepAfterCrash(a[0], a[1], RenderSafety.K_SOFTWARE, true, true, true, VIVO_WHERE);
        assertEquals(RenderSafety.R_NO_X264, b[1]);
        assertEquals(1, b[2]);
        int[] cc = RenderSafety.stepAfterCrash(b[0], b[1], RenderSafety.K_HW_ENCODE, false, true, true, VIVO_WHERE);
        assertEquals(RenderSafety.R_NO_ASM, cc[1]);
        int[] d = RenderSafety.stepAfterCrash(cc[0], cc[1], RenderSafety.K_SOFTWARE, true, true, true, VIVO_WHERE);
        assertEquals(RenderSafety.R_NATIVE, d[1]);
    }

    @Test public void unknownCrashPlaceOnTheSoftwarePathAlsoTriesNoNeonFirst() {
        assertTrue(RenderSafety.neonFirst(RenderSafety.K_SOFTWARE, RenderSafety.R_NORMAL, false, ""));
        assertTrue(RenderSafety.neonFirst(RenderSafety.K_SOFTWARE, RenderSafety.R_NO_X264, false, null));
        assertFalse(RenderSafety.neonFirst(RenderSafety.K_SOFTWARE, RenderSafety.R_NO_ASM, false, null));   // CPU-safe is already off
        assertFalse(RenderSafety.neonFirst(RenderSafety.K_NATIVE, RenderSafety.R_NATIVE, false, VIVO_WHERE));
        assertFalse(RenderSafety.neonFirst(RenderSafety.K_NONE, RenderSafety.R_NORMAL, false, VIVO_WHERE));
    }

    @Test public void hardwareCodecCrashKeepsThe49Step() {
        // his HONOR (Qualcomm encoder): the tombstone names the codec stack -> hardware decoder off / x264, as in 4.9
        String honor = "CRASH_NATIVE, signal 11 in libcodec2_qti_avc.so, libswscale.so, libavcodec.so, C2PlatformComponentStore";
        int[] a = RenderSafety.stepAfterCrash(RenderSafety.FULL, RenderSafety.R_NORMAL, RenderSafety.K_HW_ENCODE, false, true, false, honor);
        assertEquals(RenderSafety.SW_ALL, a[0]);
        assertEquals(0, a[2]);
        int[] b = RenderSafety.stepAfterCrash(RenderSafety.FULL, RenderSafety.R_NORMAL, RenderSafety.K_HW_DECODE, false, true, false, "");
        assertEquals(RenderSafety.SW_DECODE, b[0]);
        assertEquals(0, b[2]);
    }

    @Test public void hardwarePathCrashInFfmpegsScalerTriesNoNeonFirst() {
        int[] a = RenderSafety.stepAfterCrash(RenderSafety.FULL, RenderSafety.R_NORMAL, RenderSafety.K_HW_ENCODE, false, true, false, VIVO_WHERE);
        assertArrayEquals(new int[]{RenderSafety.FULL, RenderSafety.R_NORMAL, 1}, a);
        assertTrue(RenderSafety.ffmpegOwnCrash("in libswscale.so, libavfilter.so"));
        assertFalse(RenderSafety.ffmpegOwnCrash("in libavcodec.so"));
        assertFalse(RenderSafety.ffmpegOwnCrash("in libswscale.so, libstagefright.so"));
        assertFalse(RenderSafety.ffmpegOwnCrash(null));
    }

    @Test public void everyCrashStillChangesThePath() {
        int[] kinds = {RenderSafety.K_HW_DECODE, RenderSafety.K_HW_ENCODE, RenderSafety.K_SOFTWARE};
        String[] wheres = {"", VIVO_WHERE, "in libcodec2_qti.so"};
        for (int lvl = RenderSafety.FULL; lvl <= RenderSafety.SW_ALL; lvl++)
            for (int r = RenderSafety.R_NORMAL; r < RenderSafety.R_NATIVE; r++)
                for (int k : kinds) for (boolean x : new boolean[]{true, false}) for (boolean hw : new boolean[]{true, false})
                    for (boolean nn : new boolean[]{true, false}) for (String w : wheres) {
                        int[] n = RenderSafety.stepAfterCrash(lvl, r, k, x, hw, nn, w);
                        int before = lvl * 100 + r * 10 + (nn ? 1 : 0), after = n[0] * 100 + n[1] * 10 + n[2];
                        assertNotEquals("level " + lvl + " rung " + r + " kind " + k + " nn " + nn + " " + w, before, after);
                        assertTrue(n[0] >= lvl && n[1] >= r && n[2] >= (nn ? 1 : 0));   // never back
                    }
    }

    // ------------------------------------------------------------------ the command

    @Test public void noNeonOnlyAddsTheFlagInFront() {
        String[] cmd = FfmpegCommands.renderClipSafe("/v.mp4", "/a.webm", 10, 20, "/c.ass", "/fonts", "/o.mp4", 720);
        String[] n = FfmpegCommands.noNeon(cmd);
        assertEquals(cmd.length + 2, n.length);
        assertEquals("-cpuflags", n[0]);
        assertEquals("-neon", n[1]);
        assertArrayEquals(cmd, Arrays.copyOfRange(n, 2, n.length));   // everything else byte-identical
        assertTrue(Arrays.asList(n).indexOf("-cpuflags") < Arrays.asList(n).indexOf("-i"));   // before the inputs
        assertArrayEquals(n, FfmpegCommands.noNeon(n));   // idempotent
        String[] safe = FfmpegCommands.cpuSafe(cmd);
        assertArrayEquals(safe, FfmpegCommands.noNeon(safe));   // the CPU-safe rung (-cpuflags 0) is not touched
        String[] nat = {RenderSafety.NATIVE_ARG, "{}"};
        assertArrayEquals(nat, FfmpegCommands.noNeon(nat));
        assertTrue(RenderSafety.commandNoNeon(n));
        assertTrue(RenderSafety.commandNoNeon(safe));
        assertFalse(RenderSafety.commandNoNeon(cmd));
    }

    @Test public void otherPhonesRunExactlyThe51Command() {
        String[] cmd = FfmpegCommands.renderClip("/v.mp4", "/a.webm", 5, 30, FfmpegCommands.Frame.FILL, "/c.ass", "/fonts", "/o.mp4", 720,
                FfmpegCommands.Encoder.X264, 6, null, false, null, 1080);
        assertSame(cmd, MediaRunner.prepare(c, cmd));
        assertFalse(RenderSafety.noNeon(c));
        String[] audio = FfmpegCommands.cutAudioCopy("/a.webm", 1, 2, "/o.m4a");
        RenderSafety.setRiskySocForTest(true);
        assertSame(audio, MediaRunner.prepare(c, audio));   // audio-only work is not touched even on the vivo
        // code 35 (5.3): the colour tag removed the crash's cause, so the vivo's chip family keeps NEON too (the crash
        // retry without NEON stays: aRiskyChipKeepsNeonAndItsFirstCrashRetriesWithoutNeon)
        assertSame(cmd, MediaRunner.prepare(c, cmd));
    }

    @Test public void riskyChipsAreTheHelioP35Family() {
        assertTrue(RenderSafety.riskySocName("MT6765"));
        assertTrue(RenderSafety.riskySocName("", "mt6765", "k65v1_64_bsp"));
        assertTrue(RenderSafety.riskySocName("Hardware\t: MT6765V/WB "));
        assertTrue(RenderSafety.riskySocName("MT6762G"));
        assertTrue(RenderSafety.riskySocName("MediaTek Helio P35"));
        assertTrue(RenderSafety.riskySocName("Helio G37"));
        for (String other : new String[]{"SM6225", "MT6768", "MT6769V/CU", "MT6833", "exynos850", "kirin710", "qcom", "robolectric", "ums9230", "", "Helio G85"})
            assertFalse(other, RenderSafety.riskySocName(other));
        assertFalse(RenderSafety.riskySocName((String) null));
        assertTrue(RenderSafety.riskyModel("vivo", "V2204"));
        assertFalse(RenderSafety.riskyModel("vivo", "V2207"));
        assertFalse(RenderSafety.riskyModel("OPPO", "V2204"));
        assertFalse(RenderSafety.riskyModel(null, null));
    }

    @Test public void theBoxIsNotAHelioP35() {
        RenderSafety.setRiskySocForTest(null);   // read it for real: Robolectric's Build + this machine's /proc/cpuinfo
        assertFalse(RenderSafety.riskySoc());
        assertTrue(RenderSafety.chipLine(), RenderSafety.chipLine().contains("cores"));
        assertFalse(RenderSafety.chipLine().contains("NEON"));
    }

    // ------------------------------------------------------------------ the phone's memory

    @Test public void aCrashMarksThePhoneAndTheNextRenderIsTheSameWithoutNeon() {
        String[] cmd = FfmpegCommands.renderClipSafe("/v.mp4", "/a.webm", 10, 20, "/c.ass", "/fonts", "/o.mp4", 720);
        int sBefore = RenderSafety.state(c);
        int level = RenderSafety.noteCrash(c, RenderSafety.K_SOFTWARE, VIVO_WHERE, cmd);
        assertEquals(RenderSafety.FULL, level);
        assertEquals(RenderSafety.R_NORMAL, RenderSafety.rung(c));
        assertTrue(RenderSafety.noNeon(c));
        assertNotEquals(sBefore, RenderSafety.state(c));   // Pipeline renders again (the path really changed)
        assertArrayEquals(FfmpegCommands.noNeon(cmd), MediaRunner.prepare(c, cmd));
        // the second crash (already without NEON) goes down the 5.1 ladder
        RenderSafety.noteCrash(c, RenderSafety.K_SOFTWARE, VIVO_WHERE, FfmpegCommands.noNeon(cmd));
        assertTrue(RenderSafety.rung(c) > RenderSafety.R_NORMAL);
        assertTrue(RenderSafety.noNeon(c));
    }

    @Test public void theMarkSurvivesAnAppUpdateButNotOtherFfmpegLibraries() {
        RenderSafety.noteCrash(c, RenderSafety.K_SOFTWARE, VIVO_WHERE, new String[]{"-i", "x", "-c:v", "libx264", "o.mp4"});
        // an app update: level / rung start again, the NEON mark stays (same FFmpeg libraries)
        Prefs.sp(c).edit().putInt(RenderSafety.K_BUILD, -7).commit();
        RenderSafety.resetForTest();
        RenderSafety.setRiskySocForTest(false);
        assertEquals(RenderSafety.R_NORMAL, RenderSafety.rung(c));
        assertTrue(RenderSafety.noNeon(c));
        assertTrue(RenderSafety.noNeonWhy(c).contains("crashed"));
        // other FFmpeg libraries: the mark does not count
        Prefs.sp(c).edit().putString(RenderSafety.K_NO_NEON_LIBS, "ffmpeg-kit-old").commit();
        RenderSafety.resetForTest();
        RenderSafety.setRiskySocForTest(false);
        assertFalse(RenderSafety.noNeon(c));
        assertTrue(RenderSafety.noNeonStored(true, RenderSafety.NEON_MARK_ID));
        assertFalse(RenderSafety.noNeonStored(false, RenderSafety.NEON_MARK_ID));
        // code 35: a 5.2 mark (libraries only, from the RGB detour 5.3 removed) no longer counts
        assertFalse(RenderSafety.noNeonStored(true, RenderSafety.FFMPEG_LIBS));
    }

    @Test public void aStalledCodecDoesNotMarkNeon() {
        RenderSafety.noteCrash(c, RenderSafety.K_SOFTWARE, "no progress for 90 s", new String[]{"-i", "x", "-c:v", "libx264", "o.mp4"});
        assertFalse(RenderSafety.noNeon(c));
    }

    @Test public void aRiskyChipKeepsNeonAndItsFirstCrashRetriesWithoutNeon() {
        // code 35 (5.3): the chip rule is off (NEON_OFF_FOR_RISKY_SOC); the after-crash safety net is the same as on any phone
        assertFalse(RenderSafety.NEON_OFF_FOR_RISKY_SOC);
        RenderSafety.setRiskySocForTest(true);
        assertFalse(RenderSafety.noNeon(c));
        assertEquals("", RenderSafety.noNeonWhy(c));
        String[] cmd = new String[]{"-i", "x", "-c:v", "libx264", "o.mp4"};
        int s0 = RenderSafety.state(c);
        RenderSafety.noteCrash(c, RenderSafety.K_SOFTWARE, VIVO_WHERE, cmd);
        assertNotEquals(s0, RenderSafety.state(c));
        assertEquals(RenderSafety.R_NORMAL, RenderSafety.rung(c));   // first: the same command without NEON
        assertTrue(RenderSafety.noNeon(c));
        assertArrayEquals(FfmpegCommands.noNeon(cmd), MediaRunner.prepare(c, cmd));
        // a second crash (already without NEON) goes down the 5.1 ladder
        RenderSafety.noteCrash(c, RenderSafety.K_SOFTWARE, VIVO_WHERE, FfmpegCommands.noNeon(cmd));
        assertTrue(RenderSafety.rung(c) > RenderSafety.R_NORMAL);
        assertTrue(RenderSafety.reportLine(c).contains("without NEON"));
    }

    // ------------------------------------------------------------------ the exact commands for the box's arm64 kit

    /**
     * Writes the commands 5.2 runs on the vivo (a Helio P35: NEON off from the first render) for the qemu kit
     * (build/v34_kit/*.args, one argument per line): safe mode and face tracking, x264 (the vivo's encoder) at 720p.
     */
    @Test public void writeKitCommands() throws Exception {
        RenderSafety.setRiskySocForTest(true);
        String v = "/tmp/k34kit/in/video.mp4", a = "/tmp/k34kit/in/audio.webm", ass = "/tmp/v32render/jre_11_1080.ass", fonts = "/tmp/k33/fonts";
        File dir = new File("build/v35_kit");
        dir.mkdirs();
        String[] safe = FfmpegCommands.renderClipSafe(v, a, 30, 3, ass, fonts, "/tmp/k34kit/out/OUT.mp4", 720);
        FaceTrack.Plan p = new FaceTrack.Plan();
        p.usable = true;
        p.outFps = 30;
        p.cropW = (1080 * 9.0 / 16.0) / 1920.0;
        p.cx = new double[90];
        for (int k = 0; k < p.cx.length; k++) p.cx[k] = 0.42 + 0.16 * k / (p.cx.length - 1);   // the speaker drifts right
        File cmd = new File(dir, "ft_0.cmd");
        try (FileOutputStream o = new FileOutputStream(cmd)) { o.write(p.sendcmd().getBytes(StandardCharsets.UTF_8)); }
        FfmpegCommands.Track tr = new FfmpegCommands.Track(cmd.getAbsolutePath(), p, 1080);
        String[] face = FfmpegCommands.renderClip(v, a, 30, 3, FfmpegCommands.Frame.FILL, ass, fonts, "/tmp/k34kit/out/OUT.mp4", 720,
                FfmpegCommands.Encoder.X264, 6, tr, false, null, 1080);
        String[] fill = FfmpegCommands.renderClip(v, a, 30, 3, FfmpegCommands.Frame.FILL, ass, fonts, "/tmp/k34kit/out/OUT.mp4", 720,
                FfmpegCommands.Encoder.X264, 6, null, false, null, 1080);
        String[] blur = FfmpegCommands.renderClip(v, a, 30, 3, FfmpegCommands.Frame.BLUR, ass, fonts, "/tmp/k34kit/out/OUT.mp4", 720,
                FfmpegCommands.Encoder.X264, 6, null, false, null, 1080);
        String[] fit = FfmpegCommands.renderClip(v, a, 30, 3, FfmpegCommands.Frame.FIT, ass, fonts, "/tmp/k34kit/out/OUT.mp4", 720,
                FfmpegCommands.Encoder.X264, 6, null, false, null, 1080);
        FfmpegCommands.Track tr1080 = new FfmpegCommands.Track(cmd.getAbsolutePath(), p, 1080);
        String[] face1080 = FfmpegCommands.renderClip(v, a, 30, 3, FfmpegCommands.Frame.FILL, ass, fonts, "/tmp/k34kit/out/OUT.mp4", 1080,
                FfmpegCommands.Encoder.X264, 6, tr1080, false, null, 1080);
        String[] safe1080 = FfmpegCommands.renderClipSafe(v, a, 30, 3, ass, fonts, "/tmp/k34kit/out/OUT.mp4", 1080);
        String[][] all = {safe, face, fill, blur, fit, face1080, safe1080};
        String[] names = {"safe", "face", "fill", "blur", "fit", "face1080", "safe1080"};
        for (int i = 0; i < all.length; i++) {
            String[] run = MediaRunner.prepare(c, all[i]);
            assertSame(all[i], run);   // code 35: NEON stays on, the colour-tagged command runs as built
            write(new File(dir, names[i] + "_53.args"), run);      // code 35: what 5.3 runs on the vivo (and every phone)
            write(new File(dir, names[i] + "_53noneon.args"), FfmpegCommands.noNeon(run));   // after a crash
        }
        assertTrue(Arrays.asList(face).toString(), Arrays.asList(face).contains("-filter_complex"));
    }

    private static void write(File f, String[] args) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(String.join("\n", args).getBytes(StandardCharsets.UTF_8)); }
    }
}
