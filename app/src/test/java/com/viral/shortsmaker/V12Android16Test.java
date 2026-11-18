package com.viral.shortsmaker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/** v12: every screen on Android 16 (API 36). Robolectric needs Java 21 for this SDK: run with -PtestJava=<jdk21>/bin/java. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class V12Android16Test {
    private final V11ScreenTest v11 = new V11ScreenTest();

    @Before public void reset() { v11.reset(); }

    @Test @Config(sdk = 36, qualifiers = "w412dp-h915dp-xxhdpi") public void a16_f130() throws Exception { v11.allScreens("v12_a16_f130", 1.3f); }
    @Test @Config(sdk = 36, qualifiers = "w360dp-h800dp-xhdpi") public void a16_small_f100() throws Exception { v11.allScreens("v12_a16_small_f100", 1.0f); }
    @Test @Config(sdk = 36, qualifiers = "w412dp-h915dp-xxhdpi") public void faceTrack_a16_f130() throws Exception { V13ScreenTest t = new V13ScreenTest(); t.resetAll(); t.check("a16_f130", 1.3f); }
    @Test @Config(sdk = 36, qualifiers = "w360dp-h800dp-xhdpi") public void faceTrack_a16_small_f100() throws Exception { V13ScreenTest t = new V13ScreenTest(); t.resetAll(); t.check("a16_small_f100", 1.0f); }
    @Test @Config(sdk = 36, qualifiers = "w412dp-h915dp-xxhdpi") public void format_a16_f130() throws Exception { V14ScreenTest t = new V14ScreenTest(); t.resetAll(); t.check("a16_f130", 1.3f); }
    @Test @Config(sdk = 36, qualifiers = "w360dp-h800dp-xhdpi") public void format_a16_small_f100() throws Exception { V14ScreenTest t = new V14ScreenTest(); t.resetAll(); t.check("a16_small_f100", 1.0f); }
    @Test @Config(sdk = 36, qualifiers = "w412dp-h915dp-xxhdpi") public void studio_square_a16() throws Exception { studioFormat("SQUARE", 1f); }
    @Test @Config(sdk = 36, qualifiers = "w412dp-h915dp-xxhdpi") public void studio_wide_a16() throws Exception { studioFormat("LANDSCAPE", 16f / 9f); }
    @Test @Config(sdk = 34, qualifiers = "w360dp-h800dp-xhdpi") public void studio_square_a14() throws Exception { studioFormat("SQUARE", 1f); }

    /** v14: Caption Studio edits the caption box on the project's own format frame */
    void studioFormat(String aspect, float want) throws Exception {
        org.robolectric.RuntimeEnvironment.setFontScale(1f);
        v11.project(3);
        com.viral.shortsmaker.core.JobManager.get().project.aspect = aspect;
        CaptionStudioActivity a = v11.studio(0);
        V11ScreenTest.idle(300);
        V11ScreenTest.shotTo(a, "v14", "studio_" + aspect.toLowerCase());
        com.viral.shortsmaker.captions.CaptionPreviewView pv = a.findViewById(R.id.preview);
        java.lang.reflect.Field f = com.viral.shortsmaker.captions.CaptionPreviewView.class.getDeclaredField("fr");
        f.setAccessible(true);
        android.graphics.RectF fr = (android.graphics.RectF) f.get(pv);
        org.junit.Assert.assertEquals(aspect, want, fr.width() / fr.height(), 0.02);
        org.junit.Assert.assertEquals(want, pv.getCaptionLayout().aspect, 1e-4);
        org.junit.Assert.assertTrue(V11ScreenTest.clipped(a.getWindow().getDecorView()).isEmpty());
    }
    @Test @Config(sdk = 36, qualifiers = "w412dp-h915dp-xxhdpi") public void studio_a16_f130() throws Exception { v11.checkStudio("v12_studio_a16_f130", 1.3f); }
}
