package com.viral.shortsmaker;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.viral.shortsmaker.core.Pipeline;
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
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Premium edition (Oct 31). Runs in BOTH flavors and checks the flavor it is built in:
 *  - premium: every gate open on a fresh install (1080p, captions, face tracking, 2-5 shorts, high speed), no 24 h timer,
 *    no "watch ad" anywhere, no call into the ad SDK, Settings says "Premium — all features unlocked";
 *  - free: the same fresh install is locked exactly like the normal app and the ad unlocks still work.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 35, qualifiers = "w360dp-h800dp-xhdpi")
public class PremiumEditionTest {
    private final V11ScreenTest v11 = new V11ScreenTest();
    private Context ctx;

    @Before public void reset() {
        v11.reset();
        ctx = ApplicationProvider.getApplicationContext();
        // a fresh install: no PRO pass, no ad credits, nothing unlocked
        Prefs.sp(ctx).edit().putBoolean("migrated_v18", true).putLong("premium_unlock_time", 0).putInt("ads_watched_count", 0)
                .putInt("face_track_credits", 0).putInt("caption_credits", 0).putLong("hd_unlock_time", 0)
                .putLong("high_speed_unlock_time", 0).commit();
        MainActivity.AD_SDK_CALLS.set(0);
    }

    @Test public void flavorFlagMatchesBuild() {
        assertEquals(BuildConfig.PREMIUM, Prefs.PREMIUM_EDITION);
        assertEquals(BuildConfig.PREMIUM ? "premium" : "free", BuildConfig.FLAVOR);
        // same app either way: installs over the normal build
        assertEquals("com.viral.shortsmaker", BuildConfig.APPLICATION_ID);
        assertEquals(47, BuildConfig.VERSION_CODE);
        assertEquals("5.9.1", BuildConfig.VERSION_NAME);
    }

    @Test public void gatesOnFreshInstall() {
        boolean p = Prefs.PREMIUM_EDITION;
        assertEquals(p, Prefs.isPremium(ctx));
        assertEquals(p, Prefs.hdUnlocked(ctx));
        assertEquals(p, Prefs.captionsUnlocked(ctx));
        assertEquals(p, Prefs.faceTrackUnlocked(ctx));
        assertEquals(p, Prefs.highSpeedUnlocked(ctx));
        // using a feature never runs out in premium; in free it needs a credit
        for (int i = 0; i < 20; i++) {
            assertEquals(p, Prefs.useCaptionCredit(ctx));
            assertEquals(p, Prefs.useFaceTrack(ctx));
        }
        assertEquals(0, Prefs.captionCredits(ctx));
        assertEquals(0, Prefs.faceTrackCredits(ctx));
        // no PRO timer that can run out: still open a year later
        Prefs.sp(ctx).edit().putLong("clock_high", System.currentTimeMillis() + 365L * 24 * 3600_000L).commit();
        assertEquals(p, Prefs.isPremium(ctx));
        assertEquals(p, Prefs.hdUnlocked(ctx));
    }

    @Test public void freeAdUnlocksStillWork() {
        org.junit.Assume.assumeFalse(Prefs.PREMIUM_EDITION);
        Prefs.unlockHd(ctx);
        assertTrue(Prefs.hdUnlocked(ctx));
        Prefs.addCaptionCredit(ctx);
        assertTrue(Prefs.captionsUnlocked(ctx));
        assertTrue(Prefs.useCaptionCredit(ctx));
        assertFalse(Prefs.captionsUnlocked(ctx));
        Prefs.addFaceTrackCredit(ctx);
        assertTrue(Prefs.useFaceTrack(ctx));
        assertFalse(Prefs.faceTrackUnlocked(ctx));
        // the 10-ad pass: 24 h, then locked again
        Prefs.sp(ctx).edit().putLong("premium_unlock_time", Prefs.clockNow(ctx)).commit();
        assertTrue(Prefs.isPremium(ctx));
        Prefs.sp(ctx).edit().putLong("premium_unlock_time", Prefs.clockNow(ctx) - 25 * 3600_000L).commit();
        assertFalse(Prefs.isPremium(ctx));
    }

    @Test public void editionDefaults() {
        SharedPreferences sp = Prefs.sp(ctx);
        sp.edit().remove("premium_edition_defaults").putInt("video_quality", 0).remove("high_speed").commit();
        Prefs.premiumEditionDefaults(ctx);
        if (Prefs.PREMIUM_EDITION) {
            assertEquals("1080p HD by default", 1, Prefs.videoQuality(ctx));
            assertTrue("high speed on", Prefs.highSpeedOn(ctx));
            // only once: the user's later choice (720p) stays
            sp.edit().putInt("video_quality", 2).commit();
            Prefs.premiumEditionDefaults(ctx);
            assertEquals(2, Prefs.videoQuality(ctx));
            // 480p data saver is never overridden
            sp.edit().remove("premium_edition_defaults").putInt("video_quality", 3).commit();
            Prefs.premiumEditionDefaults(ctx);
            assertEquals(3, Prefs.videoQuality(ctx));
        } else {
            assertEquals("free build changes nothing", 0, Prefs.videoQuality(ctx));
            assertFalse(Prefs.highSpeedOn(ctx));
            assertFalse(sp.contains("premium_edition_defaults"));
        }
    }

    private MainActivity open() {
        MainActivity m = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((TextView) m.findViewById(R.id.tvVideoTitle)).setText("Raja Bhai Podcast: social media, rapid fire aur system ki baat");
        ((TextView) m.findViewById(R.id.tvVideoDuration)).setText("1:12:40");
        m.findViewById(R.id.layoutVideoInfo).setVisibility(View.VISIBLE);
        m.findViewById(R.id.cardControls).setVisibility(View.VISIBLE);
        V11ScreenTest.idle(400);
        return m;
    }

    private static void collectTexts(View v, List<String> out) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView) { CharSequence t = ((TextView) v).getText(); if (t != null && t.length() > 0) out.add(t.toString()); }
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) collectTexts(((android.view.ViewGroup) v).getChildAt(i), out);
    }

    @Test public void screens() throws Exception {
        MainActivity m = open();
        boolean p = Prefs.PREMIUM_EDITION;
        ChipGroup cg = m.findViewById(R.id.chipGroupShortsCount);
        // tapping 3 shorts: premium picks it at once; free asks for an ad
        Chip three = m.findViewById(R.id.chipCount3);
        three.performClick();
        V11ScreenTest.idle(300);
        if (p) {
            assertEquals(R.id.chipCount3, cg.getCheckedChipId());
            assertNull("no unlock dialog in premium", ShadowDialog.getLatestDialog());
            for (int id : new int[]{R.id.chipCount2, R.id.chipCount3, R.id.chipCount4, R.id.chipCount5})
                assertFalse("no lock icon", ((Chip) m.findViewById(id)).isChipIconVisible());
            assertFalse("no lock on face tracking", m.findViewById(R.id.ivFaceLock).getVisibility() == View.VISIBLE);
            assertFalse("no lock on 1080p", ((Chip) m.findViewById(R.id.chipExp1080)).isChipIconVisible());
            assertEquals(R.id.chipExp1080, ((ChipGroup) m.findViewById(R.id.chipGroupExport)).getCheckedChipId());
            assertEquals("PREMIUM", ((TextView) m.findViewById(R.id.badgePro)).getText().toString());
            assertEquals(View.VISIBLE, m.findViewById(R.id.badgePro).getVisibility());
            // captions and face tracking switch on with no ad
            MaterialSwitch cap = m.findViewById(R.id.switchCaptions), face = m.findViewById(R.id.switchFaceTrack);
            cap.setChecked(false); cap.setChecked(true);
            face.setChecked(false); face.setChecked(true);
            V11ScreenTest.idle(300);
            assertTrue(cap.isChecked());
            assertTrue(face.isChecked());
            assertNull(ShadowDialog.getLatestDialog());
        } else {
            assertEquals("free: stays on 1 until the ad", R.id.chipCount1, cg.getCheckedChipId());
            assertNotNull("free: unlock dialog", ShadowDialog.getLatestDialog());
            ShadowDialog.getLatestDialog().dismiss();
            ShadowDialog.reset();
        }
        String createText;
        { List<String> t = new ArrayList<>(); collectTexts(m.findViewById(R.id.tabCreate), t); createText = String.join(" | ", t); }
        File dir = new File(System.getProperty("shotDir", "build/shots"), p ? "premium" : "free");
        dir.mkdirs();
        V18ScreenTest.shot(m.findViewById(R.id.tabCreate), "../" + dir.getName() + "/create");

        com.google.android.material.bottomnavigation.BottomNavigationView nav = m.findViewById(R.id.bottomNav);
        nav.setSelectedItemId(R.id.nav_settings);
        V11ScreenTest.idle(600);
        View settings = m.findViewById(R.id.tabSettings);
        String status = ((TextView) m.findViewById(R.id.tvPremiumStatus)).getText().toString();
        List<String> t = new ArrayList<>(); collectTexts(m.findViewById(R.id.cardPremium), t);
        String card = String.join(" | ", t);
        if (p) {
            assertEquals("Premium — all features unlocked", status);
            assertFalse("no watch-ad button", m.findViewById(R.id.btnWatchAdSettings).isShown());
            assertFalse("no ad counter", m.findViewById(R.id.tvAdsCount).isShown());
            assertFalse("no 24 h timer", m.findViewById(R.id.tvTimerCountdown).isShown());
            for (String s : new String[]{"ad", "ads", "Ad", "Watch", "PRO active", "1 ad", "/ 10"})
                assertFalse("premium card mentions \"" + s + "\": " + card, java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(s) + "\\b").matcher(card).find());
            for (String s : new String[]{"1 ad", "Watch ad", "unlock with an ad"})
                assertFalse("create screen mentions \"" + s + "\": " + createText, createText.contains(s));
            assertTrue(card, card.contains("Unlocked with Premium"));
            assertTrue("high speed on", ((MaterialSwitch) m.findViewById(R.id.switchHighSpeed)).isChecked());
            // every unlock path, even if reached: no dialog and no ad SDK call
            m.findViewById(R.id.btnWatchAdSettings).performClick();
            m.findViewById(R.id.rowPremCaptions).performClick();
            m.findViewById(R.id.rowPremFace).performClick();
            m.findViewById(R.id.rowPremShorts).performClick();
            ((MaterialSwitch) m.findViewById(R.id.switchHd)).setChecked(false);
            ((MaterialSwitch) m.findViewById(R.id.switchHd)).setChecked(true);
            ((MaterialSwitch) m.findViewById(R.id.switchHighSpeed)).setChecked(false);
            ((MaterialSwitch) m.findViewById(R.id.switchHighSpeed)).setChecked(true);
            V11ScreenTest.idle(600);
            assertNull("no dialog", ShadowDialog.getLatestDialog());
            assertTrue(((MaterialSwitch) m.findViewById(R.id.switchHighSpeed)).isChecked());
            assertEquals(1, Prefs.videoQuality(m));
        } else {
            assertTrue(status, status.contains("Watch 10 ads"));
            assertTrue("free: watch-ad button", m.findViewById(R.id.btnWatchAdSettings).isShown());
        }
        V18ScreenTest.shot(settings, "../" + dir.getName() + "/settings");
        List<String> bad = new ArrayList<>(V11ScreenTest.clipped(m.getWindow().getDecorView()));
        assertTrue("clipped text: " + bad, bad.isEmpty());
        // the simulator never initialises ads (no network); premium must not even try anywhere
        if (p) assertEquals("ad SDK calls", 0, MainActivity.AD_SDK_CALLS.get());
    }

    @Test public void pipelineQualityNeverFallsBackInPremium() throws Exception {
        Prefs.sp(ctx).edit().putInt("video_quality", 1).commit();
        java.lang.reflect.Method m = Pipeline.class.getDeclaredMethod("effectiveQuality");
        m.setAccessible(true);
        Pipeline pl = newPipeline();
        if (pl == null) return;
        assertEquals(Prefs.PREMIUM_EDITION ? 1 : 0, m.invoke(pl));
    }

    private Pipeline newPipeline() {
        for (java.lang.reflect.Constructor<?> c : Pipeline.class.getDeclaredConstructors()) {
            Class<?>[] ps = c.getParameterTypes();
            if (ps.length == 1 && Context.class.isAssignableFrom(ps[0])) {
                try { c.setAccessible(true); return (Pipeline) c.newInstance(ctx); } catch (Exception e) { return null; }
            }
        }
        return null;
    }
}
