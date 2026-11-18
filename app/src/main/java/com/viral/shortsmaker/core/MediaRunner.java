package com.viral.shortsmaker.core;

import android.content.Context;
import android.content.res.AssetManager;

import com.antonkarpenko.ffmpegkit.FFmpegKit;
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig;
import com.antonkarpenko.ffmpegkit.FFmpegSession;
import com.antonkarpenko.ffmpegkit.Level;
import com.antonkarpenko.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;

/** Runs FFmpegKit synchronously on the calling (pipeline) thread with progress and cancellation. */
public final class MediaRunner {
    public interface Progress { void on(double fraction); }

    private static volatile boolean fontsReady;

    private MediaRunner() {}

    /** Copies bundled caption fonts to app storage and registers them with fontconfig/libass. */
    public static synchronized File fontsDir(Context c) {
        File dir = new File(c.getFilesDir(), "fonts");
        if (!fontsReady) {
            try {
                if (!dir.exists()) dir.mkdirs();
                AssetManager am = c.getAssets();
                String[] names = am.list("fonts");
                // code 32 (5.0): fonts are copied again once per app build (a font changed in an update reached nobody),
                // and each copy goes to a temp file renamed into place (4.9 wrote in place: a copy cut off by a kill left
                // a broken font that was never replaced; the app and the video process could also copy at the same time)
                File stamp = new File(dir, ".build");
                String build = String.valueOf(com.viral.shortsmaker.BuildConfig.VERSION_CODE);
                boolean fresh = build.equals(readSmall(stamp));
                if (names != null) for (String n : names) {
                    File out = new File(dir, n);
                    if (fresh && out.exists() && out.length() > 0) continue;
                    File tmp = new File(dir, n + "." + android.os.Process.myPid() + ".tmp");
                    try (InputStream in = am.open("fonts/" + n); FileOutputStream fo = new FileOutputStream(tmp)) {
                        byte[] b = new byte[32768]; int r;
                        while ((r = in.read(b)) > 0) fo.write(b, 0, r);
                        fo.getFD().sync();
                    }
                    if (!tmp.renameTo(out)) { out.delete(); if (!tmp.renameTo(out)) tmp.delete(); }
                }
                if (!fresh) try (FileOutputStream fo = new FileOutputStream(stamp)) { fo.write(build.getBytes("UTF-8")); }
                FFmpegKitConfig.setFontDirectory(c, dir.getAbsolutePath(), new HashMap<String, String>());
                com.viral.shortsmaker.captions.CaptionBuilder.measurer = new com.viral.shortsmaker.captions.AndroidMeasurer(dir);
                FFmpegKitConfig.setLogLevel(Level.AV_LOG_ERROR);
                fontsReady = true;
            } catch (Throwable e) {
                AppLog.e("Font setup failed: " + e.getMessage());
            }
        }
        return dir;
    }

    static String readSmall(File f) {
        try { return f.exists() && f.length() < 64 ? new String(IoUtil.readAll(f), "UTF-8").trim() : ""; } catch (Exception e) { return ""; }
    }

    /** code 31: the app (main process only, set by KliporaApp); null = everything runs in this process (tests, the render process itself) */
    public static volatile Context appCtx;
    /** code 31: tests / a phone where the separate process can't start: video work runs in this process */
    public static volatile boolean remoteOff = "robolectric".equals(android.os.Build.FINGERPRINT);

    /**
     * the command as it really runs on this phone. code 33 (5.1): a phone whose FFmpeg software path crashed twice runs every
     * video command CPU-safe (no NEON/asm). code 34 (5.2): FFmpeg's NEON scaler crashed here before (or the chip is of the
     * vivo Y16's family): the same command with NEON off - the lighter step before the CPU-safe rung, nothing else changes.
     * Any other phone: the command unchanged.
     */
    public static String[] prepare(Context c, String[] args) {
        int k = RenderSafety.kindOf(args);
        if (c == null || (k != RenderSafety.K_SOFTWARE && k != RenderSafety.K_HW_ENCODE && k != RenderSafety.K_HW_DECODE)) return args;
        if (RenderSafety.noAsm(c)) return FfmpegCommands.cpuSafe(args);
        if (RenderSafety.noNeon(c)) return FfmpegCommands.noNeon(args);
        return args;
    }

    public static void run(String[] args, double expectedSec, Progress p, CancelToken ct) throws Exception {
        ct.check();
        // code 31 (4.9): video work (decoding / encoding / filters) runs in the separate ":render" process: a native crash
        // there (his HONOR, Android 17: SIGSEGV in the hardware decoder + encoder path) no longer ends the app and the job
        final Context c = appCtx;
        args = prepare(c, args);
        final int kind = RenderSafety.kindOf(args);
        if (kind != RenderSafety.K_NONE && c != null && !remoteOff) {
            Remote.Result r = Remote.get().run(c, args, kind, expectedSec, p, ct);
            if (r != null) { finish(args, r.rc == RenderService.RC_NONE ? null : new ReturnCode(r.rc), r.out, ct); return; }
        }
        if (kind != RenderSafety.K_NONE) RenderSafety.inflight(c, kind);   // a native crash in THIS process: the next start knows what ran
        if (kind == RenderSafety.K_NATIVE) {
            // code 33: the backup engine in this process (no separate process available)
            String err;
            try { err = NativeEngine.runArgs(c, args, t -> { if (p != null && expectedSec > 0) p.on(Math.min(1.0, t / expectedSec)); }, () -> ct.cancelled); }
            finally { RenderSafety.inflightDone(c); }
            if (ct.cancelled) throw new CancelToken.Cancelled();
            if (err != null) throw new Failed("Backup video engine failed: " + err, err);
            return;
        }
        FFmpegSession session = FFmpegSession.create(args, null, null, stats -> {
            if (p != null && expectedSec > 0 && stats.getTime() > 0) p.on(Math.min(1.0, stats.getTime() / 1000.0 / expectedSec));
        });
        long sid = session.getSessionId();
        ct.ffmpegSessionId = sid;
        ct.sessions.add(sid);
        try {
            if (ct.cancelled) throw new CancelToken.Cancelled();
            FFmpegKitConfig.ffmpegExecute(session);
        } finally {
            ct.sessions.remove(sid);
            ct.ffmpegSessionId = -1;
            if (kind != RenderSafety.K_NONE) RenderSafety.inflightDone(c);
        }
        String out = null;
        if (!ReturnCode.isSuccess(session.getReturnCode()) && !ReturnCode.isCancel(session.getReturnCode())) {
            out = session.getOutput();
            if (out == null || out.trim().isEmpty()) out = session.getAllLogsAsString();
        }
        finish(args, session.getReturnCode(), out, ct);
    }

    private static void finish(String[] args, ReturnCode rc, String out, CancelToken ct) throws Exception {
        if (ct.cancelled || ReturnCode.isCancel(rc)) throw new CancelToken.Cancelled();
        if (!ReturnCode.isSuccess(rc)) {
            if (out == null) out = "";
            out = out.trim();
            // v20 (3.1): one short line for the log; the full FFmpeg text goes to the debug file only
            AppLog.debug("FFmpeg failed (code " + rc + ") for: " + String.join(" ", args) + "\n" + (out.length() > 20000 ? out.substring(out.length() - 20000) : out));
            throw new Failed("FFmpeg failed (code " + rc + "): " + summarize(out),
                    out.length() > 6000 ? out.substring(out.length() - 6000) : out);
        }
    }

    /**
     * code 31: the separate video process ended while it ran a command (a native crash in the phone's codec / FFmpeg, or
     * Android ended it). RenderSafety has already moved the phone to the safer path when it was a crash; the caller
     * renders the same thing again.
     */
    public static final class EngineCrash extends Exception {
        public final int kind;
        /** true = a crash (native / app); false = Android ended the process (low memory etc.): same path again */
        public final boolean crash;
        public final String reason, where;
        public final int levelAfter;
        public EngineCrash(int kind, boolean crash, String reason, String where, int levelAfter) {
            super("the video engine " + (crash ? "crashed" : "was stopped by the phone") + " (" + RenderSafety.kindLabel(kind)
                    + (reason == null || reason.isEmpty() ? "" : ", " + reason) + ")");
            this.kind = kind; this.crash = crash; this.reason = reason == null ? "" : reason; this.where = where == null ? "" : where;
            this.levelAfter = levelAfter;
        }
    }

    /** code 31: the client side of RenderService (Messenger IPC). One binding, shared by all renders of the job. */
    static final class Remote implements android.content.ServiceConnection {
        private static final Remote INSTANCE = new Remote();
        static Remote get() { return INSTANCE; }

        static final class Result { int rc; String out; }
        private static final class Call {
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            volatile int rc = RenderService.RC_NONE; volatile String out = ""; volatile boolean died;
            /** code 32: MSG_DONE arrived (a process end after it does not undo a finished command); which death ended it;
             *  the kind of command it runs (the mildest step of one death); the render pid that ran it */
            volatile boolean gotDone; volatile int deathNo; int kindRun; volatile int pid;
            volatile double progressMs = 0;
            /** code 32: the last moment the render process said anything about this command (progress), System.currentTimeMillis */
            volatile long lastBeat = System.currentTimeMillis();
        }

        /**
         * code 32 (5.0): no progress at all from a video command for this long while its process is alive = the phone's
         * codec is stuck (a hung MediaCodec decoder / encoder never returns; 4.9 waited forever and the job sat at the same
         * percent until the user killed the app). The render process is ended and the same short is made on the next safer
         * path. Very generous: a slow phone's software encode still reports progress every second or so.
         */
        static final long STALL_MS = 240_000;
        /** code 32: render-process deaths with no recorded reason, in a row (Android 7-10 keep no exit reasons) */
        private int unknownDeaths;

        static boolean stalled(long lastBeat, long now) { return now - lastBeat > STALL_MS; }

        /**
         * code 32: is this end of the render process a crash (the phone steps down the safe-path ladder)? A recorded native /
         * app crash is; a recorded low-memory or other stop is not; an end with NO record (Android 7-10 never record one, and
         * 11+ sometimes misses it) is a crash from the second one in a row (4.9 treated every unrecorded end as "stopped by
         * the phone", so on Android 7-10 a crashing codec was retried on the same path and never remembered).
         */
        static boolean treatAsCrash(String reason, int unknownInRow) {
            if (reason != null && !reason.isEmpty()) return RenderSafety.isCrashReason(reason);
            return unknownInRow >= 2;
        }

        static boolean networkInput(String[] args) {
            for (int i = 0; i + 1 < args.length; i++) if ("-i".equals(args[i]) && args[i + 1] != null && args[i + 1].startsWith("http")) return true;
            return false;
        }

        private final java.util.concurrent.ConcurrentHashMap<Integer, Call> calls = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicInteger ids = new java.util.concurrent.atomic.AtomicInteger();
        private android.os.HandlerThread thread;
        private android.os.Messenger replies;
        private android.os.Messenger service;
        private android.os.IBinder binder;
        private Context bindCtx;
        private boolean bound;
        private int active;
        private boolean warned;
        /** code 32: the render process's pid (sent with its progress), for ending a stuck one */
        private volatile int renderPid;
        private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        private final Runnable idleUnbind = this::unbindIfIdle;

        private synchronized void ensureThread() {
            if (thread != null) return;
            thread = new android.os.HandlerThread("render-replies");
            thread.start();
            replies = new android.os.Messenger(new android.os.Handler(thread.getLooper(), msg -> {
                Call call = calls.get(msg.arg1);
                if (call == null) return true;
                if (msg.what == RenderService.MSG_PROGRESS) {
                    android.os.Bundle b = msg.getData();
                    if (b != null) call.progressMs = b.getDouble("t", 0);
                    call.lastBeat = System.currentTimeMillis();
                    if (b != null && b.getInt("pid", 0) != 0) { renderPid = b.getInt("pid", 0); call.pid = renderPid; }
                } else if (msg.what == RenderService.MSG_DONE) {
                    android.os.Bundle b = msg.getData();
                    call.rc = msg.arg2;
                    call.out = b == null ? "" : b.getString("out", "");
                    call.gotDone = true;
                    call.done.countDown();
                }
                return true;
            }));
        }

        /** the service's Messenger, binding first if needed (never on the main thread); null = not available */
        private synchronized android.os.Messenger connect(Context c, long waitMs) {
            if (service != null) return service;
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return null;
            ensureThread();
            if (!bound) {
                try {
                    bindCtx = c.getApplicationContext();
                    int flags = Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT;
                    bound = bindCtx.bindService(new android.content.Intent(bindCtx, RenderService.class), this, flags);
                } catch (Throwable t) { bound = false; }
                if (!bound) { if (!warned) { warned = true; AppLog.i("Separate video engine unavailable on this phone, rendering inside the app"); } return null; }
            }
            long until = System.currentTimeMillis() + waitMs;
            while (service == null) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                try { wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            if (service == null && !warned) { warned = true; AppLog.i("Separate video engine did not start in time, rendering inside the app"); }
            return service;
        }

        @Override
        public synchronized void onServiceConnected(android.content.ComponentName name, android.os.IBinder b) {
            binder = b;
            service = new android.os.Messenger(b);
            try { b.linkToDeath(() -> died(b), 0); } catch (android.os.RemoteException e) { died(b); }
            notifyAll();
        }

        @Override
        public void onServiceDisconnected(android.content.ComponentName name) { died(binder); }

        private void died(android.os.IBinder which) {
            int no;
            synchronized (this) {
                if (which != null && binder != which) return;   // an older binding
                service = null; binder = null;
                // a fresh binding next time (a crashed service's automatic restart can be delayed by Android)
                if (bound && bindCtx != null) { try { bindCtx.unbindService(this); } catch (Throwable ignored) {} }
                bound = false;
                no = book.death();
            }
            // code 32: every command that was still running ends with this ONE death (judged once, see DeathBook)
            for (Call call : calls.values()) { if (call.gotDone) continue; call.deathNo = no; call.died = true; call.done.countDown(); }
        }

        /** code 32: one judgement per render-process death */
        final DeathBook book = new DeathBook();

        private synchronized void unbindIfIdle() {
            if (active > 0 || !bound || bindCtx == null) return;
            try { bindCtx.unbindService(this); } catch (Throwable ignored) {}
            bound = false; service = null; binder = null;
        }

        /** null = the separate process is not available: the caller runs the command in this process */
        Result run(Context c, String[] args, int kind, double expectedSec, Progress p, CancelToken ct) throws Exception {
            synchronized (this) { active++; main.removeCallbacks(idleUnbind); }
            android.os.Messenger svc;
            try { svc = connect(c, 8000); } catch (Throwable t) { svc = null; }
            if (svc == null) { synchronized (this) { active = Math.max(0, active - 1); } return null; }
            int id = ids.incrementAndGet();
            Call call = new Call();
            call.kindRun = kind;
            calls.put(id, call);
            long started = System.currentTimeMillis();
            boolean stuck = false;
            long stuckAt = 0;
            try {
                try { svc.send(runMessage(id, args)); }
                catch (android.os.RemoteException e) {
                    // the process ended just now: once more on a fresh binding. code 32: died() has already finished THIS
                    // call (died + latch) - 4.9 re-sent on that finished call, saw it "dead" at once and rendered the same
                    // short again while the re-sent command still ran in the new process (two FFmpegs on one file)
                    died(binder);
                    call = new Call();
                    call.kindRun = kind;
                    calls.put(id, call);
                    svc = connect(c, 8000);
                    if (svc == null) return null;
                    started = System.currentTimeMillis();
                    svc.send(runMessage(id, args));
                }
                boolean cancelSent = false;
                long cancelAt = 0;
                double lastP = -1;
                while (!awaitOrCancel(call, svc, id)) {
                    if (p != null && expectedSec > 0 && call.progressMs > 0 && call.progressMs != lastP) {
                        lastP = call.progressMs;
                        p.on(Math.min(1.0, lastP / 1000.0 / expectedSec));
                    }
                    if (ct.cancelled && !cancelSent) {
                        cancelSent = true; cancelAt = System.currentTimeMillis();
                        try { svc.send(android.os.Message.obtain(null, RenderService.MSG_CANCEL, id, 0)); } catch (Throwable ignored) {}
                    }
                    if (cancelSent && System.currentTimeMillis() - cancelAt > 10000) throw new CancelToken.Cancelled();
                    if (!cancelSent && !stuck && stalled(call.lastBeat, System.currentTimeMillis())) {
                        // code 32: the codec is stuck - end the render process (its binder death finishes this call below)
                        stuck = true; stuckAt = System.currentTimeMillis();
                        AppLog.e("The video engine made no progress for " + (STALL_MS / 1000) + " s (" + RenderSafety.kindLabel(kind) + "), restarting it");
                        synchronized (this) { book.killPending(); }
                        if (!killRender(c)) { synchronized (this) { book.killFailed(); } call.died = true; call.done.countDown(); }
                    }
                    if (stuck && System.currentTimeMillis() - stuckAt > 20000) { synchronized (this) { book.killFailed(); } call.died = true; call.done.countDown(); }
                }
                if (call.died && !call.gotDone) {
                    if (ct.cancelled) throw new CancelToken.Cancelled();
                    boolean crash;
                    String[] why;
                    int level;
                    int verdict;
                    synchronized (this) { verdict = stuck ? DeathBook.OWN : book.verdict(call.deathNo); }
                    if (stuck) {
                        why = new String[]{"no progress for " + (STALL_MS / 1000) + " s", ""};
                        // a stalled network stream says nothing about the phone's codec
                        crash = !networkInput(args);
                        level = crash ? RenderSafety.noteCrash(c, kind, why[0], args) : RenderSafety.level(c);
                    } else if (verdict == DeathBook.KILLED) {
                        // code 32: the engine was restarted for ANOTHER, stuck short: not this command's fault
                        why = new String[]{"engine restarted for a stuck short", ""};
                        crash = false; level = RenderSafety.level(c);
                    } else if (verdict == DeathBook.SAME) {
                        // code 32: the same process end, already judged by another running command (4.9 judged it once per
                        // running command: one crash = two steps down; and 2 unknown ends = "crash" on the first death)
                        why = new String[]{"same engine stop", ""};
                        synchronized (this) { crash = book.lastCrash; }
                        level = RenderSafety.level(c);
                    } else {
                        why = exitOfRender(c, started, call.pid > 0 ? call.pid : renderPid);
                        synchronized (this) { unknownDeaths = why[0].isEmpty() ? unknownDeaths + 1 : 0; }
                        crash = treatAsCrash(why[0], unknownDeaths);
                        if (crash && why[0].isEmpty()) why[0] = "ended twice with no reason recorded";
                        java.util.List<Integer> kinds = new java.util.ArrayList<>();
                        for (Call o : calls.values()) if (o.deathNo == call.deathNo) kinds.add(o.kindRun);
                        int k = mildestKind(kind, kinds);
                        level = crash ? RenderSafety.noteCrash(c, k, why[0] + (why[1].isEmpty() ? "" : " in " + why[1]), args) : RenderSafety.level(c);
                        synchronized (this) { book.lastCrash = crash; }
                    }
                    throw new EngineCrash(kind, crash, why[0], why[1], level);
                }
                synchronized (this) { unknownDeaths = 0; }
                Result r = new Result();
                r.rc = call.rc; r.out = call.out;
                return r;
            } finally {
                calls.remove(id);
                synchronized (this) {
                    active--;
                    if (active <= 0) { active = 0; main.postDelayed(idleUnbind, 30000); }
                }
            }
        }

        private static android.os.Message runMessage(int id, String[] args) {
            android.os.Message m = android.os.Message.obtain(null, RenderService.MSG_RUN, id, 0);
            android.os.Bundle b = new android.os.Bundle();
            b.putStringArray("args", args);
            m.setData(b);
            m.replyTo = INSTANCE.replies;
            return m;
        }

        /** code 32: ends Klipora's own render process (same app, so allowed); false = it could not be found */
        private boolean killRender(Context c) {
            int pid = renderPid;
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                java.util.List<android.app.ActivityManager.RunningAppProcessInfo> l = am == null ? null : am.getRunningAppProcesses();
                if (l != null) for (android.app.ActivityManager.RunningAppProcessInfo i : l) {
                    if (i.processName != null && i.processName.endsWith(RenderService.PROCESS_SUFFIX)) { pid = i.pid; break; }
                }
            } catch (Throwable ignored) {}
            if (pid <= 0 || pid == android.os.Process.myPid()) return false;
            try { android.os.Process.killProcess(pid); return true; } catch (Throwable t) { return false; }
        }

        /**
         * code 32: waits up to 300 ms for the command; an interrupt (the job's side executor shut down) first asks the render
         * process to stop the command (4.9 left it running there, writing its file beside the next attempt)
         */
        private static boolean awaitOrCancel(Call call, android.os.Messenger svc, int id) throws InterruptedException {
            try { return call.done.await(300, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException ie) {
                try { svc.send(android.os.Message.obtain(null, RenderService.MSG_CANCEL, id, 0)); } catch (Throwable ignored) {}
                throw ie;
            }
        }

        static String[] exitOfRender(Context c, long since) { return exitOfRender(c, since, 0); }

        /**
         * {reason name, crash location hint} of the render process's newest exit (Android 11+; "" when not recorded).
         * code 32: matched by the render pid when known (4.9 took any record newer than 2 s before the command: right after a
         * retry that was the PREVIOUS death's record)
         */
        static String[] exitOfRender(Context c, long since, int pid) {
            if (android.os.Build.VERSION.SDK_INT < 30) return new String[]{"", ""};
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                for (int k = 0; k < 6; k++) {
                    java.util.List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 10);
                    if (l != null) for (android.app.ApplicationExitInfo x : l) {
                        String pn = x.getProcessName();
                        if (pn == null || !pn.endsWith(RenderService.PROCESS_SUFFIX)) continue;
                        if (pid > 0 ? x.getPid() != pid : x.getTimestamp() < since - 2000) continue;
                        String hint = "";
                        if (x.getReason() == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE && android.os.Build.VERSION.SDK_INT >= 31) {
                            try { hint = RenderSafety.tombstoneHint(x.getTraceInputStream()); } catch (Throwable ignored) {}
                        }
                        String name = JobGuard.reasonName(x.getReason());
                        if (x.getReason() == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) name += ", signal " + x.getStatus();
                        return new String[]{name, hint};
                    }
                    Thread.sleep(250);
                }
            } catch (Throwable ignored) {}
            return new String[]{"", ""};
        }
    }

    /**
     * v20: the one line of FFmpeg's error text that says what went wrong, without "[aac @ 0x...]" prefixes or repeats,
     * at most 140 characters. 3.0 showed the last 600 characters (cut mid-word: "ror while decoding...").
     */
    public static String summarize(String out) {
        if (out == null) return "unknown error";
        String best = null;
        java.util.LinkedHashSet<String> lines = new java.util.LinkedHashSet<>();
        for (String l : out.split("\\r?\\n")) {
            l = l.replaceAll("^\\[[^\\]]*@ ?0x[0-9a-fA-F]+\\]\\s*", "").trim();
            if (!l.isEmpty()) lines.add(l);
        }
        for (String l : lines) {
            String lo = l.toLowerCase(java.util.Locale.US);
            if (lo.contains("invalid data") || lo.contains("no such file") || lo.contains("permission denied") || lo.contains("not found")
                    || lo.contains("error opening") || lo.contains("could not") || lo.contains("unknown encoder") || lo.contains("invalid argument")) { best = l; break; }
        }
        if (best == null) for (String l : lines) best = l;   // the last distinct line
        if (best == null) return "unknown error";
        if (best.toLowerCase(java.util.Locale.US).contains("invalid data found")) best = "the audio/video data is damaged (" + best + ")";
        return best.length() > 140 ? best.substring(0, 137) + "..." : best;
    }

    /**
     * code 32 (5.0): one render-process death ends every command running in it; it is judged ONCE (the first command
     * handled does it; the others take that verdict). A death caused by the stall watchdog's own kill is nobody else's
     * fault.
     */
    static final class DeathBook {
        static final int FIRST = 0, SAME = 1, KILLED = 2, OWN = 3;
        int seq, judged, killedSeq = -1;
        boolean killPending, lastCrash;
        /** a death happened: its number */
        int death() { seq++; if (killPending) { killedSeq = seq; killPending = false; } return seq; }
        void killPending() { killPending = true; }
        void killFailed() { killPending = false; }
        /** how a command ended by death no. n is judged */
        int verdict(int n) {
            if (n == killedSeq) return KILLED;
            if (n > judged) { judged = n; return FIRST; }
            return SAME;
        }
    }

    /** code 32: the mildest safety step for one death: the hardware DECODER first when one of the dead commands used it */
    static int mildestKind(int own, java.util.Collection<Integer> kinds) {
        if (kinds != null) for (Integer k : kinds) if (k != null && k == RenderSafety.K_HW_DECODE) return RenderSafety.K_HW_DECODE;
        return own;
    }

    /** v26: an FFmpeg failure with the end of FFmpeg's own log, so the caller can tell encoder / decoder / network apart */
    public static final class Failed extends Exception {
        public final String log;
        public Failed(String msg, String log) { super(msg); this.log = log == null ? "" : log; }
    }

    public static void cancel(CancelToken ct) {
        try {
            if (ct.sessions.isEmpty()) FFmpegKit.cancel();
            else for (Long id : ct.sessions) FFmpegKit.cancel(id);
        } catch (Throwable ignored) {}
    }
}
