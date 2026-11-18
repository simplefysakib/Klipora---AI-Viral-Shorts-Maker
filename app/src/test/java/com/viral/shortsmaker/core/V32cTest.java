package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.captions.CaptionBuilder;
import com.viral.shortsmaker.captions.CaptionLayout;
import com.viral.shortsmaker.captions.CaptionStyles;
import com.viral.shortsmaker.captions.Word;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** code 32 (5.0) overnight audit, part three: render process deaths, face marker, captions, search speed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V32cTest {

    // ---------------------------------------------------------------- B1: one death, one judgement
    @Test public void oneRenderDeathIsJudgedOnce() {
        MediaRunner.DeathBook b = new MediaRunner.DeathBook();
        int d1 = b.death();
        assertEquals(MediaRunner.DeathBook.FIRST, b.verdict(d1));    // the first command handled judges it
        assertEquals(MediaRunner.DeathBook.SAME, b.verdict(d1));     // the other commands running in it take that verdict
        assertEquals(MediaRunner.DeathBook.SAME, b.verdict(d1));
        int d2 = b.death();
        assertEquals(MediaRunner.DeathBook.FIRST, b.verdict(d2));
        // the stall watchdog's own kill: the other commands are not at fault
        b.killPending();
        int d3 = b.death();
        assertEquals(MediaRunner.DeathBook.KILLED, b.verdict(d3));
        int d4 = b.death();
        assertEquals(MediaRunner.DeathBook.FIRST, b.verdict(d4));
        b.killPending(); b.killFailed();
        assertEquals(MediaRunner.DeathBook.FIRST, b.verdict(b.death()));
    }

    @Test public void mildestStepForOneDeath() {
        assertEquals(RenderSafety.K_HW_DECODE, MediaRunner.mildestKind(RenderSafety.K_HW_ENCODE, Arrays.asList(RenderSafety.K_HW_ENCODE, RenderSafety.K_HW_DECODE)));
        assertEquals(RenderSafety.K_HW_ENCODE, MediaRunner.mildestKind(RenderSafety.K_HW_ENCODE, Arrays.asList(RenderSafety.K_HW_ENCODE, RenderSafety.K_SOFTWARE)));
        assertEquals(RenderSafety.K_SOFTWARE, MediaRunner.mildestKind(RenderSafety.K_SOFTWARE, null));
    }

    // ---------------------------------------------------------------- B2: GPU face marker with two shorts at once
    @Test public void faceMarkerStaysWhileAnyShortRunsOnTheGpu() {
        Context c = ApplicationProvider.getApplicationContext();
        String k = "rs_face_inflight";
        while (RenderSafety.faceRunsForTest() > 0) RenderSafety.faceInflight(c, false);   // another test left one open
        RenderSafety.faceInflight(c, true);    // short 1
        RenderSafety.faceInflight(c, true);    // short 2
        RenderSafety.faceInflight(c, false);   // short 1 done (4.9 cleared the marker here)
        assertTrue(Prefs.sp(c).getBoolean(k, false));
        RenderSafety.faceInflight(c, false);
        assertFalse(Prefs.sp(c).contains(k));
        RenderSafety.faceInflight(c, false);   // never below zero
        assertEquals(0, RenderSafety.faceRunsForTest());
    }

    // ---------------------------------------------------------------- B8: a word with the same start as the next stays visible
    @Test public void wordWithTheSameStartIsNotErased() {
        List<Word> w = new ArrayList<>(Arrays.asList(new Word("ONE", 0.5, 0.9), new Word("SAME", 1.0, 1.0), new Word("NEXT", 1.0, 1.4), new Word("END.", 1.5, 1.9)));
        String ass = CaptionBuilder.buildAss(w, CaptionStyles.byId("hormozi"), CaptionLayout.defaultFor(9f / 16f));
        assertTrue(ass, ass.contains("SAME"));
        assertTrue(ass.contains("NEXT"));
    }

    // ---------------------------------------------------------------- A8: the top-up search on a long transcript
    @Test public void topUpSearchOnThreeHoursIsQuick() {
        Transcript t = V32PerfProbe.synth(3);
        long t0 = System.nanoTime();
        double[] w = Pipeline.windowIn(t, 60, 3 * 3600 - 60, 3 * 3600, 35, 60, true);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertNotNull(w);
        assertTrue(w[1] - w[0] >= 35);
        assertTrue("3 h top-up search took " + ms + " ms (4.9: ~18 s on this box)", ms < 6000);
    }

    @Test public void pinnedTokensAreTheCachedOnes() throws Exception {
        Transcript t = V32PerfProbe.synth(0.05);
        List<ClipAligner.Tok> a;
        try (AutoCloseable p = ClipAligner.pin(t.segments, t.words)) { a = ClipAligner.tokens(t.segments, t.words); }
        assertSame(a, ClipAligner.tokens(t.segments, t.words));
    }
}
