package com.viral.shortsmaker;

import android.app.Application;
import android.util.Log;

import com.viral.shortsmaker.core.MediaRunner;
import com.viral.shortsmaker.core.Prefs;
import com.viral.shortsmaker.core.ProcessingService;
import com.viral.shortsmaker.core.YtEngine;

import java.io.PrintWriter;
import java.io.StringWriter;

public class KliporaApp extends Application {
    /** v21: the Activity in front (Klipora's or the ad's own screen), so ad link clicks start from a visible screen */
    private static volatile java.lang.ref.WeakReference<android.app.Activity> front = new java.lang.ref.WeakReference<>(null);

    public static android.app.Activity frontActivity() { return front.get(); }

    /**
     * v21 (4.0): Unity Ads opens an ad's "Install" link with startActivity on this Application (4.12 had no fallback, so
     * a phone that refused the launch - ColorOS can - showed nothing). Ad links (VIEW market:/http(s): with no explicit
     * component) are started from the screen in front with fallbacks (see LinkOpener). Everything else is unchanged.
     */
    @Override
    public void startActivity(android.content.Intent intent) {
        if (!com.viral.shortsmaker.core.LinkOpener.isAdLink(intent)) { super.startActivity(intent); return; }
        // v5.5: a banner click happens on Klipora's own screen: let Unity open it exactly as it does (no re-launch from here);
        // the fallbacks still run if the phone refuses. Rewarded ad screens (Unity's own Activity) keep the v4.0 path.
        android.app.Activity fr = frontActivity();
        if (fr instanceof MainActivity) fr = null;
        com.viral.shortsmaker.core.LinkOpener.open(this, fr, intent, (from, i) -> {
            if (from == this) super.startActivity(i); else from.startActivity(i);
        });
    }

    @Override
    public void startActivity(android.content.Intent intent, android.os.Bundle options) {
        if (options != null || !com.viral.shortsmaker.core.LinkOpener.isAdLink(intent)) { super.startActivity(intent, options); return; }
        startActivity(intent);
    }

    /** code 31: true in the ":render" process */
    boolean isRenderProcess() {
        String n = null;
        try { if (android.os.Build.VERSION.SDK_INT >= 28) n = Application.getProcessName(); } catch (Throwable ignored) {}
        if (n == null) {
            try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
                byte[] b = new byte[256]; int r = in.read(b); int e = 0;
                while (e < Math.max(0, r) && b[e] != 0) e++;
                n = new String(b, 0, e, "UTF-8");
            } catch (Throwable ignored) {}
        }
        return n != null && n.endsWith(":render");
    }

    private static final java.util.concurrent.atomic.AtomicBoolean faceChecked = new java.util.concurrent.atomic.AtomicBoolean();

    /** code 32: the face engine check, once per app process, from the main screen */
    public static void selfCheckOnce(android.content.Context c) {
        if ("robolectric".equals(android.os.Build.FINGERPRINT) || !faceChecked.compareAndSet(false, true)) return;
        com.viral.shortsmaker.core.FaceAnalyzer.selfCheck(c);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // code 31 (4.9): the separate video process (RenderService, ":render") starts nothing of the app: no log rotation,
        // no ads, no face engine, no yt-dlp, no crash marker (a crash there is handled by the job, not shown as an app crash)
        if (isRenderProcess()) return;
        com.viral.shortsmaker.core.MediaRunner.appCtx = this;
        // code 30: the visible log is kept on disk per app session (the report about a stopped job carries that job's log)
        try { com.viral.shortsmaker.core.AppLog.startSession(getFilesDir(), Prefs.sp(this).getBoolean("job_alive", false)); } catch (Throwable ignored) {}
        Prefs.premiumEditionDefaults(this);   // premium edition only: 1080p + high speed on first start
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityStarted(android.app.Activity a) {}
            @Override public void onActivityResumed(android.app.Activity a) { front = new java.lang.ref.WeakReference<>(a); }
            @Override public void onActivityPaused(android.app.Activity a) {}
            @Override public void onActivityStopped(android.app.Activity a) { if (front.get() == a) front = new java.lang.ref.WeakReference<>(null); }
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityDestroyed(android.app.Activity a) { if (front.get() == a) front = new java.lang.ref.WeakReference<>(null); }
        });
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                Log.e("Klipora", sw.toString());
                Prefs.sp(this).edit().putString("app_crash_log_data", "Thread: " + t.getName() + "\n" + sw)
                        .putLong("app_crash_at", System.currentTimeMillis()).commit();   // code 30: tells a crash from a phone kill
            } catch (Throwable ignored) {}
            if (def != null) def.uncaughtException(t, e);
        });
        try { com.viral.shortsmaker.core.AppLog.debugFile = new java.io.File(getFilesDir(), "klipora_debug.log"); } catch (Throwable ignored) {}
        ProcessingService.createChannels(this);
        YtEngine.initAsync(this);
        // v14: the face engine's one-time check now runs when a Klipora screen opens (selfCheckOnce, code 32): Android also
        // starts the app in the background for a few seconds (the ad SDK's scheduled work), and a kill during the GPU
        // check there switched face tracking to the slow CPU path for good
        new Thread(() -> { try { MediaRunner.fontsDir(this); } catch (Throwable ignored) {} }, "fonts").start();
    }
}
