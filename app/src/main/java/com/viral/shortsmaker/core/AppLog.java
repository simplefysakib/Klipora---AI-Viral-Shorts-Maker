package com.viral.shortsmaker.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Thread-safe live log shared by the service and the UI (replaces v1's HTML StringBuilder on the UI thread). */
public final class AppLog {
    public static final class Entry {
        public final String time, msg;
        public final boolean error;
        Entry(String time, String msg, boolean error) { this.time = time; this.msg = msg; this.error = error; }
    }

    public interface Listener { void onLog(Entry e); }

    private static final List<Entry> entries = new ArrayList<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private AppLog() {}

    public static void i(String msg) { add(msg, false); }
    public static void e(String msg) { add(msg, true); }

    public static void add(String msg, boolean error) {
        Entry en;
        synchronized (entries) {
            en = new Entry(fmt.format(new Date()), msg, error);
            entries.add(en);
            if (entries.size() > 1500) entries.subList(0, 300).clear();
        }
        Log.println(error ? Log.ERROR : Log.INFO, "Klipora", msg);
        toSessionFile(en);
        final Entry fe = en;
        main.post(() -> { for (Listener l : listeners) l.onLog(fe); });
    }

    /**
     * v20 (3.1): full technical text (FFmpeg's stderr, etc.) goes to a debug file, never into the visible log. His 3.0
     * log carried a 12-line FFmpeg error wall per speech part. KliporaApp sets the file at start.
     */
    public static volatile java.io.File debugFile;

    public static void debug(String msg) {
        Log.println(Log.DEBUG, "Klipora", msg == null ? "" : msg);
        java.io.File f = debugFile;
        if (f == null) return;
        synchronized (AppLog.class) {
            try {
                if (f.length() > 2_000_000) { java.io.File old = new java.io.File(f.getPath() + ".1"); old.delete(); f.renameTo(old); }
                try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                    w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + msg + "\n");
                }
            } catch (Throwable ignored) {}
        }
    }

    // ================================================================== code 30: the visible log on disk

    /**
     * code 30 (4.8): the visible log is also written to files/session.log, and each app start moves the last one to
     * session_prev.log. 4.7 kept the log only in memory, so when the phone ended the process mid-job the report sent on the
     * next start had only the new session's 4 lines (his vivo report) and nothing about the job that died.
     */
    private static volatile java.io.File sessionFile;
    private static final java.util.concurrent.ExecutorService fileQ = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "applog-file"); t.setDaemon(true); return t;
    });
    static final long SESSION_MAX = 600_000;

    /** called once per process start (KliporaApp): rotates session.log to session_prev.log */
    public static void startSession(java.io.File dir) { startSession(dir, false); }

    /**
     * code 32 (5.0): keepJobLog = a job was running when the app's process ended. Its log is then kept as session_job.log
     * until the next job starts. 4.9 rotated on every process start: Android also starts the app in the background (the
     * ad SDK's scheduled work), so by the time the user opened Klipora the stopped job's log had been rotated away and the
     * report's "LOGS OF THE STOPPED JOB" showed only engine start lines.
     */
    public static void startSession(java.io.File dir, boolean keepJobLog) {
        try {
            java.io.File cur = new java.io.File(dir, "session.log"), prev = new java.io.File(dir, "session_prev.log"), job = new java.io.File(dir, "session_job.log");
            if (cur.exists() && cur.length() > 0) {
                if (keepJobLog && !job.exists()) cur.renameTo(job);
                else { prev.delete(); cur.renameTo(prev); }
            }
            if (!keepJobLog) job.delete();
            sessionFile = cur;
        } catch (Throwable ignored) {}
    }

    /** code 32: a new job starts: the kept log of an earlier stopped job is no longer needed */
    public static void dropJobLog(java.io.File dir) { try { new java.io.File(dir, "session_job.log").delete(); } catch (Throwable ignored) {} }

    private static void toSessionFile(Entry en) {
        final java.io.File f = sessionFile;
        if (f == null) return;
        final String line = new SimpleDateFormat("MM-dd ", Locale.US).format(new Date()) + en.time + (en.error ? " [ERROR] " : " ") + en.msg + "\n";
        try {
            fileQ.execute(() -> {
                try {
                    if (f.length() > SESSION_MAX) return;
                    try (java.io.FileWriter w = new java.io.FileWriter(f, true)) { w.write(line); }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** the last {@code max} lines of the previous app session's log ("" when none) */
    public static String previousSession(java.io.File dir, int max) {
        try {
            java.io.File prev = new java.io.File(dir, "session_job.log");
            if (!prev.exists()) prev = new java.io.File(dir, "session_prev.log");
            if (!prev.exists()) return "";
            String s = new String(IoUtil.readAll(prev), "UTF-8").trim();
            String[] lines = s.split("\n");
            int from = Math.max(0, lines.length - max);
            StringBuilder b = new StringBuilder();
            if (from > 0) b.append("(").append(from).append(" earlier lines not shown)\n");
            for (int i = from; i < lines.length; i++) b.append(lines[i]).append('\n');
            return b.toString().trim();
        } catch (Throwable e) { return ""; }
    }

    public static void clear() { synchronized (entries) { entries.clear(); } }

    public static List<Entry> snapshot() { synchronized (entries) { return new ArrayList<>(entries); } }

    /** code 32: the last {@code max} lines only (the bug-report mail; the whole log goes to the clipboard) */
    public static String plainTail(int max) {
        java.util.List<Entry> l = snapshot();
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, l.size() - Math.max(0, max));
        if (from > 0) sb.append("(").append(from).append(" earlier lines are in the copied report)\n");
        for (int i = from; i < l.size(); i++) { Entry e = l.get(i); sb.append(e.time).append(e.error ? " [ERROR] " : " ").append(e.msg).append('\n'); }
        return sb.toString().trim();
    }

    /** code 32: keeps a report under {@code maxChars} (cut in the middle of the log, with a note), for intents / clipboard */
    public static String capReport(String s, int maxChars) {
        if (s == null || s.length() <= maxChars) return s;
        int head = maxChars * 2 / 5, tail = maxChars - head - 64;
        return s.substring(0, head) + "\n\n... (" + (s.length() - head - tail) + " characters cut) ...\n\n" + s.substring(s.length() - tail);
    }

    public static String plain() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : snapshot()) sb.append(e.time).append(e.error ? " [ERROR] " : " ").append(e.msg).append('\n');
        return sb.toString().trim();
    }

    public static void addListener(Listener l) { listeners.add(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }
}
