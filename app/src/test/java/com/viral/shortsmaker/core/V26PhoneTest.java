package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** v26 (code 26): the per-phone encoder ladder, the one-time re-test of settings older versions turned down, guard learning. */
@RunWith(RobolectricTestRunner.class)
public class V26PhoneTest {
    Context ctx;

    @Before public void setUp() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
        Prefs.sp(ctx).edit().clear().apply();
        java.lang.reflect.Field f = Prefs.class.getDeclaredField("migrated26");
        f.setAccessible(true);
        f.set(null, false);
    }

    @After public void tearDown() { FfmpegCommands.planSource = null; }

    @Test public void settingsTurnedDownByOlderVersionsAreRetestedOnce() {
        Prefs.sp(ctx).edit().putInt("hw_tune_state", -1).putInt("hw_encode_state", -1).putInt("hi_src_fail", 2).apply();
        Prefs.migrateV26(ctx);
        assertEquals(0, Prefs.hwTuneState(ctx));
        assertEquals(0, Prefs.hwEncodeState(ctx));
        assertEquals(0, Prefs.sp(ctx).getInt("hi_src_fail", 0));
        // once only: a later real failure stays
        Prefs.setHwEncodeState(ctx, -1);
        Prefs.migrateV26(ctx);
        assertEquals(-1, Prefs.hwEncodeState(ctx));
    }

    @Test public void oneBadMomentNoLongerDecidesThePhonesSettings() {
        assertFalse("an unclear failure (e.g. a dropped stream) counts 1 point", Prefs.hwLevelFault(ctx, 0, false));
        assertEquals(0, Prefs.hwTuneLevel(ctx));
        assertFalse("one clear encoder error: 3 points", Prefs.hwLevelFault(ctx, 0, true));
        assertEquals(0, Prefs.hwTuneLevel(ctx));
        assertTrue(Prefs.hwLevelFault(ctx, 0, true));
        assertEquals(1, Prefs.hwTuneLevel(ctx));
        // a level that works forgets its points
        Prefs.hwLevelFault(ctx, 1, true);
        Prefs.hwLevelOk(ctx, 1);
        assertFalse(Prefs.hwLevelFault(ctx, 1, true));
        assertEquals(1, Prefs.hwTuneLevel(ctx));
        // even the bare settings fail for real twice: x264 on this phone
        Prefs.hwLevelFault(ctx, 2, true);
        assertTrue(Prefs.hwLevelFault(ctx, 2, true));
        assertEquals(-1, Prefs.hwEncodeState(ctx));
    }

    @Test public void encoderChoicePerPhone() {
        Pipeline p = new Pipeline(ctx);
        FfmpegCommands.planSource = (w, h) -> HwEncode.choose(V26Test.qualcomm(), w, h);
        assertEquals(FfmpegCommands.Encoder.HW, p.hwEncoder(null, 1080));
        assertEquals(FfmpegCommands.Encoder.HW_BASIC, p.hwEncoder(FfmpegCommands.Encoder.HW_BASIC, 1080));   // never above what failed in this short
        assertEquals(FfmpegCommands.Encoder.X264, p.hwEncoder(FfmpegCommands.Encoder.X264, 1080));
        Prefs.sp(ctx).edit().putInt("hw_tune_level", 1).apply();
        assertEquals(FfmpegCommands.Encoder.HW_STD, p.hwEncoder(null, 1080));
        // a phone whose encoder only takes landscape sizes: x264 for 9:16, hardware for 16:9
        FfmpegCommands.planSource = (w, h) -> HwEncode.choose(V26Test.landscapeOnly(), w, h);
        assertEquals(FfmpegCommands.Encoder.X264, p.hwEncoder(null, 1080));
        assertEquals(FfmpegCommands.Encoder.HW_STD, p.hwEncoder(null, FfmpegCommands.Aspect.LANDSCAPE.spec(1080)));
        // emulator / software-only phone
        FfmpegCommands.planSource = (w, h) -> HwEncode.choose(V26Test.softwareOnly(), w, h);
        assertEquals(FfmpegCommands.Encoder.X264, p.hwEncoder(null, 720));
    }

    @Test public void guardLearnsAPoorEncoder() {
        Pipeline p = new Pipeline(ctx);
        FfmpegCommands.planSource = (w, h) -> HwEncode.choose(V26Test.mediatekOmx(), w, h);
        assertEquals(FfmpegCommands.Encoder.HW, p.hwEncoder(null, 1080));
        Prefs.guardResult(ctx, false);
        Prefs.guardResult(ctx, true);
        assertFalse(Prefs.hwQualityPoor(ctx));
        Prefs.guardResult(ctx, true);
        assertTrue(Prefs.hwQualityPoor(ctx));
        assertEquals("final shorts on x264 once the encoder starved the picture twice", FfmpegCommands.Encoder.X264, p.hwEncoder(null, 1080));
    }
}
