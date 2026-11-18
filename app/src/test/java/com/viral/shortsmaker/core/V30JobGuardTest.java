package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * code 30 (4.8): his vivo V2321 / Android 15 report ("Processing was stopped ... battery saver", Video/URL N/A, the error
 * logged 0 s after start). Pins: the stopped-job decision for every way a process ends (and the stale / finished /
 * same-process cases that must say nothing), the marker writes, Continue making only the missing shorts, the session log
 * on disk, the atomic project save, and the foreground start of ProcessingService.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V30JobGuardTest {
    static final long H = 3600_000L, NOW = 1_800_000_000_000L, BOOT = NOW - 50 * H;

    static JobGuard.Marker marker(long at) {
        JobGuard.Marker m = new JobGuard.Marker();
        m.alive = true; m.at = at; m.beat = at + 60_000; m.pid = 4242; m.ver = 30; m.boot = BOOT;
        m.url = "https://youtu.be/abcdefghijk"; m.title = "Podcast";
        return m;
    }

    static JobGuard.Cause v(JobGuard.Marker m, JobGuard.Exit x) { return JobGuard.verdict(m, NOW, 777, 30, false, x, 0, false, BOOT); }
    static JobGuard.Exit exit(int reason, String desc) { return new JobGuard.Exit(reason, 0, 125, NOW - 9 * 60_000, desc); }

    @After public void clear() { Pipeline.renderHookForTest = null; Pipeline.parForTest = 0; }

    // ---------------------------------------------------------------- the decision

    @Test public void nothingToSayWithoutMarkerOrWhileRunning() {
        JobGuard.Marker m = marker(NOW - 10 * 60_000);
        assertEquals(JobGuard.Cause.NONE, JobGuard.verdict(m, NOW, 777, 30, true, null, 0, false, BOOT));
        m.alive = false;
        assertEquals(JobGuard.Cause.NONE, v(m, null));
        assertEquals(JobGuard.Cause.NONE, JobGuard.verdict(null, NOW, 777, 30, false, null, 0, false, BOOT));
    }

    @Test public void staleMarkersSayNothing() {
        // same process, job no longer running (a marker this process left): never "stopped by the phone"
        JobGuard.Marker m = marker(NOW - 10 * 60_000);
        assertEquals(JobGuard.Cause.STALE, JobGuard.verdict(m, NOW, 4242, 30, false, null, 0, false, BOOT));
        // no sign of life for more than a day
        JobGuard.Marker old = marker(NOW - 30 * H);
        assertEquals(JobGuard.Cause.STALE, v(old, exit(JobGuard.R_OTHER, "")));
        // the job had finished (every short on disk) - the end-of-job write was lost
        assertEquals(JobGuard.Cause.FINISHED, JobGuard.verdict(marker(NOW - 600_000), NOW, 777, 30, false, null, 0, true, BOOT));
        assertTrue(JobGuard.Cause.STALE.silent() && JobGuard.Cause.FINISHED.silent() && !JobGuard.Cause.SYSTEM.silent());
    }

    @Test public void causeFromAndroidsExitRecord() {
        JobGuard.Marker m = marker(NOW - 10 * 60_000);
        assertEquals(JobGuard.Cause.CRASH, v(m, exit(JobGuard.R_CRASH, "")));
        assertEquals(JobGuard.Cause.CRASH, v(m, exit(JobGuard.R_CRASH_NATIVE, "")));
        assertEquals(JobGuard.Cause.CRASH, v(m, exit(JobGuard.R_ANR, "")));
        assertEquals(JobGuard.Cause.LOW_MEMORY, v(m, exit(JobGuard.R_LOW_MEMORY, "")));
        assertEquals(JobGuard.Cause.UPDATED, v(m, exit(JobGuard.R_PACKAGE_UPDATED, "")));
        assertEquals(JobGuard.Cause.SWIPED, v(m, exit(JobGuard.R_USER_REQUESTED, "remove task")));
        // a force stop by the phone's cleaner / power manager: not the user's swipe
        assertEquals(JobGuard.Cause.SYSTEM, v(m, exit(JobGuard.R_USER_REQUESTED, "stop com.viral.shortsmaker due to from pid 1923")));
        assertEquals(JobGuard.Cause.SYSTEM, v(m, exit(JobGuard.R_SIGNALED, "")));
        assertEquals(JobGuard.Cause.SYSTEM, v(m, exit(JobGuard.R_EXCESSIVE_RESOURCE, "")));
        assertEquals(JobGuard.Cause.SYSTEM, v(m, exit(JobGuard.R_FREEZER, "")));
        // swiped away, then killed seconds later (vivo / Xiaomi end the app after onTaskRemoved)
        m.taskRemovedAt = NOW - 9 * 60_000 - 3000;
        assertEquals(JobGuard.Cause.SWIPED, v(m, exit(JobGuard.R_SIGNALED, "")));
        assertEquals(JobGuard.Cause.SWIPED, v(m, exit(JobGuard.R_USER_REQUESTED, "")));
    }

    @Test public void exitRecordOfAnOlderProcessIsIgnored() {
        JobGuard.Marker m = marker(NOW - 10 * 60_000);
        JobGuard.Exit oldCrash = new JobGuard.Exit(JobGuard.R_CRASH, 0, 400, NOW - 3 * H, "");   // pid reused from hours ago
        assertEquals(JobGuard.Cause.SYSTEM, v(m, oldCrash));
    }

    @Test public void causeWithoutExitRecord() {   // Android 7-10, or no record kept
        JobGuard.Marker m = marker(NOW - 10 * 60_000);
        assertEquals(JobGuard.Cause.SYSTEM, v(m, null));
        assertEquals(JobGuard.Cause.CRASH, JobGuard.verdict(m, NOW, 777, 30, false, null, NOW - 9 * 60_000, false, BOOT));
        assertEquals(JobGuard.Cause.SYSTEM, JobGuard.verdict(m, NOW, 777, 30, false, null, NOW - 2 * H, false, BOOT));   // crash before the job
        assertEquals(JobGuard.Cause.REBOOT, JobGuard.verdict(m, NOW, 777, 30, false, null, 0, false, BOOT + 2 * H));
        assertEquals(JobGuard.Cause.UPDATED, JobGuard.verdict(m, NOW, 777, 31, false, null, 0, false, BOOT));
        JobGuard.Marker legacy = new JobGuard.Marker();   // a 4.7 marker: only job_alive (+ job_alive_at)
        legacy.alive = true; legacy.at = NOW - 20 * 60_000;
        assertEquals(JobGuard.Cause.SYSTEM, JobGuard.verdict(legacy, NOW, 777, 30, false, null, 0, false, BOOT));
        m.taskRemovedAt = NOW - 9 * 60_000;
        assertEquals(JobGuard.Cause.SWIPED, v(m, null));
    }

    @Test public void messagesFitTheCause() {
        String sys = JobGuard.message(JobGuard.Cause.SYSTEM, "vivo", 0, 0);
        assertTrue(sys.contains("Fix background settings"));
        assertTrue(sys.contains("Retry"));
        assertFalse(JobGuard.message(JobGuard.Cause.SWIPED, "vivo", 0, 0).contains("battery"));
        assertFalse(JobGuard.message(JobGuard.Cause.CRASH, "vivo", 0, 0).contains("battery"));
        assertTrue(JobGuard.message(JobGuard.Cause.LOW_MEMORY, "vivo", 0, 0).contains("memory"));
        assertTrue(JobGuard.message(JobGuard.Cause.SYSTEM, "vivo", 2, 5).contains("2 of 5 shorts are already done"));
        assertTrue(JobGuard.message(JobGuard.Cause.SYSTEM, "vivo", 2, 5).contains("Continue"));
        assertTrue(JobGuard.wantsFix(JobGuard.Cause.SYSTEM) && JobGuard.wantsFix(JobGuard.Cause.SWIPED));
        assertFalse(JobGuard.wantsFix(JobGuard.Cause.CRASH) || JobGuard.wantsFix(JobGuard.Cause.UPDATED));
        String block = JobGuard.reportBlock(marker(NOW - 600_000), exit(JobGuard.R_USER_REQUESTED, "stop by power"), JobGuard.Cause.SYSTEM, new int[]{2, 5}, NOW);
        assertTrue(block, block.contains("USER_REQUESTED") && block.contains("Shorts finished: 2 of 5") && block.contains("foreground service"));
    }

    // ---------------------------------------------------------------- marker writes

    @Test public void markerWrittenAndClearedSynchronously() {
        Context c = ApplicationProvider.getApplicationContext();
        Pipeline.Request rq = new Pipeline.Request();
        rq.ytUrl = "https://youtu.be/abcdefghijk"; rq.title = "My video";
        JobGuard.markStart(c, rq, 30);
        JobGuard.Marker m = JobGuard.Marker.read(Prefs.sp(c));
        assertTrue(m.alive);
        assertEquals(android.os.Process.myPid(), m.pid);
        assertEquals(30, m.ver);
        assertEquals("https://youtu.be/abcdefghijk", m.url);
        assertEquals("My video", m.title);
        assertTrue(m.boot > 0 && m.at > 0);
        JobGuard.checkpoint(c, "p1", 3);
        JobGuard.markEnd(c, false);   // failed: marker off, checkpoint kept
        m = JobGuard.Marker.read(Prefs.sp(c));
        assertFalse(m.alive);
        assertEquals("p1", m.project);
        JobGuard.markStart(c, rq, 30);   // a NEW job forgets the old checkpoint
        assertEquals("", JobGuard.Marker.read(Prefs.sp(c)).project);
        rq.resumeId = "p2"; JobGuard.checkpoint(c, "p2", 3); JobGuard.markStart(c, rq, 30);   // Continue keeps it
        assertEquals("p2", JobGuard.Marker.read(Prefs.sp(c)).project);
        JobGuard.markEnd(c, true);
        m = JobGuard.Marker.read(Prefs.sp(c));
        assertFalse(m.alive);
        assertEquals("", m.project);
    }

    @Test public void requestKeepsResumeId() {
        Pipeline.Request rq = new Pipeline.Request();
        rq.ytUrl = "https://youtu.be/abcdefghijk"; rq.resumeId = "123";
        assertEquals("123", Pipeline.Request.fromJson(rq.toJson()).resumeId);
        rq.resumeId = "";
        assertEquals("", Pipeline.Request.fromJson(rq.toJson()).resumeId);
    }

    @Test public void sessionLogSurvivesTheProcess() throws Exception {
        File dir = Files.createTempDirectory("k30log").toFile();
        AppLog.startSession(dir);
        AppLog.i("Rendering shorts 2 of 5");
        AppLog.e("something went wrong");
        Thread.sleep(300);
        AppLog.startSession(dir);   // the next process start
        String prev = AppLog.previousSession(dir, 50);
        assertTrue(prev, prev.contains("Rendering shorts 2 of 5"));
        assertTrue(prev, prev.contains("[ERROR] something went wrong"));
    }

    @Test public void projectSaveIsAtomic() {
        Context c = ApplicationProvider.getApplicationContext();
        Project p = new Project();
        p.id = "k30atomic"; p.title = "T"; p.quality = 1; p.captionsWanted = true;
        p.save(c);
        assertFalse(new File(p.dir(c), "project.json.tmp").exists());
        Project back = JobGuard.checkpointProject(c, "k30atomic");
        assertNotNull(back);
        assertEquals(1, back.quality);
        assertTrue(back.captionsWanted);
    }

    // ---------------------------------------------------------------- Continue

    static File tiny() throws Exception {
        File f = File.createTempFile("k30tiny", ".mp4");
        byte[] b = new byte[40000]; new java.util.Random(3).nextBytes(b); Files.write(f.toPath(), b);
        return f;
    }

    @Test public void continueMakesOnlyTheMissingShorts() throws Exception {
        Context c = ApplicationProvider.getApplicationContext();
        File tiny = tiny();
        Project pr = new Project();
        pr.id = "k30resume"; pr.url = "https://youtu.be/abcdefghijk"; pr.title = "Resume me"; pr.duration = 900; pr.quality = 0;
        for (int i = 0; i < 4; i++) {
            Project.Clip cl = new Project.Clip();
            cl.index = i; cl.start = 100 + i * 120; cl.end = cl.start + 50;
            if (i == 0 || i == 2) {   // finished before the phone stopped the job
                File out = new File(pr.dir(c), "Klipora_short" + (i + 1) + ".mp4");
                Files.copy(tiny.toPath(), out.toPath());
                cl.outPath = out.getAbsolutePath();
            }
            pr.clips.add(cl);
        }
        pr.save(c);
        Project.writeText(pr.transcriptFile(c), new Transcript().toJson().toString());
        assertArrayEquals(new int[]{2, 4}, JobGuard.progressOf(c, "k30resume"));
        assertEquals("History keeps the finished shorts of a stopped job", 2, Project.loadFrom(pr.dir(c)).clips.size());

        List<Integer> rendered = new ArrayList<>();
        AtomicInteger n = new AtomicInteger();
        Pipeline.parForTest = 2;
        Pipeline.renderHookForTest = (pp, prj, ci, threads, prog) -> {
            synchronized (rendered) { rendered.add(prj.clips.get(ci).index); }
            n.incrementAndGet();
            File out = new File(prj.dir(c), "Klipora_short" + (ci + 1) + "_r.mp4");
            Files.copy(tiny.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            pp.finishClipForTest(prj, prj.clips.get(ci), out);
        };
        Pipeline.Request rq = new Pipeline.Request();
        rq.ytUrl = pr.url; rq.resumeId = "k30resume";
        JobManager.get().start(JobManager.Kind.ANALYZE);
        Project done = new Pipeline(c).runResume(rq);
        JobManager.get().finish(JobManager.State.DONE, null);
        java.util.Collections.sort(rendered);
        assertEquals("only shorts #2 and #4 are made again", java.util.Arrays.asList(1, 3), rendered);
        assertEquals(4, done.clips.size());
        for (Project.Clip cl : done.clips) assertTrue(JobGuard.clipDone(cl));
        assertEquals("k30resume", Prefs.sp(c).getString("current_project", ""));
    }

    @Test public void lowMemoryMakesOneAtATime() {
        Context c = ApplicationProvider.getApplicationContext();
        Pipeline p = new Pipeline(c);
        JobGuard.setLowMemoryNext(c);
        assertEquals(1, p.parallelism(5));
        JobGuard.markEnd(c, true);   // a job that finishes lifts it
        assertFalse(JobGuard.lowMemoryNext(c));
    }

    // ---------------------------------------------------------------- the foreground service

    @Test public void serviceGoesForegroundAtOnceAndRecordsIt() {
        Context c = ApplicationProvider.getApplicationContext();
        Prefs.sp(c).edit().putBoolean("job_alive", true).commit();
        org.robolectric.android.controller.ServiceController<ProcessingService> sc = Robolectric.buildService(ProcessingService.class).create();
        ProcessingService s = sc.get();
        s.onStartCommand(new android.content.Intent(c, ProcessingService.class), 0, 1);   // no pending job: goes foreground, then stops
        android.app.Notification n = org.robolectric.Shadows.shadowOf(s).getLastForegroundNotification();
        assertNotNull("startForeground is called before anything else", n);
        assertTrue((n.flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0);
        assertTrue(Prefs.sp(c).getString("job_fgs", ""), Prefs.sp(c).getString("job_fgs", "").startsWith("on (foreground service, data sync)"));
    }

    @Test public void startWritesTheMarkerAndStartsTheForegroundService() {
        Context c = ApplicationProvider.getApplicationContext();
        Pipeline.Request rq = new Pipeline.Request();
        rq.ytUrl = "https://youtu.be/abcdefghijk"; rq.title = "Start me";
        JobManager.get().finish(JobManager.State.DONE, null);
        assertTrue(ProcessingService.start(c, rq));
        android.content.Intent i = org.robolectric.Shadows.shadowOf((android.app.Application) c).getNextStartedService();
        assertNotNull(i);
        assertEquals(ProcessingService.class.getName(), i.getComponent().getClassName());
        JobGuard.Marker m = JobGuard.Marker.read(Prefs.sp(c));
        assertTrue(m.alive);
        assertEquals("Start me", m.title);
        assertFalse("a second start while running is refused", ProcessingService.start(c, rq));
        JobManager.get().finish(JobManager.State.CANCELLED, null);
        JobGuard.markEnd(c, false);
    }

    @Test public void swipeFromRecentsIsRecorded() {
        Context c = ApplicationProvider.getApplicationContext();
        Pipeline.Request rq = new Pipeline.Request(); rq.ytUrl = "https://youtu.be/abcdefghijk";
        JobGuard.markStart(c, rq, 30);
        JobManager.get().start(JobManager.Kind.ANALYZE);
        ProcessingService s = Robolectric.buildService(ProcessingService.class).create().get();
        s.onTaskRemoved(new android.content.Intent());
        assertTrue(JobGuard.Marker.read(Prefs.sp(c)).taskRemovedAt > 0);
        JobManager.get().finish(JobManager.State.CANCELLED, null);
        JobGuard.markEnd(c, false);
    }
}
