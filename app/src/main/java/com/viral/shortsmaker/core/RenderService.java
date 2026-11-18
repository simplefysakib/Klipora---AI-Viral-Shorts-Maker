package com.viral.shortsmaker.core;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.antonkarpenko.ffmpegkit.FFmpegKit;
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig;
import com.antonkarpenko.ffmpegkit.FFmpegSession;
import com.antonkarpenko.ffmpegkit.ReturnCode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * code 31 (4.9): FFmpeg's video work in its own process (android:process=":render", see the manifest). A native crash in
 * the phone's MediaCodec decoder / encoder or in FFmpeg ends only this process; the app and the job keep running, are told
 * (binder death) and render the same short again on a safer path (RenderSafety). Nothing else starts here: no ads, no face
 * engine, no yt-dlp, no log rotation (KliporaApp checks the process name). Bound by MediaRunner with BIND_IMPORTANT, so it
 * has the foreground priority of the job's foreground service.
 */
public class RenderService extends Service {
    static final int MSG_RUN = 1, MSG_CANCEL = 2, MSG_PROGRESS = 3, MSG_DONE = 4;
    static final String PROCESS_SUFFIX = ":render";
    /** return code sent when FFmpegKit gave none */
    static final int RC_NONE = -999;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<Integer, Long> sessions = new ConcurrentHashMap<>();
    private final java.util.Set<Integer> cancelled = ConcurrentHashMap.newKeySet();
    private HandlerThread thread;
    private Messenger messenger;

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("render-ipc");
        thread.start();
        messenger = new Messenger(new Handler(thread.getLooper(), this::handle));
        pool.execute(() -> { try { MediaRunner.fontsDir(this); } catch (Throwable ignored) {} });
    }

    @Override
    public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    @Override
    public void onDestroy() {
        for (Long sid : sessions.values()) { try { FFmpegKit.cancel(sid); } catch (Throwable ignored) {} }
        pool.shutdownNow();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    private boolean handle(Message msg) {
        switch (msg.what) {
            case MSG_RUN: {
                final int id = msg.arg1;
                final Bundle b = msg.getData();
                final Messenger reply = msg.replyTo;
                final String[] args = b == null ? null : b.getStringArray("args");
                if (args == null || reply == null) return true;
                pool.execute(() -> run(id, args, reply));
                return true;
            }
            case MSG_CANCEL: {
                cancelled.add(msg.arg1);
                Long sid = sessions.get(msg.arg1);
                if (sid != null) { try { FFmpegKit.cancel(sid); } catch (Throwable ignored) {} }
                return true;
            }
            default: return false;
        }
    }

    private void run(int id, String[] args, Messenger reply) {
        int rc = RC_NONE;
        String out = "";
        if (args.length > 0 && RenderSafety.NATIVE_ARG.equals(args[0])) { runNative(id, args, reply); return; }
        try {
            MediaRunner.fontsDir(this);   // fonts + log level in this process (idempotent)
            final long[] lastSent = {0};
            FFmpegSession s = FFmpegSession.create(args, null, null, stats -> {
                long now = System.currentTimeMillis();
                if (now - lastSent[0] < 250 || stats.getTime() <= 0) return;
                lastSent[0] = now;
                Message p = Message.obtain(null, MSG_PROGRESS, id, 0);
                Bundle pb = new Bundle();
                pb.putDouble("t", stats.getTime());
                pb.putInt("pid", android.os.Process.myPid());
                p.setData(pb);
                try { reply.send(p); } catch (RemoteException ignored) {}
            });
            sessions.put(id, s.getSessionId());
            if (!cancelled.contains(id)) FFmpegKitConfig.ffmpegExecute(s);
            ReturnCode r = s.getReturnCode();
            rc = cancelled.contains(id) ? ReturnCode.CANCEL : r == null ? RC_NONE : r.getValue();
            if (!ReturnCode.isSuccess(r)) {
                out = s.getOutput();
                if (out == null || out.trim().isEmpty()) out = s.getAllLogsAsString();
                if (out == null) out = "";
                out = out.trim();
                if (out.length() > 20000) out = out.substring(out.length() - 20000);
            }
        } catch (Throwable t) {
            out = "render process error: " + t;
        } finally {
            sessions.remove(id);
            cancelled.remove(id);
        }
        Message d = Message.obtain(null, MSG_DONE, id, rc);
        Bundle db = new Bundle();
        db.putString("out", out);
        d.setData(db);
        try { reply.send(d); } catch (RemoteException ignored) {}
    }

    /** code 33: the backup video engine (no FFmpeg): same messages as an FFmpeg command (0 = done, 1 = failed) */
    private void runNative(int id, String[] args, Messenger reply) {
        final long[] lastSent = {0};
        String err;
        try {
            if (MediaRunner.appCtx == null) MediaRunner.appCtx = getApplicationContext();
            err = NativeEngine.runArgs(this, args, sec -> {
                long now = System.currentTimeMillis();
                if (now - lastSent[0] < 250) return;
                lastSent[0] = now;
                Message p = Message.obtain(null, MSG_PROGRESS, id, 0);
                Bundle pb = new Bundle();
                pb.putDouble("t", sec * 1000.0);
                pb.putInt("pid", android.os.Process.myPid());
                p.setData(pb);
                try { reply.send(p); } catch (RemoteException ignored) {}
            }, () -> cancelled.contains(id));
        } catch (Throwable t) { err = "render process error: " + t; }
        boolean wasCancelled = cancelled.remove(id);
        int rc = wasCancelled ? ReturnCode.CANCEL : err == null ? ReturnCode.SUCCESS : 1;
        Message d = Message.obtain(null, MSG_DONE, id, rc);
        Bundle db = new Bundle();
        db.putString("out", err == null ? "" : "Backup video engine: " + err);
        d.setData(db);
        try { reply.send(d); } catch (RemoteException ignored) {}
    }
}
