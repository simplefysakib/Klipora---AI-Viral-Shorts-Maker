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

/** v14 screen checks (JVM-simulated Android, not real phones): Format chips, no Auto count, premium face tracking. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class V14ScreenTest {
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
        android.content.Context c = ApplicationProvider.getApplicationContext();
        Prefs.sp(c).edit().putLong("premium_unlock_time", 0).putInt("face_track_credits", 0).commit();
        MainActivity m = home(font);
        // format chips: 9:16 by default, the framing label follows the choice, the choice is remembered
        com.google.android.material.chip.ChipGroup g = m.findViewById(R.id.chipGroupAspect);
        assertEquals(R.id.chipAspect916, g.getCheckedChipId());
        TextView fl = m.findViewById(R.id.tvFrameLabel);
        assertEquals("9:16 framing", fl.getText().toString());
        // number of shorts: 1-5 only, no Auto
        assertEquals(View.GONE, m.findViewById(R.id.chipCountAuto).getVisibility());
        // face tracking locked: switch off, lock shown, hint says podcasts + premium
        MaterialSwitch sw = m.findViewById(R.id.switchFaceTrack);
        TextView sum = m.findViewById(R.id.tvFaceTrackSummary);
        if (!Prefs.PREMIUM_EDITION) {   // premium edition: face tracking is never locked (PremiumEditionTest)
            assertFalse(sw.isChecked());
            assertEquals(View.VISIBLE, m.findViewById(R.id.ivFaceLock).getVisibility());
            assertTrue(sum.getText().toString(), sum.getText().toString().contains("Podcasts only"));
        } else assertEquals(View.GONE, m.findViewById(R.id.ivFaceLock).getVisibility());
        View row = m.findViewById(R.id.rowFaceTrack);
        View p = row;
        while (p.getParent() instanceof View) {
            p = (View) p.getParent();
            if (p instanceof NestedScrollView) { ((NestedScrollView) p).scrollTo(0, Math.max(0, top(g, p) - 200)); break; }
            if (p instanceof ScrollView) { ((ScrollView) p).scrollTo(0, Math.max(0, top(g, p) - 200)); break; }
        }
        V11ScreenTest.idle(300);
        V11ScreenTest.shotTo(m, "v14", tag + "_916_locked");
        assertTrue(tag + " " + V11ScreenTest.clipped(m.getWindow().getDecorView()), V11ScreenTest.clipped(m.getWindow().getDecorView()).isEmpty());
        // tapping the locked row asks for the ad, the switch stays off
        if (!Prefs.PREMIUM_EDITION) {
            row.performClick();
            V11ScreenTest.idle(200);
            assertFalse(sw.isChecked());
        }
        // 1:1 and 16:9
        m.findViewById(R.id.chipAspect11).performClick();
        V11ScreenTest.idle(200);
        assertEquals("1:1 framing", fl.getText().toString());
        assertEquals(com.viral.shortsmaker.core.FfmpegCommands.Aspect.SQUARE, Prefs.aspect(c));
        m.findViewById(R.id.chipAspect169).performClick();
        V11ScreenTest.idle(200);
        assertEquals("16:9 framing", fl.getText().toString());
        assertTrue(sum.getText().toString(), sum.getText().toString().contains("16:9"));
        // with one ad credit: unlocked, can be switched on
        Prefs.addFaceTrackCredit(c);
        m.findViewById(R.id.chipAspect11).performClick();
        sw.setChecked(true);
        V11ScreenTest.idle(200);
        assertTrue(sw.isChecked());
        assertEquals(View.GONE, m.findViewById(R.id.ivFaceLock).getVisibility());
        assertTrue(sum.getText().toString(), sum.getText().toString().startsWith("On for podcasts"));
        V11ScreenTest.shotTo(m, "v14", tag + "_11_unlocked");
        assertTrue(V11ScreenTest.clipped(m.getWindow().getDecorView()).isEmpty());
        // a new screen remembers 1:1
        MainActivity m2 = home(font);
        assertEquals(R.id.chipAspect11, ((com.google.android.material.chip.ChipGroup) m2.findViewById(R.id.chipGroupAspect)).getCheckedChipId());
        assertEquals("1:1 framing", ((TextView) m2.findViewById(R.id.tvFrameLabel)).getText().toString());
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
    @Test @Config(sdk = 26, qualifiers = "w360dp-h640dp-xhdpi") public void a8_f100() throws Exception { check("a8_f100", 1.0f); }
}
