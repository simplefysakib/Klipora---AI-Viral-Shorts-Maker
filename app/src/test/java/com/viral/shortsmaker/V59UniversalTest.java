package com.viral.shortsmaker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.viral.shortsmaker.core.ClipAligner;
import com.viral.shortsmaker.core.YtEngine;

import org.junit.Test;

public class V59UniversalTest {
    @Test public void promptSupportsGlobalLanguagesAndCompleteStories() {
        String p = PromptManager.buildViralPrompt(
                PromptManager.Category.OTHER,
                "Explicación de tecnología",
                "0 [0-4] ¿Sabías que esta función puede ahorrar batería?\n1 [4-12] La explicación completa está aquí.",
                1,
                12);
        assertTrue(p.contains("UNIVERSAL BEST SHORTS ENGINE"));
        assertTrue(p.contains("GLOBAL LANGUAGE MODE"));
        assertTrue(p.contains("topic complete"));
        assertTrue(p.contains("narration"));
        assertTrue(p.contains("source_language"));
        assertTrue(p.contains("duration <= 60 seconds"));
    }

    @Test public void clipsHaveOneMinuteMaximum() {
        assertEquals(60.0, ClipAligner.MAX_LEN, 0.01);
    }

    @Test public void ageRestrictedErrorsAreRecognizedClearly() {
        Exception e = new Exception("ERROR: [youtube] Sign in to confirm your age. This video may be inappropriate for some users.");
        assertTrue(YtEngine.isAgeRestricted(e));
        assertTrue(YtEngine.friendlyYtError(e).contains("age-restricted"));
    }
}
