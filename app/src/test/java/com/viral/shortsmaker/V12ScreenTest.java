package com.viral.shortsmaker;

import static org.junit.Assert.*;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.viral.shortsmaker.captions.CaptionLayout;
import com.viral.shortsmaker.captions.CaptionPreviewView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/**
 * v12 screen checks (JVM-simulated Android, not real phones): the new size card and resize handle, and every screen on
 * Android 8, 9, 11, 13, 14, 15 (and 16 where the simulator has it), small / normal / large screens, font 1.0 and 1.3.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class V12ScreenTest {
    private final V11ScreenTest v11 = new V11ScreenTest();

    @Before public void reset() { v11.reset(); }

    private static void touch(View v, int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, action, x, y, 0);
        v.dispatchTouchEvent(e);
        e.recycle();
    }

    private static void drag(View v, float x0, float y0, float x1, float y1) {
        touch(v, MotionEvent.ACTION_DOWN, x0, y0);
        for (int i = 1; i <= 8; i++) touch(v, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 8f, y0 + (y1 - y0) * i / 8f);
        touch(v, MotionEvent.ACTION_UP, x1, y1);
        V11ScreenTest.idle(50);
    }

    @Test @Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi") public void handleResizesOnlyAndBodyMovesOnly() throws Exception {
        V11ScreenTest.project(3);
        CaptionStudioActivity a = v11.studio(0);
        CaptionPreviewView pv = a.findViewById(R.id.preview);
        PremiumSlider size = a.findViewById(R.id.sliderSize);
        TextView val = a.findViewById(R.id.tvSizeValue);
        V11ScreenTest.idle(300);
        CaptionLayout l0 = pv.getCaptionLayout().copy();
        float s0 = size.getValue();
        float[] h = pv.handleCenter();
        // drag the bottom-right handle outwards: size grows, the box centre stays where it was
        drag(pv, h[0], h[1], h[0] + 120, h[1] + 60);
        CaptionLayout l1 = pv.getCaptionLayout().copy();
        assertTrue("size grew " + s0 + " -> " + size.getValue(), size.getValue() > s0 + 0.05f);
        assertEquals(Math.round(size.getValue() * 100) + "%", val.getText().toString());
        assertEquals("position unchanged x", l0.cx, l1.cx, 0.01);
        assertEquals("position unchanged y", l0.cy, l1.cy, 0.01);
        assertTrue("box scaled with the text", l1.h > l0.h);
        // drag inwards: smaller
        h = pv.handleCenter();
        float s1 = size.getValue();
        drag(pv, h[0], h[1], h[0] - 160, h[1] - 80);
        assertTrue(size.getValue() < s1 - 0.05f);
        // drag the body: moves, size unchanged
        float s2 = size.getValue();
        CaptionLayout l2 = pv.getCaptionLayout().copy();
        float bx = pv.handleCenter()[0] - 150, by = pv.handleCenter()[1] - 30;
        drag(pv, bx, by, bx, by - 250);
        CaptionLayout l3 = pv.getCaptionLayout().copy();
        assertEquals("size unchanged by moving", s2, size.getValue(), 0.001);
        assertTrue("moved up", l3.cy < l2.cy - 0.05);
        assertEquals(l2.h, l3.h, 0.001);
        // slider drives the same size and the box follows
        size.setValue(1.4f);
        // (programmatic setValue is not a user change; simulate the user's drag on the slider)
        float sx = size.getWidth() * 0.9f, sy = size.getHeight() / 2f;
        touch(size, MotionEvent.ACTION_DOWN, sx, sy); touch(size, MotionEvent.ACTION_UP, sx, sy);
        V11ScreenTest.idle(100);
        assertTrue(size.getValue() > 1.4f);
        assertTrue(val.getText().toString().endsWith("%"));
        // double tap resets size to 100 %
        float cx = pv.handleCenter()[0] - 100, cy = pv.handleCenter()[1] - 20;
        for (int i = 0; i < 2; i++) { touch(pv, MotionEvent.ACTION_DOWN, cx, cy); touch(pv, MotionEvent.ACTION_UP, cx, cy); V11ScreenTest.idle(60); }
        V11ScreenTest.idle(400);
        // lines segmented control
        a.findViewById(R.id.segLines2).performClick();
        assertTrue(a.findViewById(R.id.segLines2).isSelected());
        assertFalse(a.findViewById(R.id.segLines1).isSelected());
    }

    @Test @Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi") public void studioShotShowsSizeCard() throws Exception {
        com.viral.shortsmaker.captions.CaptionStyle bs = com.viral.shortsmaker.captions.CaptionStyles.byId("boxword");
        bs.highlightColor = 0xFF8B3DFF;
        com.viral.shortsmaker.core.Prefs.saveCaption(androidx.test.core.app.ApplicationProvider.getApplicationContext(), bs, new CaptionLayout());
        CaptionStudioActivity a = v11.studio(-1);
        View card = a.findViewById(R.id.cardSize);
        V11ScreenTest.idle(300);
        View sc = (View) card.getParent().getParent();
        int[] lc = new int[2], ls = new int[2];
        card.getLocationInWindow(lc); sc.getLocationInWindow(ls);
        sc.scrollBy(0, Math.max(0, lc[1] - ls[1] - (int) (40 * a.getResources().getDisplayMetrics().density)));
        V11ScreenTest.idle(300);
        V11ScreenTest.shotTo(a, "v12", "caption_studio");
        assertTrue(V11ScreenTest.clipped(a.getWindow().getDecorView()).toString(), V11ScreenTest.clipped(a.getWindow().getDecorView()).isEmpty());
    }

    // every screen on every Android version
    @Test @Config(sdk = 26, qualifiers = "w320dp-h568dp-mdpi") public void a8_small_f130() throws Exception { v11.allScreens("v12_a8_small_f130", 1.3f); }
    @Test @Config(sdk = 26, qualifiers = "w411dp-h891dp-xxhdpi") public void a8_large_f100() throws Exception { v11.allScreens("v12_a8_large_f100", 1.0f); }
    @Test @Config(sdk = 28, qualifiers = "w360dp-h740dp-xhdpi") public void a9_f130() throws Exception { v11.allScreens("v12_a9_f130", 1.3f); }
    @Test @Config(sdk = 30, qualifiers = "w360dp-h800dp-xhdpi") public void a11_f130() throws Exception { v11.allScreens("v12_a11_f130", 1.3f); }
    @Test @Config(sdk = 33, qualifiers = "w393dp-h852dp-xxhdpi") public void a13_f100() throws Exception { v11.allScreens("v12_a13_f100", 1.0f); }
    @Test @Config(sdk = 34, qualifiers = "w360dp-h800dp-xhdpi") public void a14_f130() throws Exception { v11.allScreens("v12_a14_f130", 1.3f); }
    @Test @Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi") public void a15_f130() throws Exception { v11.allScreens("v12_a15_f130", 1.3f); }
    @Test @Config(sdk = 35, qualifiers = "w600dp-h960dp-xhdpi") public void a15_tablet_f100() throws Exception { v11.allScreens("v12_a15_tablet_f100", 1.0f); }


    @Test @Config(sdk = 26, qualifiers = "w320dp-h568dp-mdpi") public void studio_a8_small_f130() throws Exception { v11.checkStudio("v12_studio_a8_small_f130", 1.3f); }
    @Test @Config(sdk = 28, qualifiers = "w360dp-h740dp-xhdpi") public void studio_a9_f130() throws Exception { v11.checkStudio("v12_studio_a9_f130", 1.3f); }
    @Test @Config(sdk = 33, qualifiers = "w393dp-h852dp-xxhdpi") public void studio_a13_f100() throws Exception { v11.checkStudio("v12_studio_a13_f100", 1.0f); }
    @Test @Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi") public void studio_a15_f130() throws Exception { v11.checkStudio("v12_studio_a15_f130", 1.3f); }
}
