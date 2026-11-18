package com.viral.shortsmaker;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.button.MaterialButton;
import com.viral.shortsmaker.core.JobManager;
import com.viral.shortsmaker.core.Prefs;
import com.viral.shortsmaker.core.Project;

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
 * v17 (2.8) screen checks (JVM-simulated Android, not real phones): the Logs sheet buttons (his OPPO showed "Cop",
 * "Shar", "Rep"), Settings without the two removed switches, the Premium card, and every main screen at font 1.0 / 1.3
 * / 1.5 on narrow phones. Full-length captures go to shotDir/v17 for the contact sheet.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class V17ScreenTest {
    private final V11ScreenTest v11 = new V11ScreenTest();
    static final boolean BEFORE = Boolean.parseBoolean(System.getProperty("v17Before", "false"));

    @Before public void reset() { v11.reset(); }

    static void save(Bitmap b, String name) throws Exception {
        File d = new File(System.getProperty("shotDir", "build/shots"), "v17");
        d.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(d, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, fo); }
    }

    /** the whole scrollable content of a tab (not just the window), so the sheet shows everything */
    static void fullShot(View scrollOrRoot, String name) throws Exception {
        View c = scrollOrRoot instanceof ViewGroup && ((ViewGroup) scrollOrRoot).getChildCount() > 0 ? ((ViewGroup) scrollOrRoot).getChildAt(0) : scrollOrRoot;
        int w = Math.max(1, c.getWidth()), h = Math.max(1, Math.min(c.getHeight(), 9000));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF0B0A10);
        c.draw(new Canvas(b));
        save(b, name);
    }

    static void windowShot(View root, String name) throws Exception {
        V11ScreenTest.idle(600);
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        save(b, name);
    }

    private MainActivity home(float font) {
        RuntimeEnvironment.setFontScale(font);
        MainActivity m = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((TextView) m.findViewById(R.id.tvVideoTitle)).setText("Ananya Panday On Childhood, Relationship, Bollywood, SRK & celebrity Life");
        ((TextView) m.findViewById(R.id.tvVideoDuration)).setText("2:10:23");
        m.findViewById(R.id.layoutVideoInfo).setVisibility(View.VISIBLE);
        m.findViewById(R.id.cardControls).setVisibility(View.VISIBLE);
        V11ScreenTest.idle(400);
        return m;
    }

    private static void add(List<String> bad, String screen, View root) {
        for (String s : V11ScreenTest.clipped(root)) bad.add(screen + ": " + s);
    }

    void run(String tag, float font) throws Exception {
        List<String> bad = new ArrayList<>();
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit().putLong("premium_unlock_time", 0).putInt("face_track_credits", 0)
                .putInt("ads_watched_count", 3).commit();
        MainActivity m = home(font);
        fullShot(m.findViewById(R.id.tabCreate), tag + "_create");
        add(bad, "create", m.getWindow().getDecorView());
        if (!BEFORE) {
            View card = m.findViewById(R.id.cardPremium);
            assertNotNull("premium card", card);
            assertEquals(View.VISIBLE, card.getVisibility());
            // v18 (2.9): the card lives in Settings again (2.8 had it on Create)
            assertTrue("premium card in Settings", isInside(card, m.findViewById(R.id.tabSettings)));
            assertFalse("premium card not on Create", isInside(card, m.findViewById(R.id.tabCreate)));
            for (int id : new int[]{R.id.rowPremShorts, R.id.rowPremHd, R.id.rowPremCaptions, R.id.rowPremFace, R.id.switchHighSpeed, R.id.btnWatchAdSettings})
                assertTrue("premium item inside the card", isInside(m.findViewById(id), card));
            String hdSub = ((TextView) m.findViewById(R.id.rowPremHdSub)).getText().toString();
            assertTrue(hdSub, Prefs.PREMIUM_EDITION ? hdSub.contains("Premium") && !hdSub.contains("ad") : hdSub.contains("1 ad"));
        }
        com.google.android.material.bottomnavigation.BottomNavigationView nav = m.findViewById(R.id.bottomNav);
        nav.setSelectedItemId(R.id.nav_settings); V11ScreenTest.idle(600);
        fullShot(m.findViewById(R.id.tabSettings), tag + "_settings");
        add(bad, "settings", m.getWindow().getDecorView());
        if (!BEFORE) {
            assertNull("hardware encoder switch removed", find(m, "switchHwEncode"));
            assertNull("keep screen on switch removed", find(m, "switchKeepScreen"));
            assertFalse(allText(m.getWindow().getDecorView()).contains("Keep screen on"));
            assertFalse(allText(m.getWindow().getDecorView()).contains("Hardware encoder"));
        }
        // Logs sheet
        java.lang.reflect.Method sl = MainActivity.class.getDeclaredMethod("showLogsSheet");
        sl.setAccessible(true);
        sl.invoke(m);
        V11ScreenTest.idle(800);
        Dialog d = ShadowDialog.getLatestDialog();
        View droot = d.getWindow().getDecorView();
        windowShot(droot, tag + "_logs");
        add(bad, "logs", droot);
        for (int id : new int[]{R.id.btnLogsCopy, R.id.btnLogsShare, R.id.btnLogsReport}) {
            MaterialButton b = droot.findViewById(id);
            String want = b.getText().toString();
            boolean whole = b.getLayout() != null && b.getLineCount() == 1 && b.getLayout().getLineEnd(0) >= want.length()
                    && b.getLayout().getEllipsisCount(0) == 0 && b.getLayout().getLineWidth(0) <= b.getWidth() - b.getPaddingLeft() - b.getPaddingRight() + 1;
            if (!whole) bad.add("logs: \"" + want + "\" button text does not fit (" + (b.getLayout() == null ? -1 : b.getLayout().getLineWidth(0)) + " of " + (b.getWidth() - b.getPaddingLeft() - b.getPaddingRight()) + " px)");
            if (!BEFORE) assertTrue("touch target " + want, b.getHeight() >= 44 * droot.getResources().getDisplayMetrics().density * 0.99f * 0.9f);
        }
        d.dismiss();
        V11ScreenTest.idle(200);
        // processing, results, studio
        JobManager.get().reset();
        MainActivity pr = Robolectric.buildActivity(MainActivity.class).setup().get();
        ScreenshotTest.fakeProgress(3, 0.16, "Uploading speech to AI", "6.2 of 12.4 MB • 2.9 MB/s • 15 of 15 parts ready");
        V11ScreenTest.idle(600);
        windowShot(pr.getWindow().getDecorView(), tag + "_processing");
        add(bad, "processing", pr.getWindow().getDecorView());
        JobManager.get().removeAllListeners(); JobManager.get().project = null; JobManager.get().reset();
        Project p = V11ScreenTest.project(3);
        p.clips.get(0).hook = "Aapne Arjun Reddy se shaadi ki?"; p.clips.get(0).title = "Arjun Reddy wala sawaal";
        JobManager.get().finish(JobManager.State.DONE, null);
        MainActivity r = Robolectric.buildActivity(MainActivity.class).setup().get();
        V11ScreenTest.idle(600);
        windowShot(r.getWindow().getDecorView(), tag + "_results");
        add(bad, "results", r.getWindow().getDecorView());
        V11ScreenTest.project(3);
        CaptionStudioActivity cs = v11.studio(0);
        windowShot(cs.getWindow().getDecorView(), tag + "_studio");
        add(bad, "studio", cs.getWindow().getDecorView());
        File rep = new File(new File(System.getProperty("shotDir", "build/shots"), "v17"), tag + "_problems.txt");
        try (FileOutputStream fo = new FileOutputStream(rep)) { fo.write(String.join("\n", bad).getBytes("UTF-8")); }
        if (!BEFORE) assertTrue(tag + ": " + bad, bad.isEmpty());
    }

    private static boolean isInside(View v, View ancestor) {
        if (v == null) return false;
        for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) if (p == ancestor) return true;
        return false;
    }

    private static View find(Activity a, String idName) {
        int id = a.getResources().getIdentifier(idName, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    private static String allText(View v) {
        StringBuilder b = new StringBuilder();
        collect(v, b);
        return b.toString();
    }

    private static void collect(View v, StringBuilder b) {
        if (v instanceof TextView) b.append(((TextView) v).getText()).append('\n');
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), b);
    }

    // his phone: OPPO CPH2667, 720 x 1604, ~360 dp wide
    @Test @Config(sdk = 35, qualifiers = "w360dp-h800dp-xhdpi") public void oppo_f100() throws Exception { run("oppo_f100", 1.0f); }
    @Test @Config(sdk = 35, qualifiers = "w360dp-h800dp-xhdpi") public void oppo_f130() throws Exception { run("oppo_f130", 1.3f); }
    @Test @Config(sdk = 35, qualifiers = "w360dp-h800dp-xhdpi") public void oppo_f150() throws Exception { run("oppo_f150", 1.5f); }
    @Test @Config(sdk = 26, qualifiers = "w320dp-h568dp-mdpi") public void small_f130() throws Exception { run("small_f130", 1.3f); }
    @Test @Config(sdk = 30, qualifiers = "w320dp-h640dp-xhdpi") public void narrow_f150() throws Exception { run("narrow_f150", 1.5f); }
}
