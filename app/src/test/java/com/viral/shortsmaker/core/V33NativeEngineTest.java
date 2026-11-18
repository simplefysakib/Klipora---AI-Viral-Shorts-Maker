package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import com.viral.shortsmaker.captions.Word;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** code 33 (5.1): the pure parts of the backup video engine (the GPU / MediaCodec part needs a phone) */
@RunWith(RobolectricTestRunner.class)
public class V33NativeEngineTest {
    @Test public void fillCropIsTheFfmpegCrop() {
        // 1920x1080 source, 9:16: crop w = min(1920, trunc(1080*9/16/2)*2) = 606, full height, centred
        float[] r = NativeEngine.fillRect(1920, 1080, 9.0 / 16);
        assertEquals(606f, r[2], 0.01);
        assertEquals(1080f, r[3], 0.01);
        assertEquals((1920 - 606) / 2f, r[0], 0.01);
        float[] c = NativeEngine.camRect(1920, 1080, 9.0 / 16, 1f);
        assertEquals(1920 - 606, c[0], 0.01);
        float[] f = NativeEngine.fitRect(1920, 1080, 1080, 1920);
        assertEquals(1080f, f[2], 0.01);
        assertEquals(607.5f, f[3], 0.01);
        assertEquals((1920 - 607.5f) / 2f, f[1], 0.01);
    }

    @Test public void sharpenOnlyWhenEnlarged() {
        assertEquals(0f, NativeEngine.sharpAmount(1920, 1920), 0);
        assertTrue(NativeEngine.sharpAmount(1080, 1920) > 0);
        assertTrue(NativeEngine.sharpAmount(720, 1920) > NativeEngine.sharpAmount(1500, 1920));
    }

    @Test public void trackLinesRoundTrip() {
        float[][] t = NativeEngine.parseTrack("c 0.25000\ns 0.5 0.5 0.1 0.2 0.9 0.8\nc 1.0\n");
        assertEquals(3, t.length);
        assertEquals(0f, t[0][0], 0); assertEquals(0.25f, t[0][1], 1e-6);
        assertEquals(1f, t[1][0], 0); assertEquals(0.9f, t[1][5], 1e-6);
    }

    @Test public void monoAudioBecomesStereo48k() {
        int sr = 24000, n = 2400;   // 0.1 s mono at 24 kHz
        ByteBuffer b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) b.putShort((short) 1000);
        short[] st = NativeEngine.toStereo48k(b.array(), sr, 1, 4800);
        assertEquals(9600, st.length);
        assertEquals(1000, st[100]); assertEquals(1000, st[101]);   // both channels
        NativeEngine.fadeIn(st, 48000, 0.08);
        assertEquals(0, st[0]);
        assertTrue(st[2 * 1000] < 1000 && st[2 * 1000] > 0);
        assertEquals(1000, st[2 * 4000]);   // past 80 ms: untouched
    }

    @Test public void specCarriesTheEdit() throws Exception {
        JSONObject o = NativeEngine.spec("/v.mp4", "/a.webm", 12.5, 30, "/o.mp4", 720, FfmpegCommands.Frame.FILL, 9.0 / 16, 8_000_000L, null);
        assertEquals(720, o.getInt("w"));
        assertEquals(1280, o.getInt("h"));
        assertEquals("FILL", o.getString("frame"));
        assertEquals(12.5, o.getDouble("ss"), 0);
        assertEquals(RenderSafety.K_NATIVE, RenderSafety.kindOf(new String[]{RenderSafety.NATIVE_ARG, o.toString()}));
        assertEquals(1, NativeEngine.wordsFrom(NativeEngine.wordsJson(Arrays.asList(new Word("HEY", 0.1, 0.4)))).size());
    }
}
