package com.viral.shortsmaker;

import static org.junit.Assert.*;

import com.viral.shortsmaker.captions.CaptionBuilder;
import com.viral.shortsmaker.captions.CaptionFonts;
import com.viral.shortsmaker.captions.CaptionLayout;
import com.viral.shortsmaker.captions.CaptionPreviewView;
import com.viral.shortsmaker.captions.CaptionStyle;
import com.viral.shortsmaker.captions.Word;
import com.viral.shortsmaker.core.AiClient;
import com.viral.shortsmaker.core.ClipAligner;
import com.viral.shortsmaker.core.Pipeline;
import com.viral.shortsmaker.core.Profanity;
import com.viral.shortsmaker.core.Project;
import com.viral.shortsmaker.core.Transcript;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** v12 (2.3): podcast-only question starts, gaali rule, topic-complete endings, rounded highlight box, size handle. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class V12Test {

    static AiClient.Candidate cand(String type, int qLine, double s, double e, String hook, int score) {
        return V11Test.cand(type, qLine, s, e, hook, score, false);
    }

    // ------------------------------------------------------------------ 1. question start only for podcasts

    @Test public void questionStartOnlyForPodcast() {
        Transcript t = V11Test.tr(V11Test.TALK);
        List<AiClient.Candidate> cs = new ArrayList<>(Arrays.asList(cand("qa", 3, 13, 36, "Yes I did and here is the full story.", 80)));
        Project.Clip pod = Pipeline.pickClips(cs, t, 90, 1, PromptManager.Category.PODCAST).get(0);
        assertEquals("podcast: moved back to the question", 9.0, pod.start, 0.4);
        assertTrue(pod.reason.startsWith("Q&A"));
        for (PromptManager.Category cat : new PromptManager.Category[]{PromptManager.Category.OTHER, PromptManager.Category.MOVIES_SERIES, PromptManager.Category.GAMING}) {
            List<AiClient.Candidate> c2 = new ArrayList<>(Arrays.asList(cand("qa", 3, 13, 36, "Yes I did and here is the full story.", 80)));
            Project.Clip o = Pipeline.pickClips(c2, t, 90, 1, cat).get(0);
            assertEquals(cat + ": keeps the AI's own start", 13.0, o.start, 0.4);
            assertFalse(o.reason.startsWith("Q&A"));
        }
    }

    @Test public void questionClipsGetNoBonusOutsidePodcast() {
        Transcript t = V11Test.tr(V11Test.TALK);
        List<AiClient.Candidate> cs = new ArrayList<>(Arrays.asList(
                cand("story", -1, 37, 61, "The weather in Mumbai has been crazy lately.", 84),
                cand("qa", 16, 62, 86, "What is the one habit that made you rich?", 80)));
        assertTrue(Pipeline.pickClips(cs, t, 90, 2, PromptManager.Category.PODCAST).get(0).hook.startsWith("What is"));
        List<AiClient.Candidate> cs2 = new ArrayList<>(Arrays.asList(
                cand("story", -1, 37, 61, "The weather in Mumbai has been crazy lately.", 84),
                cand("qa", 16, 62, 86, "What is the one habit that made you rich?", 80)));
        assertTrue(Pipeline.pickClips(cs2, t, 90, 2, PromptManager.Category.OTHER).get(0).hook.startsWith("The weather"));
    }

    @Test public void promptIsGenreAware() {
        String pod = PromptManager.buildViralPrompt(PromptManager.Category.PODCAST, "t", "0 [0-1] hi", 3, 100);
        String vlog = PromptManager.buildViralPrompt(PromptManager.Category.OTHER, "t", "0 [0-1] hi", 3, 100);
        assertTrue(pod.contains("QUESTION-LED CLIPS"));
        assertFalse(vlog.contains("QUESTION-LED CLIPS"));
        assertTrue(vlog.contains("PICK FREELY"));
        assertTrue(vlog.contains("never pick a weaker moment just because it has a question"));   // v24: a clear question opener wins ties only
        assertTrue(pod.contains("CLEAN, CLEAR START") && vlog.contains("CLEAN, CLEAR START"));
        for (String p : new String[]{pod, vlog}) {
            assertTrue(p.contains("ENDING (very important)"));
            assertTrue(p.contains("90 seconds"));
            assertTrue(p.contains("GAALI"));
            assertTrue(p.contains("has_profanity"));
        }
    }

    // ------------------------------------------------------------------ 2. gaali

    @Test public void detectsGaaliWithoutFalseAlarms() {
        for (String s : new String[]{"yeh banda chutiya hai", "What the fuck was that", "bhenchod kya scene hai", "tu gandu hai kya",
                "ये चूतिया है", "भोसड़ीके चुप", "fucking amazing", "he is a motherfucker", "bullshit yaar", "saala harami"})
            assertFalse(s, Profanity.find(s).isEmpty());
        for (String s : new String[]{"mujhe chhod do", "isko chod ke aage badho", "kutta bhaunk raha tha", "meri saali ki shaadi hai",
                "phoolon ki gandh", "MC Stan ka gaana", "ye choodi kitne ki hai", "Dick Van Dyke was great", "kamal ka kaam", "मुझे छोड़ दो",
                "हमारा देश", "basically shit happens nahi bolna"})
            if (!s.contains("shit")) assertTrue(s + " -> " + Profanity.find(s), Profanity.find(s).isEmpty());
    }

    static Transcript gaaliTalk() {
        return V11Test.tr(new Object[][]{
                {"Welcome to the show today.", 0},
                {"This guy came to my office and said something crazy.", 3},
                {"He said bhenchod you will never make it in this city.", 8},
                {"I laughed and said watch me become your boss.", 12},
                {"Five years later I bought his entire company.", 16},
                {"He still calls me sir every single morning.", 20},
                {"That is the best revenge you can ever take.", 24},
                {"Let us talk about something calmer now.", 30},
                {"My morning routine is very simple and boring.", 34},
                {"I drink warm water and read for twenty minutes.", 38},
                {"Then I walk for half an hour without my phone.", 42},
                {"It keeps my head clear for the whole day.", 46},
                {"Everyone should try it for just one week.", 50},
                {"You will feel the difference very fast.", 54},
                {"Okay let us take a break.", 59},
        });
    }

    @Test public void gaaliClipDroppedUnlessClearlyTheMostViral() {
        Transcript t = gaaliTalk();
        // gaali clip only a little better than the clean one: the clean one wins, gaali clip only fills the count
        List<AiClient.Candidate> cs = new ArrayList<>(Arrays.asList(
                cand("story", -1, 3, 29, "This guy came to my office and said something crazy.", 82),
                cand("story", -1, 34, 58, "My morning routine is very simple and boring.", 80)));
        List<Project.Clip> p = Pipeline.pickClips(cs, t, 62, 1, PromptManager.Category.OTHER);
        assertEquals(1, p.size());
        assertTrue(p.get(0).hook, p.get(0).hook.startsWith("My morning"));
        // asking for 2 keeps the count: the gaali clip comes last
        List<AiClient.Candidate> cs2 = new ArrayList<>(Arrays.asList(
                cand("story", -1, 3, 29, "This guy came to my office and said something crazy.", 82),
                cand("story", -1, 34, 58, "My morning routine is very simple and boring.", 80)));
        List<Project.Clip> p2 = Pipeline.pickClips(cs2, t, 62, 2, PromptManager.Category.OTHER);
        assertEquals(2, p2.size());
        assertTrue(p2.get(1).profane);
        // clearly the most viral (96 vs 70): kept first, words untouched
        List<AiClient.Candidate> cs3 = new ArrayList<>(Arrays.asList(
                cand("story", -1, 3, 29, "This guy came to my office and said something crazy.", 96),
                cand("story", -1, 34, 58, "My morning routine is very simple and boring.", 70)));
        List<Project.Clip> p3 = Pipeline.pickClips(cs3, t, 62, 1, PromptManager.Category.OTHER);
        assertTrue(p3.get(0).hook, p3.get(0).hook.startsWith("This guy"));
        assertTrue(p3.get(0).profane);
    }

    @Test public void captionsKeepGaaliUncensored() {
        List<Word> w = Arrays.asList(new Word("bhenchod", 0, 0.5), new Word("fuck", 0.6, 1.0), new Word("yaar", 1.1, 1.5));
        String ass = CaptionBuilder.buildAss(w, new CaptionStyle(), new CaptionLayout(), 2);
        assertTrue(ass.toLowerCase().contains("bhenchod"));
        assertTrue(ass.toLowerCase().contains("fuck"));
        assertFalse(ass.contains("***"));
    }

    // ------------------------------------------------------------------ 3. endings

    @Test public void topicThatEndsWithinTwoMinutesIsKeptWhole() {
        // a 90 s answer: the AI says it ends at the concluding line; the short ends right there, not at 60 s
        List<Object[]> s = new ArrayList<>();
        s.add(new Object[]{"How did you survive your first year in Mumbai with no money?", 0});
        for (int i = 0; i < 22; i++) s.add(new Object[]{"I slept at the station and worked two jobs every single day number " + i + ".", 4 + i * 4});
        s.add(new Object[]{"And that is how the city made me who I am today.", 92});
        s.add(new Object[]{"Okay so next question for you.", 97});
        s.add(new Object[]{"What is your favourite food in the whole world?", 100});
        Transcript t = V11Test.tr(s.toArray(new Object[0][]));
        List<AiClient.Candidate> cs = new ArrayList<>(Arrays.asList(cand("qa", 0, 0, 95.3, "How did you survive your first year in Mumbai with no money?", 90)));
        Project.Clip c = Pipeline.pickClips(cs, t, 110, 1, PromptManager.Category.PODCAST).get(0);
        assertEquals(0, c.start, 0.2);
        // v18 (2.9): the topic runs past 1:30, so the short ends at the last sentence end before 1:30 (2.8: 'who I am today.' at 95 s)
        assertTrue("ends before 1:30 (" + c.end + ")", c.end <= 90.0 && c.end > 80);
        boolean afterFullStop = false;   // (this synthetic talk's sentences overlap in time, so look at the word that ends right here)
        for (Word x : t.words) if (x.text.endsWith(".") && x.end <= c.end + 1e-6 && x.end >= c.end - 0.5) afterFullStop = true;
        assertTrue("ends right after a sentence's last word (" + c.end + ")", afterFullStop);
    }

    @Test public void neverEndsMidSentence() {
        Transcript t = V11Test.tr(V11Test.TALK);
        // AI end lands in the middle of "Two years later that same director called me again."
        double[] r = ClipAligner.align(t.segments, t.words, -1, -1, 9, 25.5, 90);
        assertTrue("ended at " + r[1], ClipAligner.endsOnThought(t.segments, t.words, r[1] - 0.3));
        String last = ClipAligner.openingLine(t.segments, t.words, r[1] - 3.2, r[1]);
        assertFalse(last, last.trim().isEmpty());
        assertTrue(r[1] - r[0] <= ClipAligner.MAX_LEN);
    }

    @Test public void lengthCapIs120() {
        // v18 (2.9): the cap is now 1:30 (Sakib's rule); the name is kept for history
        assertEquals(90.0, ClipAligner.MAX_LEN, 0.01);
        Transcript t = V11Test.tr(V11Test.TALK);
        double[] r = ClipAligner.align(t.segments, t.words, -1, -1, 0, 400, 90);
        assertTrue(r[1] - r[0] <= 90.5);
    }

    // ------------------------------------------------------------------ 6. rounded highlight box

    @Test public void roundedRectDrawingIsClosedAndRounded() {
        String d = CaptionBuilder.roundedRect(100, 40, 40 * CaptionBuilder.BOX_RADIUS);
        assertTrue(d.startsWith("m 11 0"));
        assertEquals(4, d.split(" b ").length - 1);
        assertTrue(d.endsWith("11 0"));
    }

    @Test public void boxWordUsesRoundedVectorBoxInSyncWithWord() {
        CaptionStyle st = new CaptionStyle();
        st.anim = CaptionStyle.Anim.BOX_WORD;
        st.highlightColor = 0xFF7C3AED;
        List<Word> w = Arrays.asList(new Word("Hello", 0.0, 0.4), new Word("big", 0.5, 0.9), new Word("world", 1.0, 1.6));
        String ass = CaptionBuilder.buildAss(w, st, new CaptionLayout(), 2.0);
        assertFalse("no square BorderStyle-3 box event", ass.contains(",Box,"));
        Matcher m = Pattern.compile("Dialogue: 2,([0-9:.]+),([0-9:.]+),Main,[^\\n]*\\\\p1}m ").matcher(ass);
        List<String> boxes = new ArrayList<>();
        while (m.find()) boxes.add(m.group(1) + "-" + m.group(2));
        assertEquals(ass, 3, boxes.size());
        Matcher t = Pattern.compile("Dialogue: 3,([0-9:.]+),([0-9:.]+),Main,").matcher(ass);
        List<String> texts = new ArrayList<>();
        while (t.find()) texts.add(t.group(1) + "-" + t.group(2));
        assertEquals("box and word on top share exact times", boxes, texts);
        // Hindi too
        st.fontKey = "noto_deva";
        String hi = CaptionBuilder.buildAss(Arrays.asList(new Word("\u0928\u092E\u0938\u094D\u0924\u0947", 0, 0.6), new Word("\u0926\u094B\u0938\u094D\u0924\u094B\u0902", 0.7, 1.3)), st, new CaptionLayout(), 2.0);
        assertTrue(hi.contains("\\p1}m "));
    }

    @Test public void boxGeometryHugsTheText() {
        CaptionFonts.Font f = CaptionFonts.get("montserrat");
        float[] g = CaptionBuilder.boxGeom("HELLO", 200, 60, f);
        assertTrue(g[0] > 200 && g[0] < 230);
        assertTrue("height " + g[1], g[1] > 30 && g[1] < 60);
        float[] h = CaptionBuilder.boxGeom("\u0928\u092E\u0938\u094D\u0924\u0947", 200, 60, CaptionFonts.get("noto_deva"));
        assertTrue(h[1] > 30 && h[1] < 70);
    }

    // ------------------------------------------------------------------ 5. size handle

    @Test public void sizeClamp() {
        assertEquals(0.6f, CaptionPreviewView.clampSize(0.1f), 1e-4);
        assertEquals(1.6f, CaptionPreviewView.clampSize(9f), 1e-4);
        assertEquals(1.23f, CaptionPreviewView.clampSize(1.234f), 1e-4);
    }
}
