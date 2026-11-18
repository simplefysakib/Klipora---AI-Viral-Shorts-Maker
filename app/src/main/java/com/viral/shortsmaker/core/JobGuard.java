package com.viral.shortsmaker.core;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * code 30 (4.8): the "a job was running" marker, why the previous process ended, and the job's checkpoint.
 *
 * <p>4.7 kept a single boolean (job_alive). On the next start ANY end of the process during a job - the phone's
 * cleaner or battery manager, a swipe from Recent apps, low memory, a crash, an app update, a reboot - was shown as
 * "closed by battery saver", the message lost the job's link/title (the report read them from the screen, empty after a
 * restart: "Video: N/A"), the end-of-job write used apply(), so a process frozen right after the job could leave a false
 * marker behind, and a marker days old was still reported. Now the marker carries the job (link, title, step, progress,
 * last sign of life, pid, app version, boot) and the next start asks Android why that process ended
 * (ApplicationExitInfo, Android 11+) before saying anything. The decision itself ({@link #verdict}) is pure and tested.
 */
public final class JobGuard {
    private JobGuard() {}

    static final String K_ALIVE = "job_alive", K_AT = "job_alive_at", K_PID = "job_pid", K_VER = "job_ver", K_BOOT = "job_boot",
            K_KIND = "job_kind", K_URL = "job_url", K_TITLE = "job_title", K_STEP = "job_step", K_PCT = "job_pct", K_BEAT = "job_beat",
            K_PROJECT = "job_project", K_TOTAL = "job_total", K_FGS = "job_fgs", K_TASK_GONE = "job_task_removed",
            K_SCREEN_OFF = "job_screen_off", K_LOW_MEM_NEXT = "low_mem_next";
    /** a marker older than this (no sign of life for a day) is cleared without a message */
    public static final long STALE_MS = 24 * 3600 * 1000L;
    /** two boot times further apart than this = the phone restarted */
    static final long BOOT_SLACK_MS = 2 * 60 * 1000L;
    static final long BEAT_EVERY_MS = 10_000L;

    // ApplicationExitInfo reasons (numbers, so the code compiles with SDK 34 and runs on 24+)
    public static final int R_UNKNOWN = 0, R_EXIT_SELF = 1, R_SIGNALED = 2, R_LOW_MEMORY = 3, R_CRASH = 4, R_CRASH_NATIVE = 5,
            R_ANR = 6, R_INIT_FAILURE = 7, R_PERMISSION_CHANGE = 8, R_EXCESSIVE_RESOURCE = 9, R_USER_REQUESTED = 10,
            R_USER_STOPPED = 11, R_DEPENDENCY_DIED = 12, R_OTHER = 13, R_FREEZER = 14, R_PACKAGE_STATE = 15, R_PACKAGE_UPDATED = 16;

    public enum Cause {
        /** nothing to say */ NONE,
        /** marker left behind but nothing to report (too old, same process, the job had actually finished) */ STALE, FINISHED,
        /** the app was updated / reinstalled while the job ran */ UPDATED,
        /** the phone restarted */ REBOOT,
        /** closed from Recent apps (or "clear all") */ SWIPED,
        /** the system ran out of memory */ LOW_MEMORY,
        /** Klipora itself crashed */ CRASH,
        /** the phone's battery / background manager (or an unknown system kill) */ SYSTEM;

        public boolean silent() { return this == NONE || this == STALE || this == FINISHED; }
    }

    /** what the marker said about the job that was running */
    public static final class Marker {
        public boolean alive;
        public long at, beat, boot, taskRemovedAt, screenOffAt;
        public int pid, ver, pct = -1, total;
        public String kind = "", url = "", title = "", step = "", project = "", fgs = "";

        public static Marker read(SharedPreferences sp) {
            Marker m = new Marker();
            m.alive = sp.getBoolean(K_ALIVE, false);
            m.at = sp.getLong(K_AT, 0); m.beat = sp.getLong(K_BEAT, 0); m.boot = sp.getLong(K_BOOT, 0);
            m.taskRemovedAt = sp.getLong(K_TASK_GONE, 0); m.screenOffAt = sp.getLong(K_SCREEN_OFF, 0);
            m.pid = sp.getInt(K_PID, 0); m.ver = sp.getInt(K_VER, 0); m.pct = sp.getInt(K_PCT, -1); m.total = sp.getInt(K_TOTAL, 0);
            m.kind = sp.getString(K_KIND, ""); m.url = sp.getString(K_URL, ""); m.title = sp.getString(K_TITLE, "");
            m.step = sp.getString(K_STEP, ""); m.project = sp.getString(K_PROJECT, ""); m.fgs = sp.getString(K_FGS, "");
            return m;
        }

        public long lastSign() { return Math.max(at, beat); }
    }

    /** why Android says the process ended (from ApplicationExitInfo), null when unknown */
    public static final class Exit {
        public int reason, status, importance;
        public long timestamp;
        public String description = "";
        public Exit(int reason, int status, int importance, long timestamp, String description) {
            this.reason = reason; this.status = status; this.importance = importance; this.timestamp = timestamp;
            this.description = description == null ? "" : description;
        }
    }

    // ================================================================== the decision (pure)

    /**
     * @param jobRunning     a job is running in this process right now
     * @param projectDone    the job's checkpoint project has every short rendered (the job did finish)
     * @param crashAt        time of the last crash Klipora's own handler saved (0 = none)
     * @param bootNow        this boot's start time (currentTimeMillis - elapsedRealtime)
     */
    public static Cause verdict(Marker m, long now, int myPid, int curVer, boolean jobRunning, Exit exit, long crashAt,
                                boolean projectDone, long bootNow) {
        if (m == null || !m.alive) return Cause.NONE;
        if (jobRunning) return Cause.NONE;
        if (m.pid != 0 && m.pid == myPid && (m.boot == 0 || Math.abs(m.boot - bootNow) < BOOT_SLACK_MS)) return Cause.STALE;   // same process, job no longer running
        if (projectDone) return Cause.FINISHED;
        long last = m.lastSign();
        if (last > 0 && now - last > STALE_MS) return Cause.STALE;
        // the exit record must belong to the job's process (pids are reused: it must be newer than the job start)
        if (exit != null && m.at > 0 && exit.timestamp > 0 && exit.timestamp + 5_000 < m.at) exit = null;
        if (exit != null) {
            switch (exit.reason) {
                case R_CRASH: case R_CRASH_NATIVE: case R_ANR: case R_INIT_FAILURE: return Cause.CRASH;
                case R_LOW_MEMORY: return Cause.LOW_MEMORY;
                case R_PACKAGE_UPDATED: case R_PACKAGE_STATE: return Cause.UPDATED;
                case R_USER_REQUESTED: case R_USER_STOPPED:
                    return swipeLike(exit.description) || m.taskRemovedAt >= m.at && m.taskRemovedAt > 0 ? Cause.SWIPED : Cause.SYSTEM;
                case R_EXIT_SELF:
                    if (crashAt > 0 && crashAt >= m.at) return Cause.CRASH;
                    return Cause.SYSTEM;
                default:
                    if (m.taskRemovedAt > 0 && m.taskRemovedAt >= m.at && exit.timestamp > 0 && exit.timestamp - m.taskRemovedAt < 15_000) return Cause.SWIPED;
                    return Cause.SYSTEM;
            }
        }
        if (crashAt > 0 && crashAt >= m.at) return Cause.CRASH;
        if (m.boot > 0 && bootNow > 0 && Math.abs(m.boot - bootNow) > BOOT_SLACK_MS) return Cause.REBOOT;
        if (m.ver > 0 && curVer > 0 && m.ver != curVer) return Cause.UPDATED;
        if (m.taskRemovedAt > 0 && m.taskRemovedAt >= m.at) return Cause.SWIPED;
        return Cause.SYSTEM;
    }

    static boolean swipeLike(String d) {
        String l = d == null ? "" : d.toLowerCase(Locale.US);
        return l.contains("remove task") || l.contains("removetask") || l.contains("swipe") || l.contains("recent");
    }

    /** card title for a cause */
    public static String title(Cause c) {
        switch (c) {
            case CRASH: return "Klipora stopped because of an error";
            case UPDATED: return "Klipora was updated during processing";
            case REBOOT: return "Your phone restarted during processing";
            default: return "Processing was stopped";
        }
    }

    /** card message for a cause; done/total = shorts already finished (resume offered when 0 < done < total) */
    public static String message(Cause c, String manufacturer, int done, int total) {
        String why;
        switch (c) {
            case SWIPED: why = "Klipora was closed from Recent apps before the shorts were finished. Next time leave it there: it keeps working with the screen off."; break;
            case LOW_MEMORY: why = "Your phone ran out of memory and closed Klipora. The next try makes one short at a time to use less memory."; break;
            case CRASH: why = "Klipora closed unexpectedly before the shorts were finished. Tap Report bug so we can fix it."; break;
            case UPDATED: why = "The update closed the running job."; break;
            case REBOOT: why = "The shorts weren't finished when the phone turned off."; break;
            default: why = "Your phone closed Klipora in the background before the shorts were finished. Tap \"Fix background settings\" so it can keep working with the screen off."; break;
        }
        if (total > 0 && done > 0 && done < total) why += "\n\n" + done + " of " + total + (total == 1 ? " short is" : " shorts are") + " already done. Tap Continue to make the rest.";
        else why += "\n\nTap Retry to start again.";
        return why;
    }

    /** the Fix background settings button belongs on the card */
    public static boolean wantsFix(Cause c) { return c == Cause.SYSTEM || c == Cause.SWIPED; }

    public static String reasonName(int r) {
        switch (r) {
            case R_EXIT_SELF: return "EXIT_SELF"; case R_SIGNALED: return "SIGNALED"; case R_LOW_MEMORY: return "LOW_MEMORY";
            case R_CRASH: return "CRASH"; case R_CRASH_NATIVE: return "CRASH_NATIVE"; case R_ANR: return "ANR";
            case R_INIT_FAILURE: return "INITIALIZATION_FAILURE"; case R_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case R_EXCESSIVE_RESOURCE: return "EXCESSIVE_RESOURCE_USAGE"; case R_USER_REQUESTED: return "USER_REQUESTED";
            case R_USER_STOPPED: return "USER_STOPPED"; case R_DEPENDENCY_DIED: return "DEPENDENCY_DIED"; case R_OTHER: return "OTHER";
            case R_FREEZER: return "FREEZER"; case R_PACKAGE_STATE: return "PACKAGE_STATE_CHANGE"; case R_PACKAGE_UPDATED: return "PACKAGE_UPDATED";
            default: return "UNKNOWN";
        }
    }

    static String importanceName(int i) {
        if (i <= 0) return "?";
        if (i <= 100) return "on screen";
        if (i <= 125) return "foreground service";
        if (i <= 230) return "visible/perceptible";
        if (i <= 300) return "service";
        return "background (" + i + ")";
    }

    // ================================================================== marker writes (commit: must survive an immediate kill)

    public static long bootTime() { return System.currentTimeMillis() - SystemClock.elapsedRealtime(); }

    public static void markStart(Context c, Pipeline.Request rq, int versionCode) {
        SharedPreferences.Editor e = Prefs.sp(c).edit();
        long now = System.currentTimeMillis();
        e.putBoolean(K_ALIVE, true).putLong(K_AT, now).putLong(K_BEAT, now).putInt(K_PID, android.os.Process.myPid())
                .putInt(K_VER, versionCode).putLong(K_BOOT, bootTime())
                .putString(K_KIND, rq == null || rq.kind == null ? "" : rq.kind.name())
                .putString(K_URL, rq == null || rq.ytUrl == null ? "" : rq.ytUrl)
                .putString(K_TITLE, rq == null || rq.title == null ? "" : rq.title)
                .putString(K_STEP, "Starting").putInt(K_PCT, 0).putString(K_FGS, "")
                .remove(K_TASK_GONE).remove(K_SCREEN_OFF).remove(K_TOTAL);
        // a resumed job keeps its checkpoint; a new job forgets the old one
        if (rq == null || rq.resumeId == null || rq.resumeId.isEmpty()) e.remove(K_PROJECT);
        e.commit();
        AppLog.dropJobLog(c.getFilesDir());   // code 32
    }

    /** job ended in this process (done, failed or cancelled): synchronous, so a phone that freezes Klipora right after the job can't leave a false marker */
    public static void markEnd(Context c, boolean success) {
        SharedPreferences.Editor e = Prefs.sp(c).edit().putBoolean(K_ALIVE, false).remove(K_BEAT).remove(K_TASK_GONE).remove(K_SCREEN_OFF);
        if (success) e.remove(K_PROJECT).remove(K_TOTAL).remove(K_LOW_MEM_NEXT);
        e.commit();
    }

    private static volatile long lastBeat;

    /** a sign of life with the step the job is in (throttled; cheap async write) */
    public static void beat(Context c, String step, int pct) {
        long now = System.currentTimeMillis();
        if (now - lastBeat < BEAT_EVERY_MS) return;
        lastBeat = now;
        Prefs.sp(c).edit().putLong(K_BEAT, now).putString(K_STEP, step == null ? "" : step).putInt(K_PCT, pct).apply();
    }

    /** the clips are picked: the project folder is the checkpoint a stopped job continues from */
    public static void checkpoint(Context c, String projectId, int total) {
        Prefs.sp(c).edit().putString(K_PROJECT, projectId == null ? "" : projectId).putInt(K_TOTAL, total).commit();
    }

    public static void foreground(Context c, String state) { Prefs.sp(c).edit().putString(K_FGS, state).apply(); }

    public static void taskRemoved(Context c) {
        if (Prefs.sp(c).getBoolean(K_ALIVE, false)) Prefs.sp(c).edit().putLong(K_TASK_GONE, System.currentTimeMillis()).commit();
    }

    public static void screenOff(Context c) {
        if (Prefs.sp(c).getBoolean(K_ALIVE, false)) Prefs.sp(c).edit().putLong(K_SCREEN_OFF, System.currentTimeMillis()).apply();
    }

    /** clears the marker after it was handled (the checkpoint stays for Continue) */
    public static void consume(Context c) {
        Prefs.sp(c).edit().putBoolean(K_ALIVE, false).commit();
    }

    public static void forgetCheckpoint(Context c) { Prefs.sp(c).edit().remove(K_PROJECT).remove(K_TOTAL).apply(); }

    public static boolean lowMemoryNext(Context c) { return Prefs.sp(c).getBoolean(K_LOW_MEM_NEXT, false); }
    public static void setLowMemoryNext(Context c) { Prefs.sp(c).edit().putBoolean(K_LOW_MEM_NEXT, true).apply(); }

    // ================================================================== reading the system

    /** why the job's process ended, from Android 11+'s exit history (null below Android 11 or when not recorded) */
    public static Exit exitOf(Context c, Marker m) {
        if (Build.VERSION.SDK_INT < 30 || m == null || m.pid == 0) return null;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(c.getPackageName(), m.pid, 3);
            if (l == null || l.isEmpty()) {
                // pid filter not honoured on some builds: newest entries, matching pid
                l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 8);
            }
            if (l == null) return null;
            for (android.app.ApplicationExitInfo x : l) {
                if (x.getPid() != m.pid) continue;
                String desc = x.getDescription();
                // code 31: Android 12+ keeps the native crash report: the libraries / functions in it go into the bug report
                if (x.getReason() == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31) {
                    try {
                        String hint = RenderSafety.tombstoneHint(x.getTraceInputStream());
                        if (!hint.isEmpty()) desc = (desc == null ? "" : desc) + " | in: " + hint;
                    } catch (Throwable ignored) {}
                }
                return new Exit(x.getReason(), x.getStatus(), x.getImportance(), x.getTimestamp(), desc);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** how many shorts the checkpoint project already has on disk, and how many it was making: {done, total}; null = no checkpoint */
    public static int[] progressOf(Context c, String projectId) {
        Project p = checkpointProject(c, projectId);
        if (p == null || p.clips.isEmpty()) return null;
        int done = 0;
        for (Project.Clip cl : p.clips) if (clipDone(cl)) done++;
        return new int[]{done, p.clips.size()};
    }

    public static boolean clipDone(Project.Clip cl) {
        File f = cl.file();
        return f != null && f.exists() && f.length() > 1024;
    }

    /** the checkpoint project with EVERY picked clip (Project.loadFrom keeps only finished ones) */
    public static Project checkpointProject(Context c, String projectId) {
        if (projectId == null || projectId.isEmpty()) return null;
        try {
            File f = new File(new File(Project.root(c), projectId), "project.json");
            if (!f.exists()) return null;
            String s = Project.readText(f);
            if (s == null) return null;
            Project p = Project.fromJson(new org.json.JSONObject(s));
            p.id = projectId;
            return p;
        } catch (Throwable e) { return null; }
    }

    /** lines for the bug report about the job that was stopped */
    public static String reportBlock(Marker m, Exit x, Cause cause, int[] prog, long now) {
        StringBuilder b = new StringBuilder();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
        if (m.at > 0) b.append("Started: ").append(f.format(new java.util.Date(m.at))).append('\n');
        if (m.lastSign() > 0) b.append("Last sign of life: ").append(f.format(new java.util.Date(m.lastSign())))
                .append(m.at > 0 ? String.format(Locale.US, " (%.1f min after start)", (m.lastSign() - m.at) / 60000.0) : "").append('\n');
        if (!m.step.isEmpty()) b.append("Last step: ").append(m.step).append(m.pct >= 0 ? " (" + m.pct + "%)" : "").append('\n');
        if (prog != null) b.append("Shorts finished: ").append(prog[0]).append(" of ").append(prog[1]).append('\n');
        b.append("Stopped because: ").append(cause.name());
        if (x != null) b.append(" (Android: ").append(reasonName(x.reason)).append(x.description.isEmpty() ? "" : ", \"" + x.description + "\"")
                .append(", status ").append(x.status).append(", was ").append(importanceName(x.importance))
                .append(x.timestamp > 0 ? ", at " + f.format(new java.util.Date(x.timestamp)) : "").append(')');
        else b.append(" (no exit record from Android)");
        b.append('\n');
        if (m.taskRemovedAt > 0) b.append("Removed from Recent apps at: ").append(f.format(new java.util.Date(m.taskRemovedAt))).append('\n');
        if (m.screenOffAt > 0) b.append("Screen turned off at: ").append(f.format(new java.util.Date(m.screenOffAt))).append('\n');
        if (!m.fgs.isEmpty()) b.append("Background mode: ").append(m.fgs).append('\n');
        if (m.ver > 0) b.append("App build when it ran: ").append(m.ver).append('\n');
        return b.toString();
    }
}
