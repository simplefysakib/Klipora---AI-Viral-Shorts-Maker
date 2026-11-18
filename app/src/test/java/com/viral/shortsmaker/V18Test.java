package com.viral.shortsmaker;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.captions.CaptionStyles;
import com.viral.shortsmaker.captions.Word;
import com.viral.shortsmaker.core.*;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** v18 (2.9): unlock rules, clip endings (topic complete, <= 1:30, never mid-sentence), face tiles, retry after a kill. */
@RunWith(RobolectricTestRunner.class)
public class V18Test {
    Context c;
    SharedPreferences sp;
    static final long H = 3600_000L, MIN = 60_000L;

    @Before public void setUp() {
        c = ApplicationProvider.getApplicationContext();
        sp = Prefs.sp(c);
        sp.edit().clear().commit();
    }

    private String res(String n) throws Exception {
        return new String(Files.readAllBytes(new File(getClass().getClassLoader().getResource(n).toURI()).toPath()), "UTF-8");
    }

    // ------------------------------------------------------------------ unlock rules

    @Test public void timedUnlockIsClampedAgainstClockChanges() {
        long now = 1_800_000_000_000L;
        assertEquals(H, Prefs.timedLeftForTest(now, now, H));
        assertEquals(30 * MIN, Prefs.timedLeftForTest(now, now - 30 * MIN, H));
        assertEquals(0, Prefs.timedLeftForTest(now, now - 61 * MIN, H));
        // the phone's clock was set back 2 days after the unlock: never more than the unlock's own length
        assertEquals(H, Prefs.timedLeftForTest(now - 2 * 24 * H, now, H));
        // clock moved forward a day: expired
        assertEquals(0, Prefs.timedLeftForTest(now + 24 * H, now, H));
        assertEquals(0, Prefs.timedLeftForTest(now, 0, H));
    }

    @Test public void hdOneAdIsOneHourAndSurvivesRestart() {
        org.junit.Assume.assumeFalse("rewarded-ad unlock rule of the normal app; premium edition: PremiumEditionTest", com.viral.shortsmaker.core.Prefs.PREMIUM_EDITION);
        sp.edit().putBoolean("migrated_v18", true).commit();
        assertFalse(Prefs.hdUnlocked(c));
        Prefs.unlockHd(c);
        assertTrue(Prefs.hdUnlocked(c));
        long left = Prefs.hdLeftMs(c);
        assertTrue(left > 59 * MIN && left <= H);
        // "restart": a fresh read of the same stored prefs
        SharedPreferences again = c.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
        assertTrue(again.getLong("hd_unlock_time", 0) > 0);
        assertTrue(Prefs.hdUnlocked(c));
        // 61 minutes later: locked again
        sp.edit().putLong("hd_unlock_time", System.currentTimeMillis() - 61 * MIN).commit();
        assertFalse(Prefs.hdUnlocked(c));
        // clock set back by a day after unlocking: still at most 1 hour
        sp.edit().putLong("hd_unlock_time", System.currentTimeMillis() + 24 * H).commit();
        assertTrue(Prefs.hdLeftMs(c) <= H);
    }

    @Test public void captionsOneAdIsOneVideo() {
        org.junit.Assume.assumeFalse("rewarded-ad unlock rule of the normal app; premium edition: PremiumEditionTest", com.viral.shortsmaker.core.Prefs.PREMIUM_EDITION);
        sp.edit().putBoolean("migrated_v18", true).commit();
        assertFalse(Prefs.captionsUnlocked(c));
        assertFalse(Prefs.useCaptionCredit(c));
        Prefs.addCaptionCredit(c);
        assertTrue(Prefs.captionsUnlocked(c));
        assertTrue(Prefs.useCaptionCredit(c));      // the next video uses it
        assertFalse(Prefs.captionsUnlocked(c));     // ...and the one after needs a new ad
        assertFalse(Prefs.useCaptionCredit(c));
        for (int i = 0; i < 9; i++) Prefs.addCaptionCredit(c);
        assertEquals(Prefs.MAX_CREDITS, Prefs.captionCredits(c));
        // old callers: unlockFeature(F_CAPTIONS) = +1 video, unlockFeature(F_HD) = 1 hour
        sp.edit().putInt("caption_credits", 0).commit();
        Prefs.unlockFeature(c, Prefs.F_CAPTIONS);
        assertEquals(1, Prefs.captionCredits(c));
        Prefs.unlockFeature(c, Prefs.F_HD);
        assertTrue(Prefs.hdLeftMs(c) > 59 * MIN);
    }

    @Test public void captionsCreditUsedOncePerVideoAndKeptForItsRetry() {
        org.junit.Assume.assumeFalse("rewarded-ad unlock rule of the normal app; premium edition: PremiumEditionTest", com.viral.shortsmaker.core.Prefs.PREMIUM_EDITION);
        sp.edit().putBoolean("migrated_v18", true).commit();
        Pipeline pl = new Pipeline(c);
        Project pr = new Project();
        pr.url = "https://youtu.be/dQw4w9WgXcQ";
        assertFalse(pl.captionsAllowedForTest(pr, CaptionStyles.byId("hormozi")));   // locked
        Prefs.addCaptionCredit(c);
        assertTrue(pl.captionsAllowedForTest(pr, CaptionStyles.byId("hormozi")));
        assertTrue(pr.captionsPaid);
        assertEquals(0, Prefs.captionCredits(c));
        // re-styling this video's shorts in Caption Studio: free
        assertTrue(pl.captionsAllowedForTest(pr, CaptionStyles.byId("hormozi")));
        // the phone killed the job; the retry builds a new project of the SAME video: still unlocked, no new ad
        Project retry = new Project();
        retry.url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        assertTrue(pl.captionsAllowedForTest(retry, CaptionStyles.byId("hormozi")));
        // a different video needs a new ad
        Project other = new Project();
        other.url = "https://youtu.be/aaaaaaaaaaa";
        assertFalse(pl.captionsAllowedForTest(other, CaptionStyles.byId("hormozi")));
        assertFalse(pl.captionsAllowedForTest(other, null));
        // Project JSON keeps the flag (app restart)
        assertTrue(Project.fromJson(pr.toJson()).captionsPaid);
    }

    @Test public void faceTrackingOneAdIsOnePodcastProUnlimited() {
        org.junit.Assume.assumeFalse("rewarded-ad unlock rule of the normal app; premium edition: PremiumEditionTest", com.viral.shortsmaker.core.Prefs.PREMIUM_EDITION);
        sp.edit().putBoolean("migrated_v18", true).commit();
        assertFalse(Prefs.useFaceTrack(c));
        Prefs.addFaceTrackCredit(c);
        assertTrue(Prefs.faceTrackUnlocked(c));
        assertTrue(Prefs.useFaceTrack(c));
        assertFalse(Prefs.faceTrackUnlocked(c));
        // PRO pass: unlimited, credits untouched
        sp.edit().putLong("premium_unlock_time", System.currentTimeMillis()).putInt("face_track_credits", 1).commit();
        for (int i = 0; i < 4; i++) assertTrue(Prefs.useFaceTrack(c));
        assertEquals(1, Prefs.faceTrackCredits(c));
        assertTrue(Prefs.useCaptionCredit(c));
        assertTrue(Prefs.hdUnlocked(c));
        // PRO is 24 h, also clamped against a clock set back
        sp.edit().putLong("premium_unlock_time", System.currentTimeMillis() + 5 * 24 * H).commit();
        assertTrue(Prefs.premiumLeftMs(c) <= 24 * H);
        sp.edit().putLong("premium_unlock_time", System.currentTimeMillis() - 25 * H).commit();
        assertFalse(Prefs.isPremium(c));
    }

    @Test public void migrationFrom28KeepsWhatWasEarned() {
        org.junit.Assume.assumeFalse("rewarded-ad unlock rule of the normal app; premium edition: PremiumEditionTest", com.viral.shortsmaker.core.Prefs.PREMIUM_EDITION);
        long now = System.currentTimeMillis();
        // 2.8: HD unlocked 1 h ago (3 h rule -> 2 h left), captions unlocked 20 min ago, an expired one ignored
        sp.edit().putLong("unlock_hd1080", now - H).putLong("unlock_captions", now - 20 * MIN).commit();
        Prefs.migrateV18(c);
        long hd = Prefs.hdLeftMs(c);
        assertTrue("hd left " + hd, hd > 119 * MIN && hd <= 2 * H);
        assertEquals(1, Prefs.captionCredits(c));
        Prefs.migrateV18(c);   // idempotent
        assertEquals(1, Prefs.captionCredits(c));
        assertFalse(sp.contains("unlock_hd1080"));
        sp.edit().clear().commit();
        sp.edit().putLong("unlock_hd1080", now - 4 * H).putLong("unlock_captions", now - 5 * H).commit();
        assertFalse(Prefs.hdUnlocked(c));
        assertEquals(0, Prefs.captionCredits(c));
    }

    @Test public void retryRequestSurvivesProcessDeath() {
        Pipeline.Request rq = new Pipeline.Request();
        rq.ytUrl = "https://youtu.be/dQw4w9WgXcQ"; rq.title = "T"; rq.duration = 1234; rq.count = 3;
        rq.category = PromptManager.Category.PODCAST; rq.faceTrack = true; rq.aspect = FfmpegCommands.Aspect.SQUARE;
        rq.style = CaptionStyles.byId("hormozi"); rq.script = CaptionScript.HINDI;
        Pipeline.Request b = Pipeline.Request.fromJson(rq.toJson());
        assertNotNull(b);
        assertEquals(rq.ytUrl, b.ytUrl); assertEquals(3, b.count); assertEquals(PromptManager.Category.PODCAST, b.category);
        assertTrue(b.faceTrack); assertEquals(FfmpegCommands.Aspect.SQUARE, b.aspect); assertEquals(CaptionScript.HINDI, b.script);
        assertNotNull(b.style); assertEquals("hormozi", b.style.id);
        assertNull(Pipeline.Request.fromJson(""));
        assertNull(Pipeline.Request.fromJson("{\"kind\":\"CAPTION\",\"ytUrl\":\"x\"}"));
    }

    // ------------------------------------------------------------------ clip endings

    /** builds a transcript from sentences: each word 0.3 s, 0.05 s gaps, `pause` after each sentence */
    static Transcript tr(String[] sentences, double pause, boolean punct) {
        Transcript t = new Transcript();
        t.language = "hi";
        double at = 0;
        for (String s : sentences) {
            String[] ws = s.split(" ");
            double st = at;
            StringBuilder txt = new StringBuilder();
            for (int i = 0; i < ws.length; i++) {
                String w = ws[i] + (punct && i == ws.length - 1 ? "." : "");
                t.words.add(new Word(w, at, at + 0.3));
                txt.append(w).append(' ');
                at += 0.35;
            }
            t.segments.add(new Transcript.Segment(st, at - 0.05, txt.toString().trim()));
            at += pause;
        }
        return t;
    }

    @Test public void topicLongerThan90sEndsAtLastConclusionBeforeTheLimit() {
        // 40 sentences of 8 words (~3 s each + 0.6 s pause) = ~140 s of one topic
        String[] ss = new String[40];
        for (int i = 0; i < 40; i++) ss[i] = "yeh baat number " + i + " bahut zaroori hai sach";
        Transcript t = tr(ss, 0.6, true);
        double e = ClipAligner.endFor(t.segments, t.words, 0, 140, 200);
        assertTrue("end " + e, e <= ClipAligner.MAX_LEN + 0.01);
        assertTrue("end " + e + " uses most of the 90 s", e > 80);
        assertTrue(ClipAligner.endStrengthAt(t.segments, t.words, e) >= 3);
        // the clip ends right after a sentence's last word (+ tail pad), never inside a sentence
        double lastWordEnd = -1;
        for (Word w : t.words) if (w.end <= e + 0.01) lastWordEnd = w.end;
        boolean sentenceEnd = false;
        for (Transcript.Segment g : t.segments) if (Math.abs(g.end - lastWordEnd) < 0.06) sentenceEnd = true;
        assertTrue(sentenceEnd);
        assertTrue(e - lastWordEnd <= 0.46);
    }

    @Test public void neverEndsOnAConnectorOrBeforeTheNextSentenceContinues() {
        // unpunctuated Hindi: pauses decide; a sentence that ends on "aur"/"और" is never an end
        Transcript t = new Transcript();
        t.language = "hi";
        String[] w = {"मैंने", "उस", "दिन", "सोचा", "और", /*pause*/ "फिर", "मैं", "मुंबई", "चला", "गया", /*pause*/ "वहाँ", "सब", "बदल", "गया", "था"};
        double[] gapAfter = {0.05, 0.05, 0.05, 0.05, 0.9, 0.05, 0.05, 0.05, 0.05, 0.8, 0.05, 0.05, 0.05, 0.05, 1.0};
        double at = 0;
        for (int i = 0; i < w.length; i++) { t.words.add(new Word(w[i], at, at + 0.4)); at += 0.4 + gapAfter[i]; }
        t.segments.add(new Transcript.Segment(0, at, String.join(" ", w)));
        // the AI said: end after "और" (at ~2.2 s). "और" can never end a clip; the next real end is after "गया"
        double e = ClipAligner.endFor(t.segments, t.words, 0, 2.2, 100);
        assertTrue("end " + e, e > t.words.get(9).end - 0.01);
        // "फिर…" continues the thought, so the end after "सोचा और" is weak, and after "चला गया" strong
        assertTrue(ClipAligner.endStrengthAt(t.segments, t.words, t.words.get(4).end) < 3);
        assertTrue(ClipAligner.endStrengthAt(t.segments, t.words, t.words.get(9).end) >= 3);
    }

    @Test public void endingOnAQuestionIsNotAnEnd() {
        Transcript t = tr(new String[]{"So what happens there?", "We see a lot of bone loss in astronauts", "That is why they exercise every day"}, 0.8, true);
        assertTrue(ClipAligner.endStrengthAt(t.segments, t.words, t.segments.get(0).end) < 3);
        assertTrue(ClipAligner.endStrengthAt(t.segments, t.words, t.segments.get(2).end) >= 3);
    }

    @Test public void shortClipIsExtendedToAtLeast20s() {
        String[] ss = new String[12];
        for (int i = 0; i < 12; i++) ss[i] = "sentence number " + i + " is right here now";
        Transcript t = tr(ss, 0.7, true);
        double e = ClipAligner.endFor(t.segments, t.words, 0, 6, 100);
        assertTrue("end " + e, e >= ClipAligner.MIN_LEN);
    }

    @Test public void phrasesSplitLongWhisperSegments() {
        Transcript t = new Transcript();
        StringBuilder txt = new StringBuilder();
        for (int i = 0; i < 60; i++) { t.words.add(new Word("w" + i, i * 0.35, i * 0.35 + 0.3)); txt.append("w").append(i).append(' '); }
        t.segments.add(new Transcript.Segment(0, 21, txt.toString().trim()));
        List<Object[]> ph = ClipAligner.phrases(t.segments, t.words, 0, 30);
        int words = 0;
        for (Object[] p : ph) { int n = ((String) p[2]).split(" ").length; assertTrue(n <= 14); words += n; }
        assertEquals(60, words);
        assertTrue(ph.size() >= 5);
    }

    @Test public void endingPassPromptAndParser() throws Exception {
        String[] ss = new String[40];
        for (int i = 0; i < 40; i++) ss[i] = "point " + i + " of this long story is told here";
        Transcript t = tr(ss, 0.6, true);
        Project.Clip cl = new Project.Clip(); cl.start = 0; cl.end = 30; cl.hook = "point 0"; cl.title = "x";
        List<Project.Clip> clips = new ArrayList<>(); clips.add(cl);
        String p = AiClient.buildEndPrompt(clips, t, 200);
        assertTrue(p.contains("90 SECOND LIMIT"));
        assertTrue(p.contains("20 SECONDS"));
        assertTrue(p.contains("=== CLIP 0"));
        List<AiClient.EndPick> picks = AiClient.parseEnds("```json\n[{\"clip\":0,\"end_phrase\":12,\"complete\":true,\"reason\":\"done\"}]\n```");
        assertEquals(1, picks.size());
        assertEquals(12, picks.get(0).phrase);
        assertTrue(AiClient.parseEnds("not json").isEmpty());
        assertEquals(1, AiClient.parseEnds("{\"clip\":0,\"end_phrase\":3}").size());
        // applying: lands on a sentence end, inside the limit
        int moved = AiClient.applyEnds(clips, t, 200, picks);
        assertEquals(1, moved);
        assertTrue(cl.end > 30 && cl.end <= 90.01);
        assertTrue(ClipAligner.endStrengthAt(t.segments, t.words, cl.end) >= 3);
        // an end past the limit is pulled back before 1:30
        AiClient.EndPick far = new AiClient.EndPick(); far.clip = 0; far.phrase = 35;
        List<AiClient.EndPick> fl = new ArrayList<>(); fl.add(far);
        AiClient.applyEnds(clips, t, 200, fl);
        assertTrue(cl.end <= ClipAligner.MAX_LEN + 0.01);
    }

    @Test public void promptAsksForTheEndingAndViralSignals() {
        String p = PromptManager.buildViralPrompt(PromptManager.Category.PODCAST, "t", "0 [0-5] hello", 5, 600);
        assertTrue(p.contains("end_text"));
        assertTrue(p.contains("90 seconds"));
        assertTrue(p.contains("VARIETY"));
        assertTrue(p.contains("HOOK in the first 3 seconds"));
        assertFalse(p.contains("120"));
    }

    @Test public void candidateParserReadsEndText() throws Exception {
        List<AiClient.Candidate> cs = AiClient.parseCandidatesForTest("[{\"start\":10,\"end\":50,\"hook_text\":\"a b c\",\"end_text\":\"x y z\",\"end_reason\":\"payoff\",\"topic_complete\":false,\"viral_score\":80}]", 600);
        assertEquals("x y z", cs.get(0).endText);
        assertEquals("payoff", cs.get(0).endReason);
        assertEquals(Boolean.FALSE, cs.get(0).topicComplete);
    }

    /** real transcripts (Hindi Whisper, Hinglish, English) + the AI answers measured on the box: the picker's output */
    @Test public void realTranscriptsEndWithinLimitAndNeverOnAConnector() throws Exception {
        int clips = 0, strong = 0;
        for (String k : new String[]{"hi_pod", "his_s", "en", "hi2", "his"}) {
            JSONObject in = new JSONObject(res("v18/" + k + "_in.json"));
            Transcript t = Transcript.fromJson(res("v18/" + k + "_transcript.json"));
            double dur = in.getDouble("duration");
            List<AiClient.Candidate> cands = AiClient.parseCandidatesForTest(res("v18/" + k + "_answer.txt"), dur);
            List<Project.Clip> picked = Pipeline.pickClips(cands, t, dur, 5, PromptManager.Category.PODCAST);
            AiClient.applyEnds(picked, t, dur, AiClient.parseEnds(res("v18/" + k + "_ends_answer.txt")));
            assertFalse(k, picked.isEmpty());
            for (Project.Clip cl : picked) {
                clips++;
                assertTrue(k + " len " + (cl.end - cl.start), cl.end - cl.start <= ClipAligner.MAX_LEN + 0.5);
                assertTrue(k + " len " + (cl.end - cl.start), cl.end - cl.start >= 8);
                String last = lastWord(t, cl.end);
                assertFalse(k + " ends on '" + last + "'", ClipAligner.isNoEndWordForTest(last));
                double es = ClipAligner.endStrengthAt(t.segments, t.words, cl.end);
                if (es >= 3) strong++;
                System.out.println("V18END " + k + " " + Math.round(cl.start) + " len " + Math.round(cl.end - cl.start) + " strength " + es + " last '" + last + "'");
                for (Project.Clip o : picked) if (o != cl) assertTrue(Math.min(o.end, cl.end) - Math.max(o.start, cl.start) <= 1.0);
            }
        }
        assertTrue(strong + " of " + clips, strong >= clips * 0.8);
    }

    static String lastWord(Transcript t, double end) {
        String s = "";
        for (Word w : t.words) if (w.end <= end + 0.01) s = w.text;
        return s;
    }

    @Test public void endMovesOntoThePauseInTheAudio() {
        // 10 ms frames from t=10.0: voice until 12.30 s, silence after; Whisper said the last word ended at 12.10
        float[] env = new float[400];
        for (int i = 0; i < env.length; i++) env[i] = (10.0 + i * 0.01) < 12.30 ? 3.0f + (i % 7) * 0.05f : 0.4f;
        double e = Pipeline.endOnPause(env, 10.0, 12.10, Double.MAX_VALUE);
        assertEquals(12.42, e, 0.03);    // the last syllable is kept, + ~0.12 s
        // the next word starts at 12.35: never into it
        e = Pipeline.endOnPause(env, 10.0, 12.10, 12.35);
        assertTrue(e <= 12.311 && e >= 12.10);
        // no speech/silence contrast: no change
        float[] flat = new float[400];
        java.util.Arrays.fill(flat, 2f);
        assertEquals(-1, Pipeline.endOnPause(flat, 10.0, 12.1, 20), 1e-9);
    }

    // ------------------------------------------------------------------ face tiles

    @Test public void faceTilesMergeWithoutDuplicates() {
        List<FaceTrack.Face> fs = new ArrayList<>();
        fs.add(new FaceTrack.Face(0.20f, 0.30f, 0.26f, 0.42f, 0.02f));
        assertEquals(1, FaceAnalyzer.lipFacesForTest(fs));
        // the same face found again in the left half (slightly different box): a duplicate
        assertTrue(FaceAnalyzer.dupTightForTest(fs, new FaceTrack.Face(0.205f, 0.305f, 0.262f, 0.425f, 0.03f)));
        // the second person on the right: new
        assertFalse(FaceAnalyzer.dupTightForTest(fs, new FaceTrack.Face(0.70f, 0.32f, 0.76f, 0.44f, 0.01f)));
        fs.add(new FaceTrack.Face(0.70f, 0.32f, 0.76f, 0.44f, -1f));   // a body-model head has no lip value
        assertEquals(1, FaceAnalyzer.lipFacesForTest(fs));
    }
}
