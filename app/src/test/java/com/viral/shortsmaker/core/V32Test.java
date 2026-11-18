package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** code 32 (5.0) overnight audit: Android 7 file reads, the :render watchdog / crash rules. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V32Test {

    // ---------------------------------------------------------------- F1: no java.nio.file on Android 7 (minSdk 24)
    @Test public void readAllReadsWholeFiles() throws Exception {
        File d = new File(System.getProperty("java.io.tmpdir"), "v32io");
        d.mkdirs();
        File empty = new File(d, "e.bin"); new FileOutputStream(empty).close();
        assertEquals(0, IoUtil.readAll(empty).length);
        File big = new File(d, "b.bin");
        byte[] want = new byte[300_001];
        for (int i = 0; i < want.length; i++) want[i] = (byte) (i * 31);
        try (FileOutputStream o = new FileOutputStream(big)) { o.write(want); }
        assertArrayEquals(want, IoUtil.readAll(big));
        try { IoUtil.readAll(new File(d, "missing")); fail(); } catch (java.io.IOException expected) {}
        empty.delete(); big.delete(); d.delete();
    }

    @Test public void noApi26OnlyFileApisInTheApp() throws Exception {
        File src = new File("src/main/java");
        if (!src.isDirectory()) src = new File("app/src/main/java");
        assertTrue(src.isDirectory());
        List<String> bad = new ArrayList<>();
        scan(src, bad);
        assertTrue("java.nio.file / toPath() need Android 8; minSdk is 24: " + bad, bad.isEmpty());
    }

    private static void scan(File f, List<String> bad) throws Exception {
        File[] fs = f.listFiles();
        if (fs == null) return;
        for (File x : fs) {
            if (x.isDirectory()) { scan(x, bad); continue; }
            if (!x.getName().endsWith(".java") || x.getName().equals("IoUtil.java")) continue;
            String s = new String(IoUtil.readAll(x), StandardCharsets.UTF_8);
            if (s.contains("java.nio.file") || s.contains(".toPath()")) bad.add(x.getName());
        }
    }

    // ---------------------------------------------------------------- F5 / F6: the render process
    @Test public void stuckOnlyAfterFourMinutesWithoutProgress() {
        long now = 10_000_000L;
        assertFalse(MediaRunner.Remote.stalled(now - 1_000, now));
        assertFalse(MediaRunner.Remote.stalled(now - 239_000, now));
        assertTrue(MediaRunner.Remote.stalled(now - 241_000, now));
    }

    @Test public void crashRulesForRenderProcessEnds() {
        // recorded reasons decide at once
        assertTrue(MediaRunner.Remote.treatAsCrash("CRASH_NATIVE, signal 11", 0));
        assertTrue(MediaRunner.Remote.treatAsCrash("CRASH", 0));
        assertFalse(MediaRunner.Remote.treatAsCrash("LOW_MEMORY", 0));
        assertFalse(MediaRunner.Remote.treatAsCrash("LOW_MEMORY", 7));
        // no record (Android 7-10): the first end is retried on the same path, the second in a row steps down
        assertFalse(MediaRunner.Remote.treatAsCrash("", 1));
        assertTrue(MediaRunner.Remote.treatAsCrash("", 2));
        assertTrue(MediaRunner.Remote.treatAsCrash(null, 3));
    }

    @Test public void networkInputIsNotTheCodecsFault() {
        assertTrue(MediaRunner.Remote.networkInput(new String[]{"-y", "-ss", "1", "-i", "https://rr1.googlevideo.com/x", "out.mp4"}));
        assertFalse(MediaRunner.Remote.networkInput(new String[]{"-y", "-i", "/data/user/0/x/seg_0.mp4", "out.mp4"}));
        assertFalse(MediaRunner.Remote.networkInput(new String[]{"-y", "-i"}));
    }
}
