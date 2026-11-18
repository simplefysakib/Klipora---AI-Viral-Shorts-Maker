package com.viral.shortsmaker;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.chip.ChipGroup;
import com.viral.shortsmaker.core.JobManager;
import com.viral.shortsmaker.core.Prefs;
import com.viral.shortsmaker.core.Project;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.time.Duration;

/** code 29: the count picked is the count asked for, and the results screen lists every short made (N = 1, 3, 5) */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi")
public class V29ResultsScreenTest {
    private void idle(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    @Before public void keys() {
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit()
                .putString("gemini_key", "AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY12")
                .putString("groq_key", "gsk_DUMMYDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY1234")
                .putBoolean("is_onboarding_done", true).apply();
        JobManager.get().removeAllListeners();
        JobManager.get().project = null;
        JobManager.get().reset();
    }

    private Project madeProject(int n) throws Exception {
        android.content.Context ctx = ApplicationProvider.getApplicationContext();
        Project pr = new Project();
        pr.id = "k29ui_" + n;
        pr.title = "Test video";
        for (int i = 0; i < n; i++) {
            Project.Clip c = new Project.Clip();
            c.index = i; c.start = i * 100; c.end = c.start + 40; c.score = 80;
            File f = new File(pr.dir(ctx), "Klipora_short" + (i + 1) + ".mp4");
            Files.write(f.toPath(), new byte[30000]);
            c.outPath = f.getAbsolutePath();
            pr.clips.add(c);
        }
        pr.save(ctx);
        return pr;
    }

    @Test public void resultsListEveryShort() throws Exception {
        for (int n : new int[]{1, 3, 5}) {
            Project pr = madeProject(n);
            MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
            idle(300);
            JobManager jm = JobManager.get();
            jm.project = pr;
            jm.finish(JobManager.State.DONE, null);
            a.onJobChanged(jm);
            idle(500);
            TextView title = a.findViewById(R.id.tvResultsTitle);
            assertEquals(n + (n == 1 ? " short is ready" : " shorts are ready"), title.getText().toString());
            RecyclerView rv = a.findViewById(R.id.rvClips);
            assertEquals(n, rv.getAdapter().getItemCount());
            assertEquals(n, Project.loadFrom(pr.dir(a)).clips.size());
            jm.reset();
        }
    }


    @Test public void linkSurvivesScreenRebuild() throws Exception {
        org.robolectric.android.controller.ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        idle(300);
        Field u = MainActivity.class.getDeclaredField("srcYtUrl"); u.setAccessible(true);
        Field t = MainActivity.class.getDeclaredField("srcTitle"); t.setAccessible(true);
        Field d = MainActivity.class.getDeclaredField("srcDuration"); d.setAccessible(true);
        u.set(a, "https://youtu.be/RE3_PGwDwhw");
        t.set(a, "Test video");
        d.set(a, 1234.0);
        c.recreate();
        MainActivity b = c.get();
        idle(300);
        assertNotSame(a, b);
        assertEquals("https://youtu.be/RE3_PGwDwhw", u.get(b));
        assertEquals("Test video", t.get(b));
        assertEquals(View.VISIBLE, b.findViewById(R.id.cardControls).getVisibility());
    }

    @Test public void lockedCountIsNeverQuietlyOne() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(300);
        ChipGroup cg = a.findViewById(R.id.chipGroupShortsCount);
        cg.check(R.id.chipCount3);   // e.g. put back by the system after the phone closed the app during an unlock ad
        idle(100);
        Method m = MainActivity.class.getDeclaredMethod("lockedCountChip"); m.setAccessible(true);
        int locked = (Integer) m.invoke(a);
        if (Prefs.PREMIUM_EDITION) assertEquals(-1, locked);
        else assertEquals(2, locked);   // Generate asks to unlock it again instead of making 1 short
    }

    @Test public void countChipIsTheCountRequested() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(300);
        a.findViewById(R.id.cardControls).setVisibility(View.VISIBLE);
        Field f = MainActivity.class.getDeclaredField("sessionCountUnlocked"); f.setAccessible(true);
        boolean[] unlocked = (boolean[]) f.get(null);
        for (int i = 0; i < unlocked.length; i++) unlocked[i] = true;   // free: each count unlocked with an ad
        Method sel = MainActivity.class.getDeclaredMethod("selectedCount"); sel.setAccessible(true);
        ChipGroup cg = a.findViewById(R.id.chipGroupShortsCount);
        int[] ids = {R.id.chipCount1, R.id.chipCount3, R.id.chipCount5};
        int[] want = {1, 3, 5};
        try {
            for (int k = 0; k < ids.length; k++) {
                a.findViewById(ids[k]).performClick();
                idle(200);
                assertEquals(ids[k], cg.getCheckedChipId());
                assertEquals(want[k], (int) (Integer) sel.invoke(a));
            }
            // the audio languages arriving later (a re-layout of the form) never reset the count
            Method m = MainActivity.class.getDeclaredMethod("showAudioTracks", String.class); m.setAccessible(true);
            m.invoke(a, (Object) null);
            idle(200);
            assertEquals(5, (int) (Integer) sel.invoke(a));
        } finally {
            for (int i = 1; i < unlocked.length; i++) unlocked[i] = false;
        }
    }
}
