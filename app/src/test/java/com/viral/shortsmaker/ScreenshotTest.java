package com.viral.shortsmaker;

import static org.junit.Assert.*;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.captions.CaptionPreviewView;
import com.viral.shortsmaker.captions.CaptionStyles;
import com.viral.shortsmaker.captions.Word;
import com.viral.shortsmaker.core.AppLog;
import com.viral.shortsmaker.core.FfmpegCommands;
import com.viral.shortsmaker.core.JobManager;
import com.viral.shortsmaker.core.Prefs;
import com.viral.shortsmaker.core.Project;

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
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi")
public class ScreenshotTest {
    private static final File OUT = new File(System.getProperty("shotDir", "build/shots"));

    private void idle(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    private void shot(Activity a, String name) throws Exception {
        idle(1500);
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        OUT.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(OUT, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, fo); }
    }

    @Before public void keys() {
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit()
                .putString("gemini_key", "AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY12")
                .putString("groq_key", "gsk_DUMMYDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY1234")
                .putBoolean("is_onboarding_done", true).apply();
        JobManager.get().removeAllListeners();
        JobManager.get().project = null;
        JobManager.get().reset();
        org.robolectric.RuntimeEnvironment.setFontScale(1.0f);
    }

    static Bitmap demoFrame() {
        String p = System.getProperty("demoFrame", "/home/user/tab/work/klipora_demo/frame169.jpg");
        Bitmap b = new File(p).exists() ? BitmapFactory.decodeFile(p) : null;
        return b != null ? b : Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
    }

    @Test public void onboarding() throws Exception {
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit().clear().apply();
        shot(Robolectric.buildActivity(OnboardingActivity.class).setup().get(), "01_onboarding");
    }

    @Test public void homeWithVideo() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((TextView) a.findViewById(R.id.tvVideoTitle)).setText("What Are the Dangers of Going to Space? We Asked a NASA Expert");
        ((TextView) a.findViewById(R.id.tvVideoDuration)).setText("1:28");
        ((android.widget.ImageView) a.findViewById(R.id.ivVideoThumb)).setImageBitmap(demoFrame());
        a.findViewById(R.id.layoutVideoInfo).setVisibility(View.VISIBLE);
        a.findViewById(R.id.cardControls).setVisibility(View.VISIBLE);
        ((com.google.android.material.textfield.TextInputEditText) a.findViewById(R.id.etYtUrlHome)).setText("https://youtu.be/dQw4w9WgXcQ");
        shot(a, "02_home");
    }

    static void fakeProgress(int stage, double f, String status, String detail) {
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit().putBoolean("logs_enabled", false).apply();
        JobManager jm = JobManager.get();
        jm.start(JobManager.Kind.ANALYZE);
        jm.title = "What Are the Dangers of Going to Space? We Asked a NASA Expert";
        jm.plan(new double[]{8, 6, 9, 40, 0});
        long now = System.currentTimeMillis();
        double[] took = {6.4, 4.1, 8.7};
        long t = now - 30000;
        for (int i = 0; i < stage; i++) { jm.t0[i] = t; t += (long) (took[i] * 1000); jm.t1[i] = t; }
        jm.startedAt = now - 30000;
        jm.step(stage, f, status, detail);
        jm.t0[stage] = t;
        jm.tick();
        jm.notifyNow();
    }

    @Test public void processing() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        AppLog.i("Video engine ready (812 ms)");
        AppLog.i("Transcript: 18 lines, 209 timed words (en)");
        AppLog.i("Clip #1 • score 96 • 00:25–00:58");
        AppLog.i("Rendering 2 shorts at the same time (8 CPU cores)");
        fakeProgress(3, 0.42, "Rendering shorts with captions", "1 of 3 done · #2 rendering 40% · #3 tracking faces 73%");   // v22: one line for every short
        shot(a, "03_processing");
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi") public void processingDownload720px() throws Exception {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        fakeProgress(0, 0.37, "Downloading audio", "2.0 of 5.4 MB • 1.2 MB/s");
        shot(a, "03b_processing_720px_font130");
    }

    static Project demoProject(String title, int n, long created) throws Exception {
        android.content.Context c = ApplicationProvider.getApplicationContext();
        Project p = new Project();
        p.title = title; p.createdAt = created; p.id = String.valueOf(created);
        String[] reasons = {"Strong hook: 'radiation is coming at them from all directions' + DNA damage payoff",
                "Relatable sunscreen analogy, clean ending", "Bone & muscle loss reveal with a clear answer"};
        for (int i = 0; i < n; i++) {
            Project.Clip cl = new Project.Clip();
            cl.index = i; cl.start = 25 + i * 20; cl.end = cl.start + 30 + i * 4; cl.score = 96 - i * 5; cl.reason = reasons[i % 3];
            File f = new File(p.dir(c), "short" + i + ".mp4");
            try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(new byte[30000]); }
            cl.outPath = f.getAbsolutePath(); cl.captionStyle = i == 2 ? "" : (i == 0 ? "Hormozi" : "One Word");
            p.clips.add(cl);
        }
        try (FileOutputStream fo = new FileOutputStream(p.thumbFile(c))) { demoFrame().compress(Bitmap.CompressFormat.JPEG, 80, fo); }
        p.save(c);
        return p;
    }

    @Test public void history() throws Exception {
        long now = System.currentTimeMillis();
        demoProject("What Are the Dangers of Going to Space? We Asked a NASA Expert", 3, now - 3600000L);
        demoProject("Podcast: Desi founders on building in India (full episode)", 2, now - 86400000L);
        demoProject("Gaming highlights – insane clutch moments", 1, now - 3 * 86400000L);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((com.google.android.material.bottomnavigation.BottomNavigationView) a.findViewById(R.id.bottomNav)).setSelectedItemId(R.id.nav_history);
        for (int i = 0; i < 10; i++) { Thread.sleep(150); idle(100); }
        shot(a, "07_history");
    }

    @Test public void historyEmpty() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((com.google.android.material.bottomnavigation.BottomNavigationView) a.findViewById(R.id.bottomNav)).setSelectedItemId(R.id.nav_history);
        for (int i = 0; i < 6; i++) { Thread.sleep(150); idle(100); }
        shot(a, "08_history_empty");
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi") public void results720pxBigFont() throws Exception {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f);
        Project p = demoProject("What Are the Dangers of Going to Space?", 3, System.currentTimeMillis());
        JobManager.get().project = p;
        JobManager.get().finish(JobManager.State.DONE, null);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        shot(a, "04b_results_720px_font130");
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi") public void settings720pxBigFont() throws Exception {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((com.google.android.material.bottomnavigation.BottomNavigationView) a.findViewById(R.id.bottomNav)).setSelectedItemId(R.id.nav_settings);
        idle(600);
        shot(a, "06b_settings_720px_font130");
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi") public void home720pxBigFont() throws Exception {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((TextView) a.findViewById(R.id.tvVideoTitle)).setText("What Are the Dangers of Going to Space? We Asked a NASA Expert");
        ((TextView) a.findViewById(R.id.tvVideoDuration)).setText("1:28");
        ((android.widget.ImageView) a.findViewById(R.id.ivVideoThumb)).setImageBitmap(demoFrame());
        a.findViewById(R.id.layoutVideoInfo).setVisibility(View.VISIBLE);
        a.findViewById(R.id.cardControls).setVisibility(View.VISIBLE);
        shot(a, "02b_home_720px_font130");
    }

    @Test public void results() throws Exception {
        Project p = new Project();
        p.title = "What Are the Dangers of Going to Space?";
        String[] reasons = {"Strong hook: 'radiation is coming at them from all directions' + DNA damage payoff",
                "Relatable sunscreen analogy, clean ending", "Bone & muscle loss reveal with a clear answer"};
        for (int i = 0; i < 3; i++) {
            Project.Clip c = new Project.Clip();
            c.index = i; c.start = 25 + i * 20; c.end = c.start + 30 + i * 4; c.score = 96 - i * 5; c.reason = reasons[i];
            c.outPath = "/nonexistent/" + i + ".mp4"; c.captionStyle = i == 2 ? "" : (i == 0 ? "Hormozi" : "Karaoke");
            if (i == 0) c.reason = "Radiation from all directions — " + reasons[i];
            p.clips.add(c);
        }
        JobManager.get().project = p;
        JobManager.get().finish(JobManager.State.DONE, null);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        shot(a, "04_results");
    }

    @Test public void resultsV7() throws Exception {
        Project p = new Project();
        p.title = "Abhishek Kar podcast: money, success & mindset";
        String[][] d = {{"Paise ki asli value kya hai?", "Paise ki asli value", "92", "88", "90", "86"},
                {"मेरा जन्म पंजाब में हुआ, और वहीं से सब शुरू हुआ", "Punjab se Bombay tak", "85", "84", "80", "82"},
                {"Most people never learn this about saving money.", "The saving mistake", "80", "78", "84", "75"}};
        for (int i = 0; i < 3; i++) {
            Project.Clip c = new Project.Clip();
            c.index = i; c.start = 125 + i * 140; c.end = c.start + 34 + i * 6;
            c.hook = d[i][0]; c.title = d[i][1];
            c.hookScore = Integer.parseInt(d[i][2]); c.standaloneScore = Integer.parseInt(d[i][3]); c.valueScore = Integer.parseInt(d[i][4]); c.payoffScore = Integer.parseInt(d[i][5]);
            c.score = (int) Math.round(.35 * c.hookScore + .2 * c.standaloneScore + .25 * c.valueScore + .2 * c.payoffScore);
            c.reason = c.title + " — clear opener + payoff";
            c.outPath = "/nonexistent/" + i + ".mp4"; c.captionStyle = i == 2 ? "" : "Hormozi";
            p.clips.add(c);
        }
        JobManager.get().project = p;
        JobManager.get().finish(JobManager.State.DONE, null);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        shot(a, "v7_results");
    }

    @Test public void captionStudio() throws Exception {
        CaptionStudioActivity.pendingFrame = demoFrame();
        CaptionStudioActivity.pendingFrameMode = FfmpegCommands.Frame.BLUR;
        CaptionStudioActivity a = Robolectric.buildActivity(CaptionStudioActivity.class, new Intent().putExtra(CaptionStudioActivity.EXTRA_CLIP, -1)).setup().get();
        CaptionPreviewView pv = a.findViewById(R.id.preview);
        pv.setAnimate(false);
        List<Word> ws = new ArrayList<>();
        String[] t = {"Radiation", "is", "coming", "at", "them", "from", "all", "directions"};
        double s = 0; for (String x : t) { ws.add(new Word(x, s, s + 0.35)); s += 0.4; }
        pv.setSampleWords(ws);
        shot(a, "05_caption_studio");
    }

    @Test public void captionStudioHindi() throws Exception {
        androidx.test.core.app.ApplicationProvider.getApplicationContext();
        com.viral.shortsmaker.core.Prefs.setCaptionScript(androidx.test.core.app.ApplicationProvider.getApplicationContext(), com.viral.shortsmaker.core.CaptionScript.HINDI);
        CaptionStudioActivity.pendingFrame = demoFrame();
        CaptionStudioActivity.pendingFrameMode = FfmpegCommands.Frame.BLUR;
        CaptionStudioActivity a = Robolectric.buildActivity(CaptionStudioActivity.class, new Intent().putExtra(CaptionStudioActivity.EXTRA_CLIP, -1)).setup().get();
        ((CaptionPreviewView) a.findViewById(R.id.preview)).setAnimate(false);
        shot(a, "05b_caption_studio_hindi");
        com.viral.shortsmaker.core.Prefs.setCaptionScript(androidx.test.core.app.ApplicationProvider.getApplicationContext(), com.viral.shortsmaker.core.CaptionScript.HINGLISH);
    }

    @Test public void settings() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((com.google.android.material.bottomnavigation.BottomNavigationView) a.findViewById(R.id.bottomNav)).setSelectedItemId(R.id.nav_settings);
        idle(600);
        shot(a, "06_settings");
    }

    // ---------------------------------------------------------------- 2.0 screens

    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi") public void errorCard720pxBigFont() throws Exception {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        JobManager.get().start(JobManager.Kind.ANALYZE);
        idle(300);
        JobManager.get().finish(JobManager.State.FAILED, "Could not render short #1. FFmpeg failed (code 1): Error while decoding stream #0:0: Invalid data found when processing input");
        idle(600);
        System.out.println("DBG state=" + JobManager.get().state + " err=" + JobManager.get().error + " title=" + ((TextView) a.findViewById(R.id.tvErrorTitle)).getText()
                + " vis=" + a.findViewById(R.id.cardError).getVisibility() + " panel=" + a.findViewById(R.id.panelInput).getVisibility());
        assertEquals(View.VISIBLE, a.findViewById(R.id.cardError).getVisibility());
        assertEquals(View.VISIBLE, a.findViewById(R.id.btnErrorReport).getVisibility());
        shot(a, "07_error_card_720px_font130");
    }

    @Test public void killedJobShowsErrorNotSilence() throws Exception {
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit().putBoolean("job_alive", true).apply();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(500);
        assertEquals(View.VISIBLE, a.findViewById(R.id.cardError).getVisibility());
        assertEquals(View.VISIBLE, a.findViewById(R.id.btnErrorFix).getVisibility());
        shot(a, "08_killed_job");
    }

    @Test public void processingHasNoLiveLogs() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertNull(a.findViewById(getId(a, "tvConsoleLogs")));
        assertNull(a.findViewById(getId(a, "layoutDiagnosticsContainer")));
        assertNull("no in-app logo", a.findViewById(getId(a, "ivHeaderLogo")));
    }

    static int getId(Activity a, String name) { return a.getResources().getIdentifier(name, "id", a.getPackageName()); }

    @Test public void logsSheetInSettings() throws Exception {
        com.viral.shortsmaker.core.AppLog.i("Speech: 24 parts, 16.7 MB");
        com.viral.shortsmaker.core.AppLog.i("AI model: gemini-3.8-flash");
        com.viral.shortsmaker.core.AppLog.e("Hardware encoder failed (Unknown encoder), switching to software encoder");
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((com.google.android.material.bottomnavigation.BottomNavigationView) a.findViewById(R.id.bottomNav)).setSelectedItemId(R.id.nav_settings);
        idle(400);
        a.findViewById(R.id.rowLogs).performClick();
        idle(800);
        android.app.Dialog d = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(d);
        View root = d.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(Math.max(1, root.getWidth()), Math.max(1, root.getHeight()), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        OUT.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(OUT, "09_logs_sheet.png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, fo); }
    }

    @Test public void captionsAndHdAreLockedUntilAd() throws Exception {
        org.junit.Assume.assumeFalse("rewarded-ad unlock rule of the normal app; premium edition: PremiumEditionTest", com.viral.shortsmaker.core.Prefs.PREMIUM_EDITION);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(300);
        com.google.android.material.materialswitch.MaterialSwitch sw = a.findViewById(R.id.switchCaptions);
        assertFalse("captions off while locked", sw.isChecked());
        sw.performClick();
        idle(300);
        assertFalse("still off: unlock dialog shown instead", sw.isChecked());
        assertNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog());
        // after the ad reward
        Prefs.unlockFeature(a, Prefs.F_CAPTIONS);
        org.robolectric.shadows.ShadowDialog.getLatestDialog().dismiss();
        sw.performClick();
        idle(300);
        assertTrue(sw.isChecked());
        assertTrue(Prefs.captionsUnlocked(a));
        assertFalse(Prefs.hdUnlocked(a));
        Prefs.unlockFeature(a, Prefs.F_HD);
        assertTrue(Prefs.hdUnlocked(a));
    }

    /** whole scrolling Create tab as one tall image (checks every card for clipping) */
    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi") public void homeTall720pxBigFont() throws Exception {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((TextView) a.findViewById(R.id.tvVideoTitle)).setText("How To Build High Status, Wealth & Powerful Relationships | Rob Henderson");
        ((TextView) a.findViewById(R.id.tvVideoDuration)).setText("2:14:51");
        ((android.widget.ImageView) a.findViewById(R.id.ivVideoThumb)).setImageBitmap(demoFrame());
        a.findViewById(R.id.layoutVideoInfo).setVisibility(View.VISIBLE);
        a.findViewById(R.id.cardControls).setVisibility(View.VISIBLE);
        idle(800);
        android.view.ViewGroup sv = a.findViewById(R.id.tabCreate);
        View child = sv.getChildAt(0);
        int w = sv.getWidth();
        child.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        child.layout(0, 0, w, child.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(w, child.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF0A0A10);
        child.draw(new Canvas(b));
        OUT.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(OUT, "02c_home_tall_720px_font130.png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, fo); }
    }
}
