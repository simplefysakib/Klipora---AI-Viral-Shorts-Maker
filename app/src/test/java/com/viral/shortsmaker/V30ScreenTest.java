package com.viral.shortsmaker;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.button.MaterialButton;
import com.viral.shortsmaker.core.JobManager;
import com.viral.shortsmaker.core.Prefs;
import com.viral.shortsmaker.core.Project;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

/**
 * code 30 (4.8): the stopped-job card on the home screen (his vivo report): the right message per cause, nothing for a
 * stale marker or a job that had finished, Continue with finished shorts, a report that carries the job's link, title,
 * exit record and log (4.7: "Video: N/A, URL: N/A" and only the new session's 4 lines), and the vivo fix list.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi")
public class V30ScreenTest {
    private static final File OUT = new File(System.getProperty("shotDir", "build/shots"));
    private String manu;

    private void idle(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    private void shot(Activity a, String name) throws Exception {
        idle(1200);
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        OUT.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(OUT, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, fo); }
    }

    @Before public void setUp() {
        manu = Build.MANUFACTURER;
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit()
                .putString("gemini_key", "AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY12")
                .putString("groq_key", "gsk_DUMMYDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY1234")
                .putBoolean("is_onboarding_done", true).commit();
        JobManager.get().removeAllListeners();
        JobManager.get().project = null;
        JobManager.get().reset();
    }

    @After public void tearDown() { ReflectionHelpers.setStaticField(Build.class, "MANUFACTURER", manu); }

    /** a marker as 4.8 writes it, for a job in another (dead) process */
    static void marker(Context c, long startedAgoMs, String project) {
        long now = System.currentTimeMillis();
        Prefs.sp(c).edit().putBoolean("job_alive", true).putLong("job_alive_at", now - startedAgoMs).putLong("job_beat", now - startedAgoMs + 60_000)
                .putInt("job_pid", 99999).putInt("job_ver", BuildConfig.VERSION_CODE)
                .putLong("job_boot", System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime())
                .putString("job_url", "https://www.youtube.com/watch?v=dQw4w9WgXcQ").putString("job_title", "The stopped video")
                .putString("job_step", "Rendering shorts 2 of 5").putInt("job_pct", 64)
                .putString("job_fgs", "on (foreground service, data sync)")
                .putString("job_project", project == null ? "" : project).putInt("job_total", 3)
                .putString("last_request", "{\"kind\":\"ANALYZE\",\"ytUrl\":\"https://www.youtube.com/watch?v=dQw4w9WgXcQ\",\"title\":\"The stopped video\",\"count\":3}")
                .commit();
    }

    static String project(Context c, String id, int done, int total) throws Exception {
        Project p = new Project();
        p.id = id; p.url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"; p.title = "The stopped video";
        for (int i = 0; i < total; i++) {
            Project.Clip cl = new Project.Clip();
            cl.index = i; cl.start = i * 100; cl.end = cl.start + 50;
            if (i < done) {
                File f = new File(p.dir(c), "Klipora_short" + (i + 1) + ".mp4");
                byte[] b = new byte[5000]; Files.write(f.toPath(), b);
                cl.outPath = f.getAbsolutePath();
            }
            p.clips.add(cl);
        }
        p.save(c);
        return id;
    }

    @Test public void stoppedJobCardAndFullReport() throws Exception {
        Context c = ApplicationProvider.getApplicationContext();
        marker(c, 10 * 60_000, null);
        Files.write(new File(c.getFilesDir(), "session_prev.log").toPath(),
                "11-02 19:10:01 Reading the video link\n11-02 19:14:30 Rendering shorts 2 of 5\n11-02 19:15:02 Screen off - still processing\n".getBytes());
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(500);
        assertEquals(View.VISIBLE, a.findViewById(R.id.cardError).getVisibility());
        assertEquals("Processing was stopped", ((TextView) a.findViewById(R.id.tvErrorTitle)).getText().toString());
        assertEquals(View.VISIBLE, a.findViewById(R.id.btnErrorFix).getVisibility());
        assertEquals("Retry", ((MaterialButton) a.findViewById(R.id.btnErrorRetry)).getText().toString());
        assertFalse("marker consumed", Prefs.sp(c).getBoolean("job_alive", false));
        shot(a, "v30_01_stopped_card");
        a.findViewById(R.id.btnErrorReport).performClick();
        idle(300);
        String body = ((ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString();
        System.out.println("V30 REPORT:\n" + body);
        assertTrue(body, body.contains("Video: The stopped video"));
        assertTrue(body, body.contains("URL: https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertFalse(body, body.contains("URL: N/A"));
        assertTrue(body, body.contains("Last step: Rendering shorts 2 of 5 (64%)"));
        assertTrue(body, body.contains("Stopped because: SYSTEM"));
        assertTrue(body, body.contains("Background mode: on (foreground service, data sync)"));
        assertTrue(body, body.contains("Battery optimisation:"));
        assertTrue(body, body.contains("=== LOGS OF THE STOPPED JOB ===") && body.contains("Screen off - still processing"));
        // a second open of the app says nothing more
        MainActivity b = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(500);
        assertNotEquals(View.VISIBLE, b.findViewById(R.id.cardError).getVisibility());
    }

    @Test public void staleMarkerSaysNothing() {
        Context c = ApplicationProvider.getApplicationContext();
        marker(c, 3L * 24 * 3600_000, null);   // a job from 3 days ago
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(500);
        assertNotEquals(View.VISIBLE, a.findViewById(R.id.cardError).getVisibility());
        assertFalse(Prefs.sp(c).getBoolean("job_alive", false));
    }

    @Test public void finishedJobWithLostEndWriteSaysNothing() throws Exception {
        Context c = ApplicationProvider.getApplicationContext();
        marker(c, 10 * 60_000, project(c, "k30fin", 3, 3));
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(500);
        assertNotEquals(View.VISIBLE, a.findViewById(R.id.cardError).getVisibility());
        assertEquals("k30fin", Prefs.sp(c).getString("current_project", ""));
    }

    @Test public void continueWhenSomeShortsAreDone() throws Exception {
        Context c = ApplicationProvider.getApplicationContext();
        marker(c, 10 * 60_000, project(c, "k30part", 1, 3));
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(500);
        assertEquals(View.VISIBLE, a.findViewById(R.id.cardError).getVisibility());
        assertEquals("Continue", ((MaterialButton) a.findViewById(R.id.btnErrorRetry)).getText().toString());
        assertTrue(((TextView) a.findViewById(R.id.tvErrorMsg)).getText().toString().contains("1 of 3 shorts are already done"));
        shot(a, "v30_02_continue_card");
    }

    @Test public void crashDuringJobIsNotBlamedOnBattery() {
        Context c = ApplicationProvider.getApplicationContext();
        marker(c, 10 * 60_000, null);
        Prefs.sp(c).edit().putString("app_crash_log_data", "Thread: klipora-pipeline\njava.lang.IllegalStateException: boom").putLong("app_crash_at", System.currentTimeMillis() - 5 * 60_000).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(500);
        assertEquals("Klipora stopped because of an error", ((TextView) a.findViewById(R.id.tvErrorTitle)).getText().toString());
        assertFalse(((TextView) a.findViewById(R.id.tvErrorMsg)).getText().toString().toLowerCase().contains("battery"));
        assertNotEquals(View.VISIBLE, a.findViewById(R.id.btnErrorFix).getVisibility());
        a.findViewById(R.id.btnErrorReport).performClick();
        idle(300);
        String body = ((ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString();
        assertTrue(body, body.contains("IllegalStateException: boom"));
    }

    @Test public void vivoFixListOpensVivoScreens() throws Exception {
        ReflectionHelpers.setStaticField(Build.class, "MANUFACTURER", "vivo");
        Context c = ApplicationProvider.getApplicationContext();
        marker(c, 10 * 60_000, null);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(500);
        a.findViewById(R.id.btnErrorFix).performClick();
        idle(500);
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(d);
        ListView lv = d.getListView();
        int n = lv.getAdapter().getCount();
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < n; i++) items.append(lv.getAdapter().getItem(i)).append('|');
        assertTrue(items.toString(), items.toString().contains("Background power use") && items.toString().contains("Autostart") && items.toString().contains("Lock Klipora in Recent apps"));
        idle(800);
        View dv = d.getWindow().getDecorView();
        Bitmap bm = Bitmap.createBitmap(Math.max(1, dv.getWidth()), Math.max(1, dv.getHeight()), Bitmap.Config.ARGB_8888);
        dv.draw(new Canvas(bm));
        OUT.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(OUT, "v30_03_vivo_fix_list.png"))) { bm.compress(Bitmap.CompressFormat.PNG, 100, fo); }
        lv.performItemClick(lv.getAdapter().getView(1, null, lv), 1, 1);
        idle(300);
        android.content.Intent started = shadowOf(a).getNextStartedActivity();
        assertNotNull(started);
        assertEquals("com.vivo.abe", started.getComponent().getPackageName());
    }

    @Test public void stepListsPerMaker() {
        List<BackgroundFix.Step> v = BackgroundFix.steps("vivo", "com.viral.shortsmaker", true);
        assertEquals(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, v.get(0).intents.get(0).getAction());
        assertEquals("com.vivo.permissionmanager", v.get(2).intents.get(0).getComponent().getPackageName());
        assertEquals("vivo", BackgroundFix.brand("vivo", "vivo"));
        assertEquals("vivo", BackgroundFix.brand("vivo", "iQOO"));
        assertEquals("xiaomi", BackgroundFix.brand("Xiaomi", "Redmi"));
        assertEquals("oppo", BackgroundFix.brand("realme", "realme"));
        assertEquals("samsung", BackgroundFix.brand("samsung", "samsung"));
        List<BackgroundFix.Step> o = BackgroundFix.steps("other", "com.viral.shortsmaker", false);
        assertEquals(1, o.size());
        assertTrue(o.get(0).done);
        for (String b : new String[]{"vivo", "xiaomi", "oppo", "samsung", "huawei"})
            for (BackgroundFix.Step s : BackgroundFix.steps(b, "com.viral.shortsmaker", false))
                assertTrue(b + ": " + s.label, s.tip != null || !s.intents.isEmpty());
    }
}
