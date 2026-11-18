package com.viral.shortsmaker;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.button.MaterialButton;
import com.viral.shortsmaker.core.FfmpegCommands;
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

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * v11 screen checks (JVM-simulated Android, not real phones): the Caption Studio bottom bar and every visible text on
 * every screen must be fully drawn — one line on buttons, nothing cut at the bottom, nothing outside the window — on
 * small, normal and tall screens at font scale 1.0 and 1.3.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class V11ScreenTest {
    private static final File OUT = new File(System.getProperty("shotDir", "build/shots"), "v11");

    @Before public void reset() {
        // the app forces dark mode at start; doing it first keeps the simulator from recreating screens mid-test
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        RuntimeEnvironment.setFontScale(1.0f);
        resetJobs();
    }

    private void resetJobs() {
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit()
                .putString("gemini_key", "AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY12")
                .putString("groq_key", "gsk_DUMMYDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY1234")
                .putBoolean("is_onboarding_done", true).apply();
        JobManager.get().removeAllListeners();
        JobManager.get().project = null;
        JobManager.get().reset();
    }

    static void idle(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    static void shotTo(Activity a, String dir, String name) throws Exception {
        idle(800);
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        File d = new File(System.getProperty("shotDir", "build/shots"), dir);
        d.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(d, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, fo); }
    }

    static void shot(Activity a, String name) throws Exception {
        idle(800);
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        OUT.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(OUT, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, fo); }
    }

    /** every visible TextView/Button: its text fits its own box (no cut-off line) and the box is inside the window */
    static List<String> clipped(View root) {
        List<String> bad = new ArrayList<>();
        walk(root, root, bad);
        return bad;
    }

    private static void walk(View root, View v, List<String> bad) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView && v.getWidth() > 0 && v.getHeight() > 0) {
            TextView t = (TextView) v;
            CharSequence s = t.getText();
            if (s != null && s.length() > 0 && t.getLayout() != null && t.isShown()) {
                int need = t.getLayout().getHeight() + t.getCompoundPaddingTop() + t.getCompoundPaddingBottom();
                String id = idName(t) + " \"" + s.toString().replace("\n", " ") + "\"";
                if (need > t.getHeight() + 2 && t.getMaxLines() >= t.getLineCount())
                    bad.add(id + " text cut at bottom: needs " + need + "px, has " + t.getHeight() + "px");
                if (v instanceof MaterialButton && t.getLineCount() > 1)
                    bad.add(id + " button text wraps to " + t.getLineCount() + " lines");
                // words silently dropped: the layout stops before the end of the text with no "…"
                int last = t.getLineCount() - 1;
                if (last >= 0 && t.getLayout().getLineEnd(last) < s.length() && t.getLayout().getEllipsisCount(last) == 0
                        && !(t instanceof android.widget.EditText))
                    bad.add(id + " text cut off after \"" + s.subSequence(0, t.getLayout().getLineEnd(last)) + "\"");
                // a single-line label that had to ellipsize is clipped words
                if (t.getLineCount() > 0 && t.getLayout().getEllipsisCount(t.getLineCount() - 1) > 0 && v instanceof MaterialButton)
                    bad.add(id + " button text ellipsized");
                Rect r = new Rect();
                boolean vis = t.getGlobalVisibleRect(r);
                if (vis && inScrolling(t) == null && r.height() + 2 < t.getHeight())
                    bad.add(id + " is cut by the window/parent: visible " + r.height() + " of " + t.getHeight() + "px");
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(root, g.getChildAt(i), bad);
        }
    }

    private static View inScrolling(View v) {
        for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
            View pv = (View) p;
            if (pv instanceof android.widget.ScrollView || pv instanceof androidx.core.widget.NestedScrollView
                    || pv instanceof android.widget.HorizontalScrollView || pv instanceof androidx.recyclerview.widget.RecyclerView
                    || pv instanceof androidx.viewpager2.widget.ViewPager2) return pv;
        }
        return null;
    }

    private static String idName(View v) {
        try { return v.getId() == View.NO_ID ? v.getClass().getSimpleName() : v.getResources().getResourceEntryName(v.getId()); }
        catch (Exception e) { return v.getClass().getSimpleName(); }
    }

    static Project project(int n) throws Exception {
        Project p = ScreenshotTest.demoProject("Podcast: what nobody tells you about Bollywood", n, System.currentTimeMillis());
        JobManager.get().project = p;
        return p;
    }

    CaptionStudioActivity studio(int clip) {
        CaptionStudioActivity.pendingFrame = ScreenshotTest.demoFrame();
        CaptionStudioActivity.pendingFrameMode = FfmpegCommands.Frame.BLUR;
        CaptionStudioActivity a = Robolectric.buildActivity(CaptionStudioActivity.class, new Intent().putExtra(CaptionStudioActivity.EXTRA_CLIP, clip)).setup().get();
        idle(500);
        return a;
    }

    void checkStudio(String tag, float font) throws Exception {
        RuntimeEnvironment.setFontScale(font);
        project(3);
        CaptionStudioActivity a = studio(0);
        MaterialButton p = a.findViewById(R.id.btnPrimary), s = a.findViewById(R.id.btnSecondary);
        idle(300);
        shot(a, tag);
        assertEquals(View.VISIBLE, s.getVisibility());
        assertEquals(tag + " primary lines", 1, p.getLineCount());
        assertEquals(tag + " secondary lines", 1, s.getLineCount());
        assertEquals(tag + " equal heights", p.getHeight(), s.getHeight());
        View bar = (View) p.getParent();
        int[] loc = new int[2];
        bar.getLocationInWindow(loc);
        View content = a.findViewById(android.R.id.content);
        assertTrue(tag + " bar inside the window", loc[1] + bar.getHeight() <= content.getHeight() + 1);
        List<String> bad = clipped(a.getWindow().getDecorView());
        assertTrue(tag + ": " + bad, bad.isEmpty());
    }

    @Test @Config(sdk = 34, qualifiers = "w360dp-h800dp-xhdpi") public void studio_720px_font100() throws Exception { checkStudio("studio_720px_font100", 1.0f); }
    @Test @Config(sdk = 34, qualifiers = "w360dp-h800dp-xhdpi") public void studio_720px_font130() throws Exception { checkStudio("studio_720px_font130", 1.3f); }
    @Test @Config(sdk = 26, qualifiers = "w320dp-h568dp-mdpi") public void studio_small_font130() throws Exception { checkStudio("studio_small_font130", 1.3f); }
    @Test @Config(sdk = 35, qualifiers = "w393dp-h873dp-xxhdpi") public void studio_tall_font130() throws Exception { checkStudio("studio_tall_font130", 1.3f); }
    @Test @Config(sdk = 30, qualifiers = "w411dp-h891dp-xxhdpi") public void studio_large_font100() throws Exception { checkStudio("studio_large_font100", 1.0f); }

    /** every screen, every visible text: nothing cut, nothing wrapped on a button */
    void allScreens(String tag, float font) throws Exception {
        RuntimeEnvironment.setFontScale(font);
        List<String> bad = new ArrayList<>();
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit().clear().apply();
        OnboardingActivity ob = Robolectric.buildActivity(OnboardingActivity.class).setup().get();
        idle(400); shot(ob, tag + "_onboarding"); add(bad, "onboarding", ob);
        resetJobs();
        MainActivity m = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((TextView) m.findViewById(R.id.tvVideoTitle)).setText("Ananya Panday On Childhood, Relationship, Bollywood, SRK & celebrity Life");
        ((TextView) m.findViewById(R.id.tvVideoDuration)).setText("1:15:23");
        m.findViewById(R.id.layoutVideoInfo).setVisibility(View.VISIBLE);
        m.findViewById(R.id.cardControls).setVisibility(View.VISIBLE);
        idle(400); shot(m, tag + "_home"); add(bad, "home", m);
        com.google.android.material.bottomnavigation.BottomNavigationView nav = m.findViewById(R.id.bottomNav);
        nav.setSelectedItemId(R.id.nav_settings); idle(600); shot(m, tag + "_settings"); add(bad, "settings", m);
        project(3);
        nav.setSelectedItemId(R.id.nav_history); for (int i = 0; i < 6; i++) { Thread.sleep(120); idle(100); }
        shot(m, tag + "_history"); add(bad, "history", m);
        resetJobs();
        Project p = project(3);
        p.clips.get(0).hook = "Aapne Arjun Reddy se shaadi ki?"; p.clips.get(0).title = "Arjun Reddy wala sawaal";
        JobManager.get().finish(JobManager.State.DONE, null);
        MainActivity r = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(600); shot(r, tag + "_results"); add(bad, "results", r);
        resetJobs();
        MainActivity pr = Robolectric.buildActivity(MainActivity.class).setup().get();
        ScreenshotTest.fakeProgress(3, 0.42, "Rendering shorts with captions", "1 of 3 done • 1.8× real-time");
        idle(600); shot(pr, tag + "_processing"); add(bad, "processing", pr);
        CaptionStudioActivity cs = studio(-1);
        shot(cs, tag + "_studio_default"); add(bad, "studio default", cs);
        project(3);
        CaptionStudioActivity all = studio(-2);
        shot(all, tag + "_studio_all"); add(bad, "studio all", all);
        assertTrue(tag + ": " + bad, bad.isEmpty());
    }

    private static void add(List<String> bad, String screen, Activity a) {
        for (String s : clipped(a.getWindow().getDecorView())) bad.add(screen + ": " + s);
    }

    @Test @Config(sdk = 26, qualifiers = "w320dp-h568dp-mdpi") public void screens_android8_small_font130() throws Exception { allScreens("a8_small_f130", 1.3f); }
    @Test @Config(sdk = 30, qualifiers = "w360dp-h800dp-xhdpi") public void screens_android11_720px_font130() throws Exception { allScreens("a11_720_f130", 1.3f); }
    @Test @Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi") public void screens_android14_font100() throws Exception { allScreens("a14_f100", 1.0f); }
    @Test @Config(sdk = 35, qualifiers = "w412dp-h915dp-xxhdpi") public void screens_android15_font130() throws Exception { allScreens("a15_f130", 1.3f); }
}
