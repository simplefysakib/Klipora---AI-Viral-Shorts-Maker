package com.viral.shortsmaker;

import static org.junit.Assert.*;

import com.viral.shortsmaker.captions.CaptionBuilder;
import com.viral.shortsmaker.captions.CaptionLayout;
import com.viral.shortsmaker.captions.CaptionStyle;
import com.viral.shortsmaker.captions.CaptionStyles;
import com.viral.shortsmaker.captions.Word;
import com.viral.shortsmaker.core.AiClient;
import com.viral.shortsmaker.core.ClipAligner;
import com.viral.shortsmaker.core.Pipeline;
import com.viral.shortsmaker.core.Project;
import com.viral.shortsmaker.core.Transcript;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** v11: question-led clip picking, hook-line placement, ALL CAPS on Hindi, hook title removed. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class V11Test {

    /** builds a transcript from sentences: each {text, start} becomes one segment with evenly timed words */
    static Transcript tr(Object[][] sents) {
        Transcript t = new Transcript();
        t.language = "en";
        for (Object[] s : sents) {
            String text = (String) s[0];
            double st = ((Number) s[1]).doubleValue();
            String[] w = text.split(" ");
            double per = 0.32;
            for (int i = 0; i < w.length; i++) t.words.add(new Word(w[i], st + i * per, st + i * per + 0.28));
            t.segments.add(new Transcript.Segment(st, st + w.length * per, " " + text));
        }
        return t;
    }

    static final Object[][] TALK = {
            {"Welcome back to the show everyone.", 0},
            {"Today we talk about money and fame.", 3},
            {"So tell me something.", 7},
            {"Did you really turn down a hundred crore movie offer?", 9},
            {"Yes I did and here is the full story.", 13},
            {"They wanted me to change my whole look for the film.", 16},
            {"I said no because my fans know me for who I am.", 20},
            {"Two years later that same director called me again.", 24},
            {"And this time he offered me the lead role.", 28},
            {"That is how saying no changed my career forever.", 32},
            {"The weather in Mumbai has been crazy lately.", 37},
            {"It rained for three days and the roads were flooded.", 41},
            {"My driver got stuck for six hours near the sea link.", 45},
            {"We just sat there eating chips and listening to old songs.", 49},
            {"Honestly it was kind of fun in a strange way.", 53},
            {"Then the water went down and we finally got home.", 57},
            {"What is the one habit that made you rich?", 62},
            {"Waking up at five every single day for ten years.", 66},
            {"While others slept I learned skills nobody else had.", 70},
            {"That one habit paid for my first house.", 74},
            {"And it still pays every month today.", 78},
            {"That is the real secret nobody tells you.", 82},
            {"Okay let us take a short break now.", 87},
    };

    @Test public void detectsQuestions() {
        assertTrue(ClipAligner.isQuestion("Did you really turn down a hundred crore movie offer?"));
        assertTrue(ClipAligner.isQuestion("What is the one habit that made you rich"));
        assertTrue(ClipAligner.isQuestion("Do you think fame changes people"));
        assertTrue(ClipAligner.isQuestion("Kya aapne kabhi socha tha"));
        assertTrue(ClipAligner.isQuestion("aapne arjun reddy se shaadi kyun ki"));
        assertTrue(ClipAligner.isQuestion("क्या आपको डर लगता है"));
        assertTrue(ClipAligner.isQuestion("आपने अर्जुन रेड्डी से शादी की?"));
        assertFalse(ClipAligner.isQuestion("I said no because my fans know me."));
        assertFalse(ClipAligner.isQuestion("हमें पता नहीं कि तुम को क्या कैसा वातावरण मिला"));
        assertFalse(ClipAligner.isQuestion("mujhe nahi pata ki kya hua tha us din bilkul bhi"));
        assertFalse(ClipAligner.isQuestion(""));
    }

    @Test public void answerOnlyClipMovesBackToTheQuestion() {
        Transcript t = tr(TALK);
        // the clip the AI picked opens on the answer ("Yes I did ...")
        double qs = ClipAligner.questionStart(t.segments, t.words, 13, 36, -1, false);
        assertEquals(9.0, qs, 0.35);
        // with the AI's question line it starts exactly there too
        assertEquals(9.0, ClipAligner.questionStart(t.segments, t.words, 13, 36, 3, true), 0.35);
        // a clip that already opens on a question stays
        assertEquals(-1, ClipAligner.questionStart(t.segments, t.words, 62, 86, -1, false), 0.01);
    }

    @Test public void connectorBeforeQuestionIsDropped() {
        Transcript t = tr(new Object[][]{{"Great point.", 0}, {"But how did you find your first client?", 2}, {"I cold called fifty shops in one week and one said yes.", 5},
                {"That shop is still my biggest client today.", 10}, {"So never stop calling people.", 14}, {"It works every single time.", 17}, {"Trust me on that one.", 20}, {"Okay next.", 23}});
        double[] h = ClipAligner.hookSafe(t.segments, t.words, new double[]{2, 23}, 100);
        assertNotNull(h);
        assertEquals("how did you find your first client?", ClipAligner.openingLine(t.segments, t.words, h[0], h[1]).trim());
    }

    @Test public void hookLineIsFoundByItsWords() {
        Transcript t = tr(TALK);
        // AI seconds 4 s off, its copied words are right
        assertEquals(62.0, ClipAligner.locateHook(t.words, "What is the one habit that made you rich?", 58), 0.01);
        assertEquals(-1, ClipAligner.locateHook(t.words, "Completely different words here", 58), 0.01);
        double[] r = ClipAligner.startAt(t.segments, t.words, 62.0, new double[]{57, 86}, 100);
        assertEquals(62.0, r[0], 0.15);
    }

    static AiClient.Candidate cand(String type, int qLine, double s, double e, String hook, int score, boolean ctx) {
        AiClient.Candidate c = new AiClient.Candidate();
        c.type = type; c.questionLine = qLine; c.start = s; c.end = e; c.hook = hook; c.score = score; c.needsContext = ctx;
        c.hookScore = score; c.standaloneScore = ctx ? 40 : score; c.valueScore = score; c.payoffScore = score; c.title = hook;
        return c;
    }

    @Test public void questionLedClipsRankFirstAndCountIsKept() {
        Transcript t = tr(TALK);
        List<AiClient.Candidate> cs = new ArrayList<>(Arrays.asList(
                cand("story", -1, 37, 61, "The weather in Mumbai has been crazy lately.", 84, false),     // side topic, high AI score
                cand("qa", 3, 13, 36, "Yes I did and here is the full story.", 80, false),                    // answer-only start
                cand("qa", 16, 62, 86, "What is the one habit that made you rich?", 78, false)));
        List<Project.Clip> p = Pipeline.pickClips(cs, t, 90, 3);
        StringBuilder dbg = new StringBuilder();
        for (Project.Clip c : p) dbg.append(c.start).append('-').append(c.end).append(' ').append(c.score).append(' ').append(c.hook).append(" | ");
        // v19: podcast shorts are whole stories of >= 35 s, so this 90 s talk holds 2 of them (the 3rd would overlap)
        assertEquals(dbg.toString(), 2, p.size());
        for (Project.Clip c : p) assertTrue(dbg.toString(), c.end - c.start >= 26);
        assertTrue(p.get(0).hook, ClipAligner.isQuestion(p.get(0).hook));
        assertTrue(p.get(1).hook, ClipAligner.isQuestion(p.get(1).hook));
        assertTrue(p.get(0).reason.startsWith("Q&A"));
        boolean movie = false;
        for (Project.Clip c : p) if (c.hook.startsWith("Did you really")) { movie = true; assertEquals(9.0, c.start, 0.4); }
        assertTrue("the answer-only clip now opens on its question", movie);
        // max asked is respected
        assertEquals(1, Pipeline.pickClips(cs, t, 90, 1).size());
    }

    @Test public void needsContextRanksLastButStillFillsTheCount() {
        Transcript t = tr(TALK);
        List<AiClient.Candidate> cs = new ArrayList<>(Arrays.asList(
                cand("other", -1, 37, 61, "The weather in Mumbai has been crazy lately.", 90, true),
                cand("qa", 16, 62, 86, "What is the one habit that made you rich?", 70, false)));
        List<Project.Clip> p = Pipeline.pickClips(cs, t, 90, 2);
        // v19: the needs-context moment grows into a 35 s+ story that overlaps the clean one, so the clean one wins
        assertEquals(1, p.size());
        assertTrue(p.get(0).hook.startsWith("What is"));
        // with the 2.9 floor (other genres, 20 s) both still fit and the needs-context one fills the count
        List<AiClient.Candidate> cs2 = new ArrayList<>(Arrays.asList(
                cand("other", -1, 37, 61, "The weather in Mumbai has been crazy lately.", 90, true),
                cand("qa", 16, 62, 86, "What is the one habit that made you rich?", 70, false)));
        assertEquals(2, Pipeline.pickClips(cs2, t, 90, 2, PromptManager.Category.OTHER).size());
    }

    @Test public void parsesNewFields() throws Exception {
        String raw = "[{\"type\":\"qa\",\"question_line\":3,\"start_line\":3,\"end_line\":9,\"start\":9,\"end\":36,\"hook_text\":\"Did you really?\","
                + "\"title\":\"T\",\"needs_context\":false,\"hook_score\":90,\"standalone_score\":85,\"value_score\":80,\"payoff_score\":80,\"viral_score\":88}]";
        AiClient.Candidate c = AiClient.parseForTest(raw, 100).get(0);
        assertTrue(c.isQa());
        assertEquals(3, c.questionLine);
        assertFalse(c.needsContext);
        // older answers without the fields still parse
        AiClient.Candidate o = AiClient.parseForTest("[{\"start\":10,\"end\":40,\"viral_score\":80}]", 100).get(0);
        assertEquals("", o.type);
        assertEquals(-1, o.questionLine);
    }

    @Test public void promptAsksForQuestionLedClips() {
        String p = PromptManager.buildViralPrompt(PromptManager.Category.PODCAST, "t", "0 [0-3] hi\n", 3, 100);
        assertTrue(p.contains("QUESTION-LED CLIPS"));
        assertTrue(p.contains("question_line"));
        assertTrue(p.contains("needs_context"));
    }

    @Test public void allCapsLeavesHindiScriptAlone() {
        List<Word> hi = CaptionBuilder.clean(Arrays.asList(new Word("आपने", 0, 0.3), new Word("शादी", 0.3, 0.6), new Word("की?", 0.6, 0.9)), true);
        assertEquals("आपने", hi.get(0).text);
        assertEquals("शादी", hi.get(1).text);
        List<Word> en = CaptionBuilder.clean(Arrays.asList(new Word("aapne", 0, 0.3), new Word("shaadi", 0.3, 0.6), new Word("ki?", 0.6, 0.9)), true);
        assertEquals("AAPNE", en.get(0).text);
        // mixed: Latin capitalised, Devanagari unchanged, nothing dropped
        List<Word> mix = CaptionBuilder.clean(Arrays.asList(new Word("Arjun", 0, 0.3), new Word("रेड्डी", 0.3, 0.6)), true);
        assertEquals(2, mix.size());
        assertEquals("ARJUN", mix.get(0).text);
        assertEquals("रेड्डी", mix.get(1).text);
        CaptionStyle st = CaptionStyles.byId("hormozi");
        st.uppercase = true;
        String ass = CaptionBuilder.buildAss(hi, st, new CaptionLayout(), 2);
        assertTrue(ass.contains("आपने"));
    }

    @Test public void hookTitleIsGone() {
        CaptionStyle st = CaptionStyles.byId("hormozi");
        assertFalse(st.toJson().has("hookTitle"));
        String ass = CaptionBuilder.buildAss(Arrays.asList(new Word("hello", 0.2, 0.6)), st, new CaptionLayout(), 10);
        assertFalse(ass.contains("Hook"));
    }
}
