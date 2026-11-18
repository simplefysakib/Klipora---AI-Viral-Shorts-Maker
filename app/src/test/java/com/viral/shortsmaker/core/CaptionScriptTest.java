package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import com.viral.shortsmaker.captions.Word;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CaptionScriptTest {
    @Test public void translatedWordsStayInsideTheSentenceTime() {
        List<Word> orig = Arrays.asList(new Word("यह", 1.0, 1.3), new Word("बहुत", 1.3, 1.7), new Word("अच्छा", 2.5, 3.0), new Word("है", 3.0, 3.2));
        List<Word> en = CaptionScript.spread(orig, "This is very good");
        assertEquals(4, en.size());
        assertEquals(1.0, en.get(0).start, 1e-6);
        assertEquals(3.2, en.get(3).end, 1e-6);
        for (int i = 1; i < en.size(); i++) assertTrue(en.get(i).start >= en.get(i - 1).start);
        for (Word w : en) assertFalse("no word inside the pause", w.start > 1.71 && w.start < 2.49);
    }

    @Test public void sentencesSplitAtPausesAndPunctuation() {
        List<Word> w = Arrays.asList(new Word("Hello.", 0, 0.3), new Word("How", 0.4, 0.6), new Word("are", 0.6, 0.8), new Word("you", 2.0, 2.2));
        List<int[]> s = CaptionScript.sentences(w);
        assertEquals(3, s.size());
    }

    @Test public void aiAnswerWithLinesOnlyGetsTimesFromTheTranscript() throws Exception {
        Transcript t = new Transcript();
        t.segments.add(new Transcript.Segment(0, 10, "a"));
        t.segments.add(new Transcript.Segment(10, 40, "b"));
        List<AiClient.Candidate> c = AiClient.parseCandidates("[{\"start_line\":0,\"end_line\":1,\"viral_score\":90,\"title\":\"x\"}]", 100, t);
        assertEquals(1, c.size());
        assertEquals(0, c.get(0).start, 1e-6);
        assertEquals(40, c.get(0).end, 1e-6);
        String p = t.forPromptCompact(240000);
        assertTrue(p.startsWith("0 [0-10] a\n1 [10-40] b"));
    }
}
