package com.viral.shortsmaker;

import static org.junit.Assert.*;

import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.viral.shortsmaker.core.Prefs;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/** v13 screen checks (JVM-simulated Android, not real phones): the AI face tracking switch on the create screen. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class V13ScreenTest {
    private final V11ScreenTest v11 = new V11ScreenTest();

    @Before public void reset() { v11.reset(); }
    void resetAll() { v11.reset(); }

    private MainActivity home(float font) {
        RuntimeEnvironment.setFontScale(font);
        MainActivity m = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((TextView) m.findViewById(R.id.tvVideoTitle)).setText("Ananya Panday On Childhood, Relationship, Bollywood, SRK & celebrity Life");
        m.findViewById(R.id.layoutVideoInfo).setVisibility(View.VISIBLE);
        m.findViewById(R.id.cardControls).setVisibility(View.VISIBLE);
        V11ScreenTest.idle(400);
        return m;
    }

    void check(String tag, float font) throws Exception {
        // v14: tracking is premium; with the 24 h pass active it is on by default exactly as in 2.4
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit().putLong("premium_unlock_time", System.currentTimeMillis()).commit();
        MainActivity m = home(font);
        MaterialSwitch sw = m.findViewById(R.id.switchFaceTrack);
        TextView sum = m.findViewById(R.id.tvFaceTrackSummary);
        assertTrue("on by default", sw.isChecked());
        assertTrue(sum.getText().toString().startsWith("On"));
        View row = m.findViewById(R.id.rowFaceTrack);
        // scroll the row into view for the screenshot
        View p = row;
        while (p.getParent() instanceof View) {
            p = (View) p.getParent();
            if (p instanceof NestedScrollView) { ((NestedScrollView) p).scrollTo(0, Math.max(0, top(row, p) - 300)); break; }
            if (p instanceof ScrollView) { ((ScrollView) p).scrollTo(0, Math.max(0, top(row, p) - 300)); break; }
        }
        V11ScreenTest.idle(300);
        V11ScreenTest.shotTo(m, "v13", tag + "_on");
        assertTrue(tag + " " + V11ScreenTest.clipped(m.getWindow().getDecorView()), V11ScreenTest.clipped(m.getWindow().getDecorView()).isEmpty());
        assertTrue(row.getHeight() >= sw.getHeight());
        // tapping the row turns it off, remembered as a setting
        row.performClick();
        V11ScreenTest.idle(200);
        assertFalse(sw.isChecked());
        assertFalse(Prefs.faceTrack(ApplicationProvider.getApplicationContext()));
        assertTrue(sum.getText().toString().startsWith("Off"));
        V11ScreenTest.shotTo(m, "v13", tag + "_off");
        assertTrue(V11ScreenTest.clipped(m.getWindow().getDecorView()).isEmpty());
        // a new screen remembers OFF
        MainActivity m2 = home(font);
        assertFalse(((MaterialSwitch) m2.findViewById(R.id.switchFaceTrack)).isChecked());
    }

    private static int top(View v, View ancestor) {
        int y = 0; View c = v;
        while (c != ancestor && c.getParent() instanceof View) { y += c.getTop(); c = (View) c.getParent(); }
        return y;
    }

    @Test @Config(sdk = 26, qualifiers = "w320dp-h568dp-mdpi") public void a8_small_f130() throws Exception { check("a8_small_f130", 1.3f); }
    @Test @Config(sdk = 28, qualifiers = "w360dp-h740dp-xhdpi") public void a9_f130() throws Exception { check("a9_f130", 1.3f); }
    @Test @Config(sdk = 30, qualifiers = "w360dp-h800dp-xhdpi") public void a11_f100() throws Exception { check("a11_f100", 1.0f); }
    @Test @Config(sdk = 33, qualifiers = "w393dp-h852dp-xxhdpi") public void a13_f130() throws Exception { check("a13_f130", 1.3f); }
    @Test @Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi") public void a14_f100() throws Exception { check("a14_f100", 1.0f); }
    @Test @Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi") public void a15_f130() throws Exception { check("a15_f130", 1.3f); }
    @Test @Config(sdk = 35, qualifiers = "w600dp-h960dp-xhdpi") public void a15_tablet_f100() throws Exception { check("a15_tablet_f100", 1.0f); }
}
