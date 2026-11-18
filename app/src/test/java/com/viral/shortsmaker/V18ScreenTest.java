package com.viral.shortsmaker;

import static org.junit.Assert.*;

import android.view.View;
import android.widget.TextView;

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

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * v18 (2.9) screen checks (JVM-simulated Android, not a real phone): the Premium card back in Settings with the
 * High-speed download and 1080p HD switches and the new "1 ad =" rules; Create without the card. Font 1.0 / 1.3.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class V18ScreenTest {
    private final V11ScreenTest v11 = new V11ScreenTest();

    @Before public void reset() { v11.reset(); }

    static void shot(View root, String name) throws Exception {
        android.graphics.Bitmap b;
        View c = root instanceof android.view.ViewGroup && ((android.view.ViewGroup) root).getChildCount() > 0 ? ((android.view.ViewGroup) root).getChildAt(0) : root;
        int w = Math.max(1, c.getWidth()), h = Math.max(1, Math.min(c.getHeight(), 9000));
        b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF0B0A10);
        c.draw(new android.graphics.Canvas(b));
        File d = new File(System.getProperty("shotDir", "build/shots"), "v18");
        d.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(d, name + ".png"))) { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fo); }
    }

    static boolean inside(View v, View anc) {
        if (v == null || anc == null) return false;
        for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) if (p == anc) return true;
        return false;
    }

    void run(String tag, float font, boolean unlocked) throws Exception {
        android.content.SharedPreferences sp = Prefs.sp(ApplicationProvider.getApplicationContext());
        sp.edit().putBoolean("migrated_v18", true).putLong("premium_unlock_time", 0).putInt("ads_watched_count", 3)
                .putInt("face_track_credits", unlocked ? 1 : 0).putInt("caption_credits", unlocked ? 1 : 0)
                .putLong("hd_unlock_time", unlocked ? System.currentTimeMillis() - 18 * 60000L : 0).putLong("hd_unlock_len", Prefs.HD_UNLOCK_MS)
                .putInt("video_quality", unlocked ? 1 : 0).commit();
        RuntimeEnvironment.setFontScale(font);
        MainActivity m = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((TextView) m.findViewById(R.id.tvVideoTitle)).setText("Ananya Panday On Childhood, Relationship, Bollywood, SRK & celebrity Life");
        ((TextView) m.findViewById(R.id.tvVideoDuration)).setText("2:10:23");
        m.findViewById(R.id.layoutVideoInfo).setVisibility(View.VISIBLE);
        m.findViewById(R.id.cardControls).setVisibility(View.VISIBLE);
        V11ScreenTest.idle(400);
        List<String> bad = new ArrayList<>();
        View create = m.findViewById(R.id.tabCreate), settings = m.findViewById(R.id.tabSettings);
        View card = m.findViewById(R.id.cardPremium);
        assertTrue("premium card in Settings", inside(card, settings));
        assertFalse("no premium card on Create", inside(card, create));
        shot(create, tag + "_create");
        for (String s : V11ScreenTest.clipped(m.getWindow().getDecorView())) bad.add("create: " + s);
        com.google.android.material.bottomnavigation.BottomNavigationView nav = m.findViewById(R.id.bottomNav);
        nav.setSelectedItemId(R.id.nav_settings);
        V11ScreenTest.idle(600);
        for (int id : new int[]{R.id.switchHighSpeed, R.id.switchHd, R.id.btnWatchAdSettings, R.id.rowPremCaptions, R.id.rowPremFace, R.id.tvAdsCount})
            assertTrue("inside the Settings premium card: " + m.getResources().getResourceEntryName(id), inside(m.findViewById(id), card));
        String hd = ((TextView) m.findViewById(R.id.rowPremHdSub)).getText().toString();
        String cap = ((TextView) m.findViewById(R.id.rowPremCaptionsSub)).getText().toString();
        String face = ((TextView) m.findViewById(R.id.rowPremFaceSub)).getText().toString();
        MaterialSwitch swHd = m.findViewById(R.id.switchHd);
        if (Prefs.PREMIUM_EDITION) {   // premium edition: everything unlocked, no ads (PremiumEditionTest)
            assertTrue(hd, hd.contains("Premium"));
            assertTrue(cap, cap.contains("Premium"));
            assertTrue(face, face.contains("Premium"));
        } else if (unlocked) {
            assertTrue(hd, hd.contains("left") && hd.contains("On"));
            assertTrue(swHd.isChecked());
            assertTrue(cap, cap.contains("next video"));
            assertTrue(face, face.contains("1 podcast"));
        } else {
            assertTrue(hd, hd.contains("1 ad unlocks 1 hour"));
            assertFalse(swHd.isChecked());
            assertTrue(cap, cap.contains("1 ad = captions on 1 video"));
            assertTrue(face, face.contains("1 ad"));
        }
        shot(settings, tag + "_settings");
        for (String s : V11ScreenTest.clipped(m.getWindow().getDecorView())) bad.add("settings: " + s);
        File rep = new File(new File(System.getProperty("shotDir", "build/shots"), "v18"), tag + "_problems.txt");
        try (FileOutputStream fo = new FileOutputStream(rep)) { fo.write(String.join("\n", bad).getBytes("UTF-8")); }
        assertTrue(tag + ": " + bad, bad.isEmpty());
    }

    @Test @Config(sdk = 35, qualifiers = "w360dp-h800dp-xhdpi") public void oppo_f100_locked() throws Exception { run("oppo_f100_locked", 1.0f, false); }
    @Test @Config(sdk = 35, qualifiers = "w360dp-h800dp-xhdpi") public void oppo_f130_locked() throws Exception { run("oppo_f130_locked", 1.3f, false); }
    @Test @Config(sdk = 35, qualifiers = "w360dp-h800dp-xhdpi") public void oppo_f100_unlocked() throws Exception { run("oppo_f100_unlocked", 1.0f, true); }
    @Test @Config(sdk = 35, qualifiers = "w360dp-h800dp-xhdpi") public void oppo_f130_unlocked() throws Exception { run("oppo_f130_unlocked", 1.3f, true); }
}
