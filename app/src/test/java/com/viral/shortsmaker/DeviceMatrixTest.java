package com.viral.shortsmaker;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.viral.shortsmaker.captions.CaptionPreviewView;
import com.viral.shortsmaker.core.JobManager;
import com.viral.shortsmaker.core.Prefs;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;

/**
 * v10 device matrix (JVM-simulated Android, not real phones): every screen is created, laid out and drawn, and the
 * caption box is dragged / toggled, on old and new Android versions and on small, normal, large and tablet screens.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class DeviceMatrixTest {
    private static final File OUT = new File(System.getProperty("shotDir", "build/shots"), "matrix");

    @Before public void reset() {
        JobManager.get().removeAllListeners();
        JobManager.get().project = null;
        JobManager.get().reset();
    }

    private static void idle(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    private static void shot(Activity a, String name) throws Exception {
        idle(800);
        View root = a.getWindow().getDecorView();
        assertTrue(name + " has no size", root.getWidth() > 0 && root.getHeight() > 0);
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        OUT.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(OUT, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, fo); }
    }

    private static void keys(boolean on) {
        android.content.Context c = ApplicationProvider.getApplicationContext();
        Prefs.sp(c).edit().clear().apply();
        if (on) {
            Prefs.setKeys(c, "AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY12", "gsk_DUMMYDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY1234");
            Prefs.sp(c).edit().putBoolean("is_onboarding_done", true).apply();
        }
    }

    static void touch(View v, int action, float x, float y, long t) {
        MotionEvent e = MotionEvent.obtain(0, t, action, x, y, 0);
        v.dispatchTouchEvent(e);
        e.recycle();
    }

    private void runAll(String tag) throws Exception {
        // 1. first launch: onboarding, keys typed -> Next enabled
        keys(false);
        OnboardingActivity ob = Robolectric.buildActivity(OnboardingActivity.class).setup().get();
        ((EditText) ob.findViewById(R.id.etGeminiKey)).setText("AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY12");
        ((EditText) ob.findViewById(R.id.etGroqKey)).setText("gsk_DUMMYDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY1234");
        idle(200);
        assertTrue(ob.findViewById(R.id.btnNext).isEnabled());
        shot(ob, tag + "_1_onboarding");
        ob.findViewById(R.id.btnNext).performClick();
        assertEquals("AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY12", Prefs.geminiKey(ApplicationProvider.getApplicationContext()));

        // 2. home
        keys(true);
        MainActivity m = Robolectric.buildActivity(MainActivity.class).setup().get();
        shot(m, tag + "_2_home");
        assertFalse(m.isFinishing());

        // 3. Caption Studio: extras switches, drag the caption box, pinch-free resize via corner
        CaptionStudioActivity cs = Robolectric.buildActivity(CaptionStudioActivity.class, new Intent()).setup().get();
        idle(300);
        ((MaterialSwitch) cs.findViewById(R.id.switchUpper)).setChecked(false);
        ((MaterialSwitch) cs.findViewById(R.id.switchProgress)).setChecked(true);
        CaptionPreviewView pv = cs.findViewById(R.id.preview);
        assertNotNull(pv);
        idle(300);
        float cx0 = pv.getCaptionLayout().cx, cy0 = pv.getCaptionLayout().cy;
        float x = pv.getWidth() / 2f, y = pv.getHeight() * 0.72f;
        long t = 1000;
        touch(pv, MotionEvent.ACTION_DOWN, x, y, t);
        for (int i = 1; i <= 12; i++) touch(pv, MotionEvent.ACTION_MOVE, x, y - i * pv.getHeight() * 0.02f, t += 16);
        touch(pv, MotionEvent.ACTION_UP, x, y - 12 * pv.getHeight() * 0.02f, t += 16);
        idle(100);
        assertTrue(tag + ": box moved up " + cy0 + " -> " + pv.getCaptionLayout().cy + " view " + pv.getWidth() + "x" + pv.getHeight() + " shown " + pv.isShown(), pv.getCaptionLayout().cy < cy0 - 0.1f);
        assertEquals(cx0, pv.getCaptionLayout().cx, 0.02f);
        shot(cs, tag + "_3_studio");
    }

    static CaptionPreviewView findPreview(View v) {
        if (v instanceof CaptionPreviewView) return (CaptionPreviewView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { CaptionPreviewView p = findPreview(g.getChildAt(i)); if (p != null && p.getWidth() > 0) return p; }
        }
        return null;
    }

    @Test @Config(sdk = 26, qualifiers = "w320dp-h568dp-mdpi") public void android8_smallPhone() throws Exception { runAll("api26_small"); }
    @Test @Config(sdk = 26, qualifiers = "w411dp-h891dp-xxhdpi") public void android8_largePhone() throws Exception { runAll("api26_large"); }
    @Test @Config(sdk = 30, qualifiers = "w360dp-h740dp-xhdpi") public void android11_normalPhone() throws Exception { runAll("api30_normal"); }
    @Test @Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi") public void android14_normalPhone() throws Exception { runAll("api34_normal"); }
    @Test @Config(sdk = 34, qualifiers = "w800dp-h1280dp-mdpi") public void android14_tablet() throws Exception { runAll("api34_tablet"); }
    @Test @Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi") public void android15_largePhone() throws Exception { runAll("api35_large"); }
}
