package com.viral.shortsmaker.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.viral.shortsmaker.MainActivity;
import com.viral.shortsmaker.R;

/**
 * Foreground service that owns every long job (download, AI, render, captions). Keeps running with the app in the
 * background or the screen off: partial wakelock + wifi lock, live progress notification with Cancel,
 * and a "shorts ready" notification at the end. Results go to JobManager (observed by the UI).
 */
public class ProcessingService extends Service {
    public static final String CH_PROGRESS = "Klipora_Background";
    public static final String CH_DONE = "Klipora_Done";
    static final int NID = 1001, NID_DONE = 1002;
    static final String ACTION_CANCEL = "com.viral.shortsmaker.CANCEL";

    private static volatile Pipeline.Request pending;
    private static volatile Pipeline current;

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private Thread worker;
    private long lastNotif;
    private String lastNotifText = "";
    private int lastNotifPct = -2;

    public static boolean start(Context c, Pipeline.Request rq) {
        if (JobManager.get().isRunning()) return false;
        pending = rq;
        JobManager.get().captionRemove = rq.kind == JobManager.Kind.CAPTION && rq.style == null;
        JobManager.get().start(rq.kind);
        // code 30: the marker carries the job (link, title, pid, build, boot) and is written synchronously
        JobGuard.markStart(c, rq, com.viral.shortsmaker.BuildConfig.VERSION_CODE);
        try {
            ContextCompat.startForegroundService(c, new Intent(c, ProcessingService.class));
        } catch (Throwable e) {
            // Android 12+ can refuse a foreground service (background start limits, some OEM ROMs). Never fail silently:
            // run the job in-process instead (works while the app is open) and say so in the logs.
            AppLog.e("Background mode refused by the phone (" + e.getClass().getSimpleName() + "), running in the app");
            JobGuard.foreground(c, "refused by the phone (" + e.getClass().getSimpleName() + "), ran inside the app");
            Pipeline.Request r = pending;
            pending = null;
            if (r != null) runInline(c.getApplicationContext(), r);
        }
        return true;
    }

    /** fallback when the foreground service cannot start */
    private static void runInline(Context app, Pipeline.Request rq) {
        new Thread(() -> {
            Pipeline p = new Pipeline(app);
            current = p;
            execute(app, p, rq);
            current = null;
        }, "klipora-pipeline-inline").start();
    }

    /** runs one job and always ends it in JobManager (DONE / CANCELLED / FAILED with the real message) */
    static String execute(Context ctx, Pipeline p, Pipeline.Request rq) {
        final JobManager jm = JobManager.get();
        String done = null;
        try {
            if (rq.kind == JobManager.Kind.ANALYZE) {
                // code 30: Continue after the phone stopped a job: finished shorts are kept, only the rest is made
                Project pr = rq.resumeId != null && !rq.resumeId.isEmpty() ? p.runResume(rq) : p.runAnalyze(rq);
                jm.project = pr;
                done = pr.clips.size() + (pr.clips.size() == 1 ? " short is" : " shorts are") + " ready";
            } else {
                Project pr = jm.project;
                if (pr == null) throw new Exception("No project open");
                p.runCaptions(rq, pr);
                done = rq.style == null ? "Captions removed" : "Captions added";
            }
            synchronized (p.warnings) { jm.warning = p.warnings.isEmpty() ? null : android.text.TextUtils.join("\n", p.warnings); }
            AppLog.i("✓ " + done);
            jm.finish(JobManager.State.DONE, null);
        } catch (CancelToken.Cancelled c) {
            AppLog.e("Cancelled by user");
            if (rq.kind == JobManager.Kind.CAPTION) jm.finish(JobManager.State.DONE, null);
            else jm.finish(JobManager.State.CANCELLED, null);
        } catch (Throwable e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            if (e instanceof OutOfMemoryError) msg = "Your phone ran out of memory while processing. Close other apps and retry (or pick fewer shorts / 720p).";
            else if (Pipeline.isNoSpace(e)) msg = Pipeline.NO_SPACE_MSG;   // code 29
            msg = KeyCheck.tidy(msg);   // 5.3: "...API key.. Update it" never shows a doubled period
            AppLog.e("FAILED: " + msg);
            if (rq.kind == JobManager.Kind.CAPTION) { jm.status = msg; jm.finish(JobManager.State.DONE, msg); }
            else jm.finish(JobManager.State.FAILED, msg);
        } finally {
            // code 30: commit, not apply - 4.7's delayed write could be lost when the phone froze Klipora right after the
            // job, and the next start then said "stopped by the phone" about a job that had finished
            JobGuard.markEnd(ctx, done != null);
        }
        return done;
    }

    public static void cancel(Context c) {
        Pipeline p = current;
        if (p != null) p.cancel();
    }

    public static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            NotificationChannel a = new NotificationChannel(CH_PROGRESS, "Processing progress", NotificationManager.IMPORTANCE_LOW);
            a.setDescription("Live progress while Klipora creates your shorts");
            a.setShowBadge(false);
            NotificationChannel b = new NotificationChannel(CH_DONE, "Shorts ready", NotificationManager.IMPORTANCE_DEFAULT);
            b.setDescription("Tells you when your shorts are ready");
            nm.createNotificationChannel(a);
            nm.createNotificationChannel(b);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancel(this);
            return START_NOT_STICKY;
        }
        createChannels(this);
        Notification n = progressNotification("Starting…", 0, true);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NID, n);
            JobGuard.foreground(this, "on (foreground service, data sync)" + (notificationsAllowed() ? "" : ", notifications off"));
        } catch (Exception e) {
            AppLog.e("Could not start background mode: " + e.getMessage());
            JobGuard.foreground(this, "refused (" + e.getClass().getSimpleName() + ")");
        }
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;
        final Pipeline.Request rq = pending;
        pending = null;
        if (rq == null) {
            if (!JobManager.get().isRunning()) stopSelfClean();
            return START_NOT_STICKY;
        }
        acquireLocks();
        watchScreen(true);
        final JobManager jm = JobManager.get();
        final JobManager.Listener l = j -> updateProgressNotification();
        jm.addListener(l);
        worker = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            Pipeline p = new Pipeline(this);
            current = p;
            String done = null;
            try {
                done = execute(this, p, rq);
            } finally {
                current = null;
                jm.removeListener(l);
                final String fd = done;
                Net.main(() -> { postDone(fd); stopSelfClean(); });
            }
        }, "klipora-pipeline");
        worker.start();
        return START_NOT_STICKY;
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Klipora::Processing");
            wake.setReferenceCounted(false);
            wake.acquire(WAKE_HOLD_MS);
            wakeAt = System.currentTimeMillis();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifi = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_HIGH_PERF : WifiManager.WIFI_MODE_FULL, "Klipora::Wifi");
                wifi.setReferenceCounted(false);
                wifi.acquire();
            }
        } catch (Exception e) { AppLog.e("Wake lock: " + e.getMessage()); }
    }

    /**
     * code 28: the CPU wake lock is held in 1-hour pieces and renewed while the job reports progress (4.5 took one
     * fixed 3-hour lock: a very long job could lose it with the screen off; a stuck one held it for 3 hours).
     */
    static final long WAKE_HOLD_MS = 60 * 60 * 1000L, WAKE_RENEW_MS = 10 * 60 * 1000L;
    private volatile long wakeAt;

    /** pure: is it time to renew the wake lock? */
    static boolean wakeRenewDue(long acquiredAt, long now) { return acquiredAt > 0 && now - acquiredAt >= WAKE_RENEW_MS; }

    private void renewWake() {
        try {
            PowerManager.WakeLock w = wake;
            long now = System.currentTimeMillis();
            if (w == null || !wakeRenewDue(wakeAt, now)) return;
            w.acquire(WAKE_HOLD_MS);   // not reference counted: this extends the timeout
            wakeAt = now;
        } catch (Exception ignored) {}
    }

    /** code 28: notification title with the percent for every kind of job (captions had none) */
    static String progressTitle(JobManager.Kind kind, int pct) {
        String head = kind == JobManager.Kind.CAPTION ? "Adding captions" : "Creating shorts";
        return pct > 0 ? head + " • " + Math.min(100, pct) + "%" : head;
    }

    private void releaseLocks() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) {}
    }

    private void stopSelfClean() {
        releaseLocks();
        watchScreen(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        watchScreen(false);
        super.onDestroy();
    }

    @Override
    public void onTimeout(int startId) {
        // Android 15 data-sync time limit: stop cleanly instead of crashing
        cancel(this);
        stopSelfClean();
    }

    /**
     * code 30: Android 15's form of the time limit (called only for apps that target Android 15; this build targets 14,
     * where data sync has no limit). Declared without @Override so it compiles against SDK 34.
     */
    public void onTimeout(int startId, int fgsType) {
        AppLog.e("Android's time limit for background work was reached");
        cancel(this);
        stopSelfClean();
    }

    /**
     * code 30: the user swiped Klipora away in Recent apps. Stock Android keeps this foreground service running; some
     * phones (vivo, Xiaomi, OPPO) end the whole app right after. Recorded synchronously so the next start can say what
     * really happened instead of blaming battery saver.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (JobManager.get().isRunning()) {
            AppLog.i("Klipora was removed from Recent apps - processing keeps going in the background");
            JobGuard.taskRemoved(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    private android.content.BroadcastReceiver screenRx;

    /** code 30: notes when the screen goes off / on during a job (for the report when a phone stops the job) */
    private void watchScreen(boolean on) {
        try {
            if (on && screenRx == null) {
                screenRx = new android.content.BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) { AppLog.i("Screen off - still processing"); JobGuard.screenOff(c); }
                        else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) AppLog.i("Screen on");
                    }
                };
                android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
                f.addAction(Intent.ACTION_SCREEN_ON);
                ContextCompat.registerReceiver(this, screenRx, f, ContextCompat.RECEIVER_NOT_EXPORTED);
            } else if (!on && screenRx != null) {
                unregisterReceiver(screenRx);
                screenRx = null;
            }
        } catch (Exception ignored) { if (!on) screenRx = null; }
    }

    private boolean notificationsAllowed() {
        try { return NotificationManagerCompat.from(this).areNotificationsEnabled(); } catch (Exception e) { return true; }
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification progressNotification(String text, int pct, boolean indeterminate) {
        Intent ci = new Intent(this, ProcessingService.class).setAction(ACTION_CANCEL);
        PendingIntent cancel = PendingIntent.getService(this, 1, ci, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        JobManager jm = JobManager.get();
        String title = progressTitle(jm.kind, pct);
        return new NotificationCompat.Builder(this, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_klipora)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, pct, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setColor(0xFF7C5CFF)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openApp())
                .addAction(0, "Cancel", cancel)
                .build();
    }

    private void updateProgressNotification() {
        JobManager jm = JobManager.get();
        if (!jm.isRunning()) return;
        renewWake();   // code 28
        JobGuard.beat(this, jm.status, jm.percent);   // code 30: last step + sign of life for the report if the phone stops the job
        long now = System.currentTimeMillis();
        if (now - lastNotif < 1000) return;
        String text = jm.status + (jm.etaSec > 0 ? " • about " + MainEta.fmt(jm.etaSec) + " left" : "");
        // v18: only when something visible changed (2.8 re-posted the same notification ~1.4x a second: wasted
        // wake-ups of the system UI while the screen is off)
        if (text.equals(lastNotifText) && jm.percent == lastNotifPct) return;
        lastNotif = now; lastNotifText = text; lastNotifPct = jm.percent;
        try { NotificationManagerCompat.from(this).notify(NID, progressNotification(text, jm.percent, jm.percent <= 0)); } catch (SecurityException ignored) {}
    }

    private void postDone(String text) {
        JobManager jm = JobManager.get();
        String title, body;
        if (jm.state == JobManager.State.DONE) { title = "✨ " + (text == null ? "Done" : text); body = "Tap to watch, caption and save"; }
        else if (jm.state == JobManager.State.CANCELLED) return;
        else { title = "Couldn't finish"; body = jm.error; }
        Notification n = new NotificationCompat.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_klipora)
                .setContentTitle(title).setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true).setColor(0xFF7C5CFF)
                .setContentIntent(openApp()).build();
        try { NotificationManagerCompat.from(this).notify(NID_DONE, n); } catch (SecurityException ignored) {}
    }
}
